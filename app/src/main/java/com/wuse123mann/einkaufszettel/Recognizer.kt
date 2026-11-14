package com.wuse123mann.einkaufszettel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Erkannt(val name: String, val menge: String, val kat: String, val unsicher: Boolean)

class RecognitionException(message: String) : Exception(message)

object Bilder {
    /** Lädt ein Bild verkleinert (längste Seite max. [maxSize] px), Drehung laut EXIF wird berücksichtigt. */
    fun load(context: Context, uri: Uri, maxSize: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, maxSize.toFloat() / maxOf(w, h))
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
    }

    fun thumb(bmp: Bitmap, height: Int = 260): Bitmap {
        if (bmp.height <= height) return bmp
        val w = (bmp.width * height.toFloat() / bmp.height).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, height, true)
    }
}

/** Handschrifterkennung über die Gemini-API (Gratis-Tarif, Schlüssel aus Google AI Studio). */
object Gemini {
    private const val FALLBACK_MODELL = "gemini-flash-lite-latest"

    private fun prompt(anzahl: Int): String {
        val start = if (anzahl > 1)
            "Du bekommst $anzahl Bilder. Sie zeigen zusammen eine handgeschriebene Einkaufsliste (evtl. mehrere Zettel oder Seiten). " +
                "Fasse alle Einträge zu EINER Liste zusammen; steht derselbe Artikel doppelt drauf, nimm ihn nur einmal."
        else "Auf dem Bild ist eine handgeschriebene Einkaufsliste."
        return """
$start Sprache meist Deutsch, evtl. österreichisch.
Lies JEDEN Eintrag. Regeln:
- Ein Artikel pro Eintrag; steht "Äpfel + Birnen" auf einer Zeile, mach zwei Artikel daraus.
- Korrigiere Rechtschreibung, schreib eindeutige Abkürzungen aus, Nomen groß.
- Mengenangaben (z. B. "2x", "500 g", "1 l", "3 Stk") gehören ins Feld "menge", nicht in den Namen. Keine Menge -> "".
- Durchgestrichene Einträge weglassen. Überschriften, Datum oder Kritzeleien ignorieren.
- Ordne jeden Artikel genau einer Kategorie zu: ${KAT_NAMEN.joinToString(", ")}.
- Wenn ein Wort schwer lesbar ist: beste Vermutung als Name und "unsicher": true.
- Ist keine Einkaufsliste erkennbar, gib {"items":[],"hinweis":"<kurzer Grund>"} zurück.
Antworte NUR mit JSON in genau diesem Format:
{"items":[{"name":"Milch","menge":"2 l","kategorie":"Milch & Käse","unsicher":false}]}
""".trim()
    }

    suspend fun read(bitmaps: List<Bitmap>, apiKey: String, modell: String): List<Erkannt> = withContext(Dispatchers.IO) {
        val parts = JSONArray().put(JSONObject().put("text", prompt(bitmaps.size)))
        for (bmp in bitmaps) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                )
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            .toString()

        val antwort = anfrage(body, apiKey, modell)
        parseItems(antwort)
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(RobustDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_TYP = "application/json; charset=utf-8".toMediaType()

    private fun url(modell: String, aktion: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(modell, "UTF-8") + aktion

    private fun ausfuehren(req: Request): Pair<Int, String> =
        client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }

    @Volatile private var gefundeneModelle: List<String>? = null

    private fun versionsNummer(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /** Fragt bei Google ab, welche Flash-Modelle mit diesem Schlüssel verfügbar sind (neueste zuerst). */
    private fun verfuegbareModelle(apiKey: String): List<String> {
        gefundeneModelle?.let { return it }
        return try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                .header("x-goog-api-key", apiKey).get().build()
            val (code, text) = ausfuehren(req)
            if (code !in 200..299) return emptyList()
            val arr = JSONObject(text).optJSONArray("models") ?: return emptyList()
            val ausschluss = listOf("image", "tts", "live", "audio", "embedding", "exp", "robotics", "computer", "transcribe")
            val liste = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").removePrefix("models/")
                val methoden = o.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                if (!methoden.contains("generateContent")) continue
                if (!name.contains("flash") || ausschluss.any { name.contains(it) }) continue
                liste += name
            }
            val sortiert = liste.sortedWith(
                compareBy<String>({ it.contains("preview") }, { -versionsNummer(it) }, { it.contains("lite") }, { it.length })
            ).take(5)
            gefundeneModelle = sortiert
            sortiert
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun modelle(modell: String, apiKey: String): List<String> =
        (listOf(modell.trim().ifBlank { STANDARD_MODELL }, STANDARD_MODELL, FALLBACK_MODELL) + verfuegbareModelle(apiKey))
            .distinct().take(7)

    /** Schneller Test: fragt nur die Modell-Info ab (prüft Verbindung + Schlüssel, ohne KI-Rechenzeit). */
    suspend fun test(apiKey: String, modell: String): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        for (m in modelle(modell, apiKey)) {
            val req = Request.Builder().url(url(m, "")).header("x-goog-api-key", apiKey).get().build()
            val (code, text) = ausfuehren(req)
            if (code in 200..299) return@withContext "Schlüssel funktioniert ✓ (${sekunden(t0)}, Modell $m)"
            if (code != 404) fehlerFuerCode(code, text)
        }
        throw RecognitionException("Kein passendes Gemini-Modell gefunden. Trag unter „Gemini-Modell“ z. B. $FALLBACK_MODELL ein.")
    }

    fun sekunden(t0: Long): String = String.format(java.util.Locale.GERMAN, "%.1f s", (System.currentTimeMillis() - t0) / 1000.0)

    /**
     * Schickt die Anfrage. Bei Überlastung (5xx) ein weiterer Versuch mit Pause,
     * danach – wie bei 404 oder ausgeschöpftem Kontingent (429) – das nächste verfügbare Modell.
     */
    private fun anfrage(body: String, apiKey: String, modell: String): String {
        var ueberlastet = 0
        var kontingent = false
        for (m in modelle(modell, apiKey)) {
            var versuch = 0
            while (true) {
                val req = Request.Builder()
                    .url(url(m, ":generateContent"))
                    .header("x-goog-api-key", apiKey)
                    .post(body.toRequestBody(JSON_TYP))
                    .build()
                val (code, text) = ausfuehren(req)
                when {
                    code in 200..299 -> return antwortText(text)
                    code >= 500 -> {
                        ueberlastet = code
                        if (versuch < 1) { versuch++; Thread.sleep(1500); continue }
                    }
                    code == 429 -> kontingent = true
                    code == 404 -> Unit
                    else -> fehlerFuerCode(code, text)
                }
                break
            }
        }
        when {
            ueberlastet > 0 -> fehlerFuerCode(ueberlastet, "")
            kontingent -> fehlerFuerCode(429, "")
            else -> throw RecognitionException("Kein passendes Gemini-Modell gefunden. Bitte unter „Gemini-Modell“ den Standard $STANDARD_MODELL eintragen.")
        }
    }

    private fun fehlerFuerCode(code: Int, text: String): Nothing = when {
        code == 400 && text.contains("API_KEY_INVALID") ->
            throw RecognitionException("Der API-Schlüssel ist ungültig. Bitte in den Einstellungen prüfen.")
        code == 400 && text.contains("location", ignoreCase = true) ->
            throw RecognitionException("Gemini ist von deinem Standort aus nicht verfügbar: ${fehlertext(text)}")
        code == 401 || code == 403 ->
            throw RecognitionException("Der API-Schlüssel wurde abgelehnt (${fehlertext(text)}). Bitte in den Einstellungen prüfen.")
        code == 429 ->
            throw RecognitionException("Das Gratis-Kontingent ist gerade aufgebraucht (bei allen Modellen). Warte eine Minute und versuch es erneut – oder nutze die Offline-Erkennung.")
        code >= 500 ->
            throw RecognitionException("Alle Gemini-Modelle sind gerade überlastet ($code). Bitte in ein paar Minuten noch einmal versuchen – oder vorübergehend die Offline-Erkennung nutzen.")
        else -> throw RecognitionException("Fehler $code: ${fehlertext(text)}")
    }

    /** Verständliche Erklärung eines Netzwerkfehlers, inkl. technischem Grund zur Fehlersuche. */
    fun netzFehlerText(e: java.io.IOException, dauer: String? = null): String {
        val grund = when (e) {
            is java.net.UnknownHostException ->
                "Die Adresse des Gemini-Servers konnte nicht ermittelt werden – auch nicht über das Ausweich-DNS. Meist blockiert dann etwas am Handy die App: Werbeblocker/Firewall-App, VPN, „Privates DNS“ (Einstellungen → Netzwerk) oder gesperrte Datennutzung für die App."
            is java.net.SocketTimeoutException ->
                "Der Server hat nicht rechtzeitig geantwortet. Läuft ein VPN, Werbeblocker oder eine Firewall-App? Sonst bitte im WLAN erneut versuchen."
            is javax.net.ssl.SSLException ->
                "Die sichere Verbindung ist fehlgeschlagen. Stimmen Datum und Uhrzeit am Handy? Läuft ein VPN oder Werbeblocker?"
            is java.net.ConnectException ->
                "Die Verbindung wurde abgelehnt. Läuft ein VPN, eine Firewall oder ein Werbeblocker?"
            else -> "Keine Verbindung zum Server."
        }
        val zeit = if (dauer != null) " nach $dauer" else ""
        return "$grund\n\nTechnisch$zeit: ${e.javaClass.simpleName}: ${e.message ?: "-"}"
    }

    private fun fehlertext(text: String): String = try {
        JSONObject(text).getJSONObject("error").optString("message").take(200)
    } catch (e: Exception) {
        text.take(200)
    }

    private fun antwortText(text: String): String {
        val root = JSONObject(text)
        val cands = root.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val grund = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw RecognitionException("Keine Antwort erhalten${if (grund.isNotBlank()) " ($grund)" else ""}.")
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    private fun parseItems(text: String): List<Erkannt> {
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) throw RecognitionException("Die Antwort konnte nicht ausgewertet werden. Bitte noch einmal versuchen.")
        val obj = try {
            JSONObject(text.substring(a, b + 1))
        } catch (e: Exception) {
            throw RecognitionException("Die Antwort konnte nicht ausgewertet werden. Bitte noch einmal versuchen.")
        }
        val arr = obj.optJSONArray("items") ?: JSONArray()
        if (arr.length() == 0) {
            val hinweis = obj.optString("hinweis")
            throw RecognitionException(
                if (hinweis.isNotBlank()) "Keine Einträge gefunden: $hinweis"
                else "Auf dem Foto wurden keine Einträge gefunden. Achte auf gutes Licht und fotografier den Zettel möglichst gerade von oben."
            )
        }
        val out = ArrayList<Erkannt>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim().take(80)
            if (name.isEmpty()) continue
            val kat = o.optString("kategorie")
            out += Erkannt(
                name = name,
                menge = o.optString("menge").trim().take(30),
                kat = if (kat in KAT_NAMEN) kat else guessKat(name),
                unsicher = o.optBoolean("unsicher"),
            )
        }
        return out
    }
}

/** Offline-Erkennung direkt am Handy (ML Kit). Kein Konto nötig, aber bei Handschrift ungenauer. */
object OfflineLeser {
    private val AUFZAEHLUNG = Regex("^\\s*(?:[-•*·–—>]+|\\d+[.)])\\s*")
    private val TRENNER = Regex("\\s*(?:,|\\+|;)\\s*")

    suspend fun read(bitmaps: List<Bitmap>): List<Erkannt> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val out = ArrayList<Erkannt>()
            for (bmp in bitmaps) {
                val text: Text = suspendCancellableCoroutine { cont ->
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                for (block in text.textBlocks) for (line in block.lines) {
                    val zeile = line.text.replace(AUFZAEHLUNG, "").trim()
                    for (teil in zeile.split(TRENNER)) {
                        if (teil.count { it.isLetter() } < 2) continue
                        val (name, menge) = parseEntry(teil)
                        val schoen = name.replaceFirstChar { it.uppercase() }.take(80)
                        out += Erkannt(schoen, menge.take(30), guessKat(schoen), unsicher = false)
                    }
                }
            }
            return out.distinctBy { it.name.lowercase() }
        } finally {
            recognizer.close()
        }
    }
}

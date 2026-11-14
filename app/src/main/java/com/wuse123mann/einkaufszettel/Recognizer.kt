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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
    private const val FALLBACK_MODELL = "gemini-2.5-flash"

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
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
            .toString()

        val antwort = anfrage(body, apiKey, modell)
        parseItems(antwort)
    }

    /** Kleiner Test, ob Schlüssel und Modell funktionieren. */
    suspend fun test(apiKey: String, modell: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Antworte nur mit: OK")))))
            .toString()
        anfrage(body, apiKey, modell)
        "Schlüssel funktioniert ✓"
    }

    /** Schickt die Anfrage; fällt bei unbekanntem Modell auf ein Standardmodell zurück. Liefert den Antworttext. */
    private fun anfrage(body: String, apiKey: String, modell: String): String {
        val modelle = listOf(modell.trim().ifBlank { STANDARD_MODELL }, STANDARD_MODELL, FALLBACK_MODELL).distinct()
        var letzter: RecognitionException? = null
        for (m in modelle) {
            val (code, text) = post(m, apiKey, body)
            when {
                code in 200..299 -> return antwortText(text)
                code == 404 -> letzter = RecognitionException("Modell „$m“ wurde nicht gefunden.")
                code == 400 && text.contains("API_KEY_INVALID") ->
                    throw RecognitionException("Der API-Schlüssel ist ungültig. Bitte in den Einstellungen prüfen.")
                code == 401 || code == 403 ->
                    throw RecognitionException("Der API-Schlüssel wurde abgelehnt (${fehlertext(text)}). Bitte in den Einstellungen prüfen.")
                code == 429 ->
                    throw RecognitionException("Das Gratis-Kontingent ist gerade aufgebraucht. Warte eine Minute und versuch es erneut – oder nutze die Offline-Erkennung.")
                code >= 500 ->
                    throw RecognitionException("Der Gemini-Server ist gerade überlastet ($code). Bitte gleich noch einmal versuchen.")
                else -> throw RecognitionException("Fehler $code: ${fehlertext(text)}")
            }
        }
        throw letzter ?: RecognitionException("Unbekannter Fehler bei der Erkennung.")
    }

    /** Verständliche Erklärung eines Netzwerkfehlers, inkl. technischem Grund zur Fehlersuche. */
    fun netzFehlerText(e: java.io.IOException): String {
        val grund = when (e) {
            is java.net.UnknownHostException ->
                "Der Gemini-Server wurde nicht gefunden. Prüfe, ob die App mobile Daten/WLAN nutzen darf (Einstellungen → Apps → Einkaufszettel → Mobile Daten) und ob ein Werbeblocker oder „Privates DNS“ googleapis.com sperrt."
            is java.net.SocketTimeoutException ->
                "Der Server hat nicht rechtzeitig geantwortet. Bei schwachem Empfang bitte erneut versuchen."
            is javax.net.ssl.SSLException ->
                "Die sichere Verbindung ist fehlgeschlagen. Stimmen Datum und Uhrzeit am Handy? Läuft ein VPN oder Werbeblocker?"
            is java.net.ConnectException ->
                "Die Verbindung wurde abgelehnt. Läuft ein VPN, eine Firewall oder ein Werbeblocker?"
            else -> "Keine Verbindung zum Server."
        }
        return "$grund\n\nTechnisch: ${e.javaClass.simpleName}: ${e.message ?: "-"}"
    }

    private fun post(modell: String, apiKey: String, body: String): Pair<Int, String> {
        // Ein Wiederholungsversuch bei kurzzeitigen Netzproblemen
        return try {
            postEinmal(modell, apiKey, body)
        } catch (e: java.net.UnknownHostException) {
            throw e
        } catch (e: java.io.IOException) {
            Thread.sleep(1200)
            postEinmal(modell, apiKey, body)
        }
    }

    private fun postEinmal(modell: String, apiKey: String, body: String): Pair<Int, String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(modell, "UTF-8") + ":generateContent")
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 20_000
            con.readTimeout = 120_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", apiKey)
            con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val stream = if (code < 400) con.inputStream else con.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            con.disconnect()
        }
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

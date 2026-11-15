package com.wuse123mann.einkaufszettel

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

const val GROQ_STANDARD_MODELL = "qwen/qwen3.8-27b"

/**
 * Handschrifterkennung über Groq (sehr schnelle Server, Gratis-Tarif ohne Kreditkarte).
 * OpenAI-kompatible Schnittstelle; Schlüssel aus console.groq.com/keys.
 */
object Groq {
    private const val BASIS = "https://api.groq.com/openai/v1"
    private const val BILDER_PRO_ANFRAGE = 3

    /** Weitere Bildmodelle, falls das eingestellte nicht (mehr) verfügbar ist. */
    private val AUSWEICH = listOf(
        GROQ_STANDARD_MODELL,
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "meta-llama/llama-4-maverick-17b-128e-instruct",
    )

    private fun kopf(apiKey: String) = "Authorization" to "Bearer $apiKey"

    suspend fun read(
        bitmaps: List<Bitmap>,
        apiKey: String,
        modell: String,
        melden: (Phase) -> Unit = {},
    ): List<Erkannt> = withContext(Dispatchers.IO) {
        val gruppen = bitmaps.chunked(BILDER_PRO_ANFRAGE)
        val alle = ArrayList<Erkannt>()
        gruppen.forEachIndexed { gi, gruppe ->
            val teil = if (gruppen.size > 1) " (Teil ${gi + 1}/${gruppen.size})" else ""
            alle += lesen(gruppe, apiKey, modell, teil, melden, leerErlaubt = gruppen.size > 1)
        }
        melden(Phase("Liste wird erstellt …", 0.97f, 1f, 1f))
        val ergebnis = alle.distinctBy { it.name.lowercase() }
        if (ergebnis.isEmpty()) {
            throw KeineEintraegeException("Auf dem Foto wurden keine Einträge gefunden. Achte auf gutes Licht und fotografier den Zettel möglichst gerade von oben.")
        }
        ergebnis
    }

    private fun lesen(
        bitmaps: List<Bitmap>,
        apiKey: String,
        modell: String,
        teil: String,
        melden: (Phase) -> Unit,
        leerErlaubt: Boolean,
    ): List<Erkannt> {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", Gemini.prompt(bitmaps.size)))
        bitmaps.forEachIndexed { i, bmp ->
            melden(Phase("Bild wird vorbereitet$teil …", 0.05f, 0.12f, i.toFloat() / bitmaps.size))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        }

        fun body(m: String, ohneNachdenken: Boolean): String {
            val o = JSONObject()
                .put("model", m)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("temperature", 0.2)
            if (ohneNachdenken) o.put("reasoning_effort", "none")
            return o.toString()
        }

        val modelle = (listOf(modell.trim().ifBlank { GROQ_STANDARD_MODELL }) + AUSWEICH).distinct()
        var ueberlastet = 0
        var kontingent = false
        var erstes = true
        for (m in modelle) {
            var versuch = 0
            var ohneNachdenken = true
            while (true) {
                val hochladenText = when {
                    erstes -> "Wird hochgeladen$teil …"
                    versuch > 0 -> "Server ausgelastet – neuer Versuch …"
                    else -> "Weiche auf Modell ${m.substringAfter('/')} aus …"
                }
                erstes = false
                melden(Phase(hochladenText, 0.12f, 0.30f, 0f))
                val (code, text) = Http.post("$BASIS/chat/completions", kopf(apiKey), body(m, ohneNachdenken)) { anteil ->
                    if (anteil < 1f) melden(Phase(hochladenText, 0.12f, 0.30f, anteil))
                    // Groq antwortet meist in wenigen Sekunden → schnellere Zeitkonstante
                    else melden(Phase("KI liest die Handschrift$teil …", 0.30f, 0.95f, null, tau = 4f))
                }
                val fehler = fehlerInfo(text)
                when {
                    code in 200..299 -> return Gemini.parseItems(antwortText(text), leerErlaubt)
                    code == 400 && ohneNachdenken && fehler.contains("reasoning", ignoreCase = true) -> {
                        ohneNachdenken = false
                        continue
                    }
                    code >= 500 -> {
                        ueberlastet = code
                        if (versuch < 1) {
                            versuch++
                            Thread.sleep(1200)
                            continue
                        }
                    }
                    code == 429 -> kontingent = true
                    code == 404 || (code == 400 && modellFehler(fehler)) -> Unit
                    code == 401 -> throw RecognitionException("Der Groq-Schlüssel ist ungültig. Bitte in den Einstellungen prüfen.")
                    code == 403 -> throw RecognitionException("Groq hat die Anfrage abgelehnt: $fehler")
                    code == 413 -> throw RecognitionException("Das Bild ist für Groq zu groß. Bitte mit weniger Bildern auf einmal versuchen.")
                    else -> throw RecognitionException("Groq-Fehler $code: $fehler")
                }
                break
            }
        }
        throw when {
            kontingent -> RecognitionException("Das Groq-Gratiskontingent ist gerade aufgebraucht. Warte eine Minute und versuch es erneut.")
            ueberlastet > 0 -> RecognitionException("Groq ist gerade überlastet ($ueberlastet). Bitte gleich noch einmal versuchen.")
            else -> RecognitionException("Kein Groq-Bildmodell gefunden. Trag unter „Groq-Modell“ den Standard $GROQ_STANDARD_MODELL ein.")
        }
    }

    private fun modellFehler(fehler: String): Boolean {
        val f = fehler.lowercase()
        return listOf("model_not_found", "model_decommissioned", "does not exist", "decommissioned", "not support", "image", "vision")
            .any { f.contains(it) }
    }

    /** Fehlermeldung samt Code aus der JSON-Antwort. */
    private fun fehlerInfo(text: String): String = try {
        val e = JSONObject(text).getJSONObject("error")
        listOf(e.optString("code"), e.optString("message")).filter { it.isNotBlank() }.joinToString(": ").take(250)
    } catch (_: Exception) {
        text.take(250)
    }

    private fun antwortText(text: String): String {
        val root = JSONObject(text)
        val inhalt = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        // Falls doch "Gedanken" mitkommen, diese entfernen
        return inhalt.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    /** Schneller Test: Modellliste abrufen (prüft Verbindung + Schlüssel). */
    suspend fun test(apiKey: String, modell: String): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val (code, text) = Http.get("$BASIS/models", kopf(apiKey))
        when {
            code in 200..299 -> {
                val ids = JSONObject(text).optJSONArray("data")
                val vorhanden = (0 until (ids?.length() ?: 0)).map { ids!!.getJSONObject(it).optString("id") }
                val gewuenscht = modell.trim().ifBlank { GROQ_STANDARD_MODELL }
                val modellText = when {
                    gewuenscht in vorhanden -> "Modell $gewuenscht verfügbar"
                    AUSWEICH.any { it in vorhanden } -> "„$gewuenscht“ nicht gefunden – die App nimmt ${AUSWEICH.first { it in vorhanden }}"
                    else -> "aber kein bekanntes Bildmodell gefunden"
                }
                "Schlüssel funktioniert ✓ (${Gemini.sekunden(t0)}, $modellText)"
            }
            code == 401 -> throw RecognitionException("Der Groq-Schlüssel ist ungültig.")
            else -> throw RecognitionException("Groq-Fehler $code: ${fehlerInfo(text)}")
        }
    }
}

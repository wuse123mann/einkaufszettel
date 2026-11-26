package com.wuse123mann.einkaufszettel

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** Ein Produkt aus dem Hofer-Sortiment (Preis in Euro, wie auf hofer.at). */
data class HoferProdukt(
    val name: String,
    val marke: String,
    val groesse: String,
    val preis: Double,
    val grundpreis: String,
    val kat: String,
    val url: String,
    val pos: Int,
) {
    val anzeige: String get() = listOf(marke, name).filter { it.isNotBlank() }.joinToString(" ")
}

/** Preis eines Listenartikels: das zugeordnete Produkt und der Betrag für die gewünschte Menge. */
data class Preisinfo(val produkt: HoferProdukt, val anzahl: Int, val sicher: Boolean) {
    val summe: Double get() = produkt.preis * anzahl
}

fun euro(betrag: Double): String = "€ " + String.format(Locale.GERMAN, "%.2f", betrag)

/**
 * Hofer-Sortiment mit Preisen. Wird von hofer.at geladen (Kategorieseiten, je 60 Produkte),
 * lokal gespeichert und für die Zuordnung zu Listenartikeln genutzt.
 */
class HoferKatalog(context: Context) {
    private val app = context.applicationContext
    private val datei = File(app.filesDir, "hofer.json")

    var produkte: List<HoferProdukt> = emptyList()
        private set
    var stand: Long = 0L
        private set

    init {
        try {
            if (datei.exists()) {
                val o = JSONObject(datei.readText())
                stand = o.optLong("stand")
                val a = o.getJSONArray("produkte")
                produkte = (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    HoferProdukt(
                        p.getString("n"), p.optString("b"), p.optString("g"), p.getDouble("p"),
                        p.optString("gp"), validKat(p.optString("k")), p.getString("u"), p.optInt("pos"),
                    )
                }
            }
        } catch (_: Exception) {
            produkte = emptyList()
        }
    }

    private fun speichern(liste: List<HoferProdukt>, zeit: Long) {
        val a = JSONArray()
        for (p in liste) {
            a.put(
                JSONObject().put("n", p.name).put("b", p.marke).put("g", p.groesse).put("p", p.preis)
                    .put("gp", p.grundpreis).put("k", p.kat).put("u", p.url).put("pos", p.pos)
            )
        }
        val tmp = File(datei.parentFile, "hofer.json.tmp")
        tmp.writeText(JSONObject().put("stand", zeit).put("produkte", a).toString())
        if (!tmp.renameTo(datei)) { datei.writeText(tmp.readText()); tmp.delete() }
    }

    fun produktZuUrl(url: String): HoferProdukt? = produkte.firstOrNull { it.url == url }

    // ------------------------------------------------------------------ Laden

    /**
     * Lädt das Sortiment neu. [melden] bekommt Text und Fortschritt (0–1).
     * Wirft [RecognitionException] mit verständlicher Meldung, wenn hofer.at nicht lesbar ist.
     */
    suspend fun aktualisieren(melden: (String, Float) -> Unit) {
        val lader = Seitenlader(app)
        try {
            melden("Kategorien werden gesucht …", 0.02f)
            val start = lader.html(START)
            val kategorien = (kategorienAus(start) + BEKANNTE_KATEGORIEN).distinct()
            if (kategorien.isEmpty()) throw RecognitionException("Auf hofer.at wurden keine Produktkategorien gefunden.")

            val gefunden = LinkedHashMap<String, HoferProdukt>()
            var pos = 0
            kategorien.forEachIndexed { ki, kat ->
                val unsereKat = katAusSlug(kat)
                var seite = 1
                while (seite <= 40) {
                    melden(
                        "${kategorieName(kat)} – Seite $seite (${gefunden.size} Produkte)",
                        0.05f + 0.93f * (ki + minOf(seite, 5) / 6f) / kategorien.size,
                    )
                    val url = BASIS + kat + if (seite > 1) "?page=$seite" else ""
                    val html = lader.html(url)
                    val kacheln = kachelnAus(html)
                    var neu = 0
                    for ((href, text) in kacheln) {
                        val voll = if (href.startsWith("http")) href else BASIS + href
                        if (gefunden.containsKey(voll)) continue
                        val p = kachelLesen(text, voll, unsereKat, pos++) ?: continue
                        gefunden[voll] = p
                        neu++
                    }
                    if (neu == 0 || kacheln.size < 20) break
                    seite++
                    delay(250)
                }
            }
            if (gefunden.size < 20) {
                throw RecognitionException("Hofer-Seiten waren nicht lesbar (nur ${gefunden.size} Produkte gefunden). Bitte später noch einmal versuchen.")
            }
            val zeit = System.currentTimeMillis()
            val liste = gefunden.values.toList()
            withContext(Dispatchers.IO) { speichern(liste, zeit) }
            produkte = liste
            stand = zeit
            melden("Fertig: ${liste.size} Produkte", 1f)
        } finally {
            lader.schliessen()
        }
    }

    // ------------------------------------------------------------------ Zuordnung

    /** Die passendsten Produkte für einen Artikelnamen, bestes zuerst. */
    fun kandidaten(name: String, kat: String?, anzahl: Int = 30): List<HoferProdukt> {
        val woerter = normal(name).split(' ').filter { it.length >= 2 }
        if (woerter.isEmpty()) return emptyList()
        return produkte.mapNotNull { p -> bewerten(p, woerter, kat)?.let { p to it } }
            .sortedByDescending { it.second }
            .take(anzahl)
            .map { it.first }
    }

    private fun bewerten(p: HoferProdukt, woerter: List<String>, kat: String?): Double? {
        val pw = normal(p.name).split(' ')
        val markeN = normal(p.marke)
        var punkte = 0.0
        for (w in woerter) {
            val beste = pw.maxOfOrNull { t ->
                when {
                    t == w -> 10.0                                // genau das Wort
                    t == w + "n" || t == w + "e" || t == w + "en" || w == t + "n" || w == t + "e" -> 9.0  // Mehrzahl
                    t.endsWith(w) && w.length >= 4 -> 7.0         // Vollmilch → Milch (Grundwort)
                    t.startsWith(w) && w.length >= 4 -> 5.0       // Milchreis → Milch
                    t.contains(w) && w.length >= 5 -> 3.0
                    else -> 0.0
                }
            } ?: 0.0
            val inMarke = if (beste == 0.0 && markeN.contains(w)) 4.0 else 0.0
            if (beste == 0.0 && inMarke == 0.0) return null          // jedes Wort muss passen
            punkte += beste + inMarke
        }
        if (kat != null && p.kat == kat) punkte += 4.0
        punkte -= (pw.size - woerter.size).coerceAtLeast(0) * 0.6     // kurze, allgemeine Namen bevorzugen
        punkte -= p.pos / 2000.0                                     // Reihenfolge bei Hofer als Gleichstand-Entscheid
        return punkte
    }

    companion object {
        const val BASIS = "https://www.hofer.at"
        private const val START = "https://www.hofer.at/produkte/milchprodukte/k/158816141856710103"

        /** Zur Sicherheit, falls die Kategorie-Links auf der Startseite fehlen. */
        private val BEKANNTE_KATEGORIEN = listOf(
            "/produkte/brot-und-backwaren/k/158816141856710104",
            "/produkte/fisch-und-meeresfruechte/k/158816141856710102",
            "/produkte/fleisch-und-wurstwaren/k/158816141856710101",
            "/produkte/milchprodukte/k/158816141856710103",
        )

        /** Aktions- und Saison-Kategorien überspringen – die Produkte stehen ohnehin im Dauersortiment. */
        private val UEBERSPRINGEN = listOf(
            "weihnacht", "ostern", "black-month", "black-friday", "aktion", "angebot", "hofer-preis",
            "neu", "saison", "fasching", "valentin", "muttertag", "halloween", "flugblatt", "onlineshop",
        )

        internal fun kategorienAus(html: String): List<String> =
            Regex("""href="(?:https://www\.hofer\.at)?(/produkte/([a-z0-9-]+)/k/\d+)"""")
                .findAll(html)
                .filter { m -> UEBERSPRINGEN.none { m.groupValues[2].contains(it) } }
                .map { it.groupValues[1] }
                .distinct()
                .toList()

        private fun kategorieName(pfad: String): String =
            pfad.substringAfter("/produkte/").substringBefore("/k/")
                .replace("-und-", " & ").replace('-', ' ')
                .replace("ue", "ü").replace("ae", "ä").replace("oe", "ö")
                .replaceFirstChar { it.uppercase() }

        /** Hofer-Kategorie → Abteilung in der App (nur als Hilfe bei der Zuordnung). */
        internal fun katAusSlug(pfad: String): String {
            val s = pfad.lowercase()
            return when {
                "tiefk" in s -> "Tiefkühl"
                "obst" in s && "gemuese" !in s -> "Obst"
                "gemuese" in s -> "Gemüse"
                "brot" in s || "back" in s -> "Brot & Backwaren"
                "fleisch" in s || "fisch" in s || "wurst" in s -> "Fleisch & Fisch"
                "milch" in s || "kaese" in s || "molkerei" in s -> "Milch & Käse"
                "kuehl" in s || "feinkost" in s -> "Kühlregal"
                "getraenk" in s || "wein" in s || "bier" in s || "spirituos" in s -> "Getränke"
                "suess" in s || "snack" in s || "knabber" in s -> "Süßes & Snacks"
                "drogerie" in s || "haushalt" in s || "pflege" in s || "baby" in s || "tier" in s -> "Drogerie & Haushalt"
                else -> "Vorrat & Konserven"
            }
        }

        /** Alle Produkt-Kacheln einer Seite: Link und sichtbarer Text. */
        internal fun kachelnAus(html: String): List<Pair<String, String>> =
            Regex("""<a\b[^>]*?href="([^"]*/produkt/[^"#?]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html)
                .map { m ->
                    val ohneTags = m.groupValues[2]
                        .replace(Regex("""<(script|style)\b.*?</\1>""", RegexOption.DOT_MATCHES_ALL), " ")
                        .replace(Regex("<[^>]+>"), " ")
                    val text = HtmlCompat.fromHtml(ohneTags, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                    m.groupValues[1] to text.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
                }
                .filter { it.second.contains('€') }
                .toList()

        private val GRUNDPREIS = Regex("""\(\s*€\s*[\d.,]+\s*/[^)]*\)""")
        private val PREIS = Regex("""€\s*(\d{1,3}(?:\.\d{3})*,\d{2})""")

        /**
         * Liest eine Kachel wie
         * „Frische Vollmilch 3,5% Fett Kühlung MILSANI Frische Vollmilch 3,5% Fett 1 l per Stück (€ 1,32/1 l) € 1,32“.
         * Der Name steht zweimal darin; dazwischen stehen Hinweise und die Marke.
         */
        internal fun kachelLesen(text: String, url: String, kat: String, pos: Int): HoferProdukt? {
            val grundpreis = GRUNDPREIS.find(text)?.value?.trim('(', ')', ' ').orEmpty()
            val ohneGp = text.replace(GRUNDPREIS, " ").replace(Regex("\\s+"), " ")
            val pm = PREIS.find(ohneGp) ?: return null
            val preis = pm.groupValues[1].replace(".", "").replace(',', '.').toDoubleOrNull() ?: return null
            val vorPreis = ohneGp.substring(0, pm.range.first).trim()

            val woerter = vorPreis.split(' ')
            var name = ""
            var zweite = -1
            for (k in woerter.size downTo 1) {
                val kand = woerter.take(k).joinToString(" ")
                if (kand.length < 3) break
                val j = vorPreis.indexOf(kand, kand.length)
                if (j > 0) { name = kand; zweite = j; break }
            }
            var marke = ""
            var groesse = ""
            if (name.isNotEmpty()) {
                marke = vorPreis.substring(name.length, zweite).split(' ')
                    .filter { w -> w.length >= 2 && w.any { it.isLetter() } && w == w.uppercase() }
                    .joinToString(" ")
                groesse = vorPreis.substring(zweite + name.length).trim()
                    .substringBefore(" per ").removePrefix("per ").trim()
                    .let { if (it.startsWith("Stück") || it.startsWith("Packung")) "" else it }
            } else {
                // Fallback: Name aus der Adresse („milsani-frische-vollmilch-3-5-fett-000…“)
                name = url.substringAfterLast('/').substringBeforeLast('-')
                    .split('-').joinToString(" ").replaceFirstChar { it.uppercase() }
            }
            if (name.isBlank()) return null
            return HoferProdukt(name, marke, groesse, preis, grundpreis, kat, url, pos)
        }

        /** Kleinbuchstaben, Umlaute vereinheitlicht, nur Buchstaben/Ziffern. */
        internal fun normal(s: String): String =
            s.lowercase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Lädt Seiten von hofer.at: zuerst direkt (Chrome-Netz), und falls Hofers Bot-Schutz das
 * ablehnt, in einem unsichtbaren Browserfenster (WebView), das sich wie ein echter Besuch verhält.
 */
private class Seitenlader(private val ctx: Context) {
    private var webMode = false
    private var web: WebView? = null

    suspend fun html(url: String): String {
        if (!webMode) {
            val (code, text) = withContext(Dispatchers.IO) { Http.seite(url) }
            if (code in 200..299 && text.contains("/produkt")) return text
            if (code == 404) return ""
            webMode = true   // 403 o. ä.: ab jetzt über das Browserfenster
        }
        return webLaden(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webLaden(url: String): String = withContext(Dispatchers.Main) {
        val w = web ?: WebView(ctx).also {
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true
            web = it
        }
        try {
            withTimeout(45_000) {
                suspendCancellableCoroutine { cont ->
                    var fertig = false
                    fun pruefen(view: WebView, versuch: Int) {
                        view.evaluateJavascript("document.documentElement.outerHTML") { roh ->
                            if (fertig) return@evaluateJavascript
                            val html = try { JSONArray("[$roh]").getString(0) } catch (_: Exception) { "" }
                            val gut = html.contains("/produkt/") || (html.length > 30_000 && !html.contains("Access Denied"))
                            if (gut || versuch >= 6) {
                                fertig = true
                                if (cont.isActive) cont.resume(html)
                            } else {
                                view.postDelayed({ if (!fertig) pruefen(view, versuch + 1) }, 1500)
                            }
                        }
                    }
                    w.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String?) {
                            view.postDelayed({ if (!fertig) pruefen(view, 0) }, 1200)
                        }
                    }
                    w.loadUrl(url)
                    cont.invokeOnCancellation { fertig = true }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw RecognitionException("hofer.at hat nicht rechtzeitig geantwortet.")
        }
    }

    fun schliessen() {
        val w = web ?: return
        web = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { w.destroy() }
    }
}

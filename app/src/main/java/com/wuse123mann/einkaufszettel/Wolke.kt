package com.wuse123mann.einkaufszettel

import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Der Ordner war nicht erreichbar, oder die Freigabe hat die Anfrage abgelehnt. */
class WolkeFehler(nachricht: String, val status: Int = 0) : Exception(nachricht)

const val WOLKE_LISTE = "listen.json"
const val WOLKE_POSTEINGANG = "posteingang"

/** Ein Zettel aus dem Posteingang: eine Anweisung vom Spiegel. */
data class Zettel(val datei: String, val aktion: String, val name: String, val liste: String)

/**
 * Zugriff auf einen öffentlich geteilten Nextcloud-Ordner über WebDAV.
 *
 * Darin liegt die Liste, die sich die App mit dem Sprachassistenten am Spiegel teilt: „Kauz, merk
 * dir Milch“ an der Wohnungstür, und hier steht es im Supermarkt. Das Format des Ordners ist in
 * `docs/einkaufsordner.md` im Kauz-Repo beschrieben.
 *
 * Drei Eigenheiten der Nextcloud-Freigabe, die leicht zu übersehen sind:
 *
 *  * Der WebDAV-Pfad ist `/public.php/dav/files/<token>`, nicht der Freigabelink selbst. Eingesetzt
 *    werden darf trotzdem der Link, den Nextcloud beim Teilen anzeigt – [adresseAus] rechnet um.
 *  * Ohne Freigabepasswort braucht es keine Anmeldedaten; mit Passwort ist der Benutzername
 *    `anonymous`, nicht das Token.
 *  * **Alles außer GET braucht den Kopf `X-Requested-With: XMLHttpRequest`.** Ohne ihn antwortet der
 *    Server mit 401, obwohl Freigabe und Passwort stimmen.
 *
 * Verfügbar ab Nextcloud 29.
 */
class Wolke(link: String, private val passwort: String = "") {

    val basis: String = adresseAus(link)

    init {
        if (!basis.startsWith("http://") && !basis.startsWith("https://")) {
            throw WolkeFehler("Das ist keine brauchbare Freigabeadresse")
        }
    }

    // ---------------------------------------------------------------- Transport

    private fun adresse(pfad: String): String {
        val teile = pfad.trim('/').split('/').filter { it.isNotEmpty() }
        if (teile.isEmpty()) return basis
        return basis + "/" + teile.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun bauen(methode: String, pfad: String, rumpf: String?, kopf: Map<String, String> = emptyMap()): Request {
        val b = Request.Builder().url(adresse(pfad))
        // Ohne diesen Kopf lehnt Nextcloud jede schreibende Anfrage mit 401 ab
        b.header("X-Requested-With", "XMLHttpRequest")
        if (passwort.isNotBlank()) {
            b.header("Authorization", okhttp3.Credentials.basic("anonymous", passwort))
        }
        for ((k, v) in kopf) b.header(k, v)
        val body = rumpf?.toRequestBody(JSON_TYP)
        b.method(methode, body ?: if (methode == "GET" || methode == "HEAD") null else "".toRequestBody())
        return b.build()
    }

    private fun <T> ruf(methode: String, pfad: String, rumpf: String? = null,
                        kopf: Map<String, String> = emptyMap(), auswerten: (Response) -> T): T {
        val antwort = try {
            klient.newCall(bauen(methode, pfad, rumpf, kopf)).execute()
        } catch (e: Exception) {
            throw WolkeFehler("Die Cloud ist nicht erreichbar (${e.message ?: e.javaClass.simpleName})")
        }
        antwort.use {
            if (it.code == 401 || it.code == 403) {
                throw WolkeFehler(
                    "Die Freigabe nimmt die App nicht an. Stimmt das Freigabepasswort, " +
                        "und ist „Bearbeiten erlauben“ angehakt?", it.code
                )
            }
            return auswerten(it)
        }
    }

    // ---------------------------------------------------------------- Dateien

    /** Inhalt einer Datei, oder `null`, wenn es sie nicht gibt. */
    fun holen(pfad: String): String? = ruf("GET", pfad) {
        when {
            it.code == 404 -> null
            it.isSuccessful -> it.body?.string() ?: ""
            else -> throw WolkeFehler("$pfad lesen: HTTP ${it.code}", it.code)
        }
    }

    fun schreiben(pfad: String, inhalt: String) = ruf("PUT", pfad, inhalt) {
        if (!it.isSuccessful) throw WolkeFehler("$pfad schreiben: HTTP ${it.code}", it.code)
    }

    /** Wegräumen. Ist die Datei schon weg, ist das kein Fehler. */
    fun loeschen(pfad: String) = ruf("DELETE", pfad) {
        if (!it.isSuccessful && it.code != 404) {
            throw WolkeFehler("$pfad löschen: HTTP ${it.code}", it.code)
        }
    }

    fun ordnerAnlegen(pfad: String) = ruf("MKCOL", pfad) {
        // 405 = gibt es schon, 409 = Elternordner fehlt (hier nicht möglich, Ordner liegen flach)
        if (!it.isSuccessful && it.code != 405 && it.code != 409) {
            throw WolkeFehler("$pfad anlegen: HTTP ${it.code}", it.code)
        }
    }

    /** Dateinamen in einem Ordner. Fehlt der Ordner, ist die Liste leer. */
    fun auflisten(pfad: String): List<String> = ruf("PROPFIND", pfad, null, mapOf("Depth" to "1")) {
        if (it.code == 404) return@ruf emptyList()
        if (!it.isSuccessful) throw WolkeFehler("$pfad auflisten: HTTP ${it.code}", it.code)
        val xml = it.body?.string() ?: return@ruf emptyList()
        // Der Ordner selbst steht als erster Eintrag in der Antwort – er ist keine Datei darin.
        val eigen = pfad.trim('/').substringAfterLast('/')
        hrefNamen(xml).filter { name -> name != eigen && name.contains('.') }.distinct().sorted()
    }

    companion object {
        private val JSON_TYP = "application/json; charset=utf-8".toMediaType()

        private val klient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(RobustDns)
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Aus dem Freigabelink die WebDAV-Adresse machen; eine fertige bleibt unverändert.
         *
         * `https://cloud.example.org/s/AbC123` wird zu
         * `https://cloud.example.org/public.php/dav/files/AbC123`. Eine Instanz in einem
         * Unterverzeichnis (`https://example.org/nextcloud/s/AbC123`) behält ihren Pfad.
         */
        fun adresseAus(link: String): String {
            val sauber = link.trim().trimEnd('/')
            if (sauber.isEmpty()) return ""
            val treffer = FREIGABE.find(sauber) ?: return sauber
            val basis = treffer.groupValues[1].removeSuffix("/index.php")
            return "$basis/public.php/dav/files/${treffer.groupValues[2]}"
        }

        private val FREIGABE = Regex("^(https?://[^/]+(?:/[^/]+)*?)/(?:index\\.php/)?s/([A-Za-z0-9_-]+)$")

        /** Die Dateinamen aus einer WebDAV-Antwort (`multistatus` mit `href`-Elementen). */
        fun hrefNamen(xml: String): List<String> {
            val namen = ArrayList<String>()
            try {
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                p.setInput(StringReader(xml))
                var inHref = false
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> inHref = p.name == "href"
                        XmlPullParser.END_TAG -> inHref = false
                        XmlPullParser.TEXT -> if (inHref) {
                            val roh = p.text.trim().trimEnd('/')
                            if (roh.isNotEmpty()) {
                                val name = java.net.URLDecoder.decode(roh.substringAfterLast('/'), "UTF-8")
                                if (name.isNotEmpty()) namen.add(name)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                return emptyList()        // eine unverständliche Antwort heißt: nichts abzuholen
            }
            return namen
        }
    }
}

// -------------------------------------------------------------------- Posteingang

/**
 * Was ein Blick in den Posteingang ergeben hat.
 *
 * [erledigt] ist absichtlich mehr als die Dateinamen in [zettel]: Ein Zettel, der sich herunterladen
 * ließ, aber kein brauchbares JSON enthält, ist erledigt – er wird nie besser. Stünde er nicht hier
 * drin, bliebe er für immer liegen und würde bei jedem Abgleich erneut geholt. Ein Zettel, der sich
 * gar nicht laden ließ (kein Netz mitten im Durchgang), fehlt dagegen in beiden Listen und kommt
 * beim nächsten Mal wieder dran.
 */
data class Posteingang(val zettel: List<Zettel>, val erledigt: List<String>)

/**
 * Die Zettel aus dem Posteingang, älteste zuerst.
 *
 * Die Reihenfolge steckt im Dateinamen (Zeit mit Mikrosekunden), darum genügt sortieren. Sie ist
 * bedeutungstragend: „dazu Milch, weg Milch, dazu Milch“ endet mit Milch auf der Liste.
 *
 * @param ab nur Zettel, deren Dateiname größer ist – siehe [Store.wolkeStand].
 */
fun Wolke.zettelHolen(ab: String = ""): Posteingang {
    val dateien = auflisten(WOLKE_POSTEINGANG).filter { it.endsWith(".json") && it > ab }
    val zettel = ArrayList<Zettel>()
    val erledigt = ArrayList<String>()
    for (datei in dateien) {
        val roh = try {
            holen("$WOLKE_POSTEINGANG/$datei")
        } catch (_: WolkeFehler) {
            continue                      // nicht ladbar: beim nächsten Durchgang noch einmal
        }
        erledigt.add(datei)               // geladen – ob verständlich oder nicht
        if (roh == null) continue         // inzwischen weg (das Handy war schneller)
        val o = try {
            JSONObject(roh)
        } catch (_: Exception) {
            continue                      // unleserlich: wird nie besser, gilt als abgearbeitet
        }
        val aktion = o.optString("aktion", "dazu")
        val name = o.optString("name", "").trim()
        if (aktion == "leeren" || name.isNotEmpty()) {
            zettel.add(Zettel(datei, aktion, name, o.optString("liste", "").trim()))
        }
    }
    return Posteingang(zettel, erledigt)
}

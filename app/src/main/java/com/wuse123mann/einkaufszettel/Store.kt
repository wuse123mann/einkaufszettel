package com.wuse123mann.einkaufszettel

import android.content.Context
import java.io.File

const val STANDARD_MODELL = "gemini-flash-latest"

enum class Dienst(val titel: String) { GROQ("Groq"), GEMINI("Gemini"), OFFLINE("Offline") }

/** Speichert die Listen als JSON-Datei und die Einstellungen in SharedPreferences. */
class Store(context: Context) {
    private val altDatei = File(context.filesDir, "liste.json")
    private val file = File(context.filesDir, "listen.json")
    private val prefs = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    /** Lädt alle Listen; übernimmt beim ersten Start die frühere Einzel-Liste. Nie leer. */
    fun loadListen(): List<Liste> {
        val geladen = try {
            when {
                file.exists() -> listenFromJson(file.readText())
                altDatei.exists() -> listOf(Liste(name = "Einkaufsliste", items = itemsFromJson(altDatei.readText())))
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        return geladen.ifEmpty { listOf(Liste(name = "Einkaufsliste")) }
    }

    @Synchronized
    fun saveListen(listen: List<Liste>) {
        val tmp = File(file.parentFile, "listen.json.tmp")
        tmp.writeText(listenToJson(listen))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    var aktiveListe: String?
        get() = prefs.getString("aktiveListe", null)
        set(v) = prefs.edit().putString("aktiveListe", v).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) = prefs.edit().putString("apiKey", v).apply()

    var offlineOnly: Boolean
        get() = prefs.getBoolean("offlineOnly", false)
        set(v) = prefs.edit().putBoolean("offlineOnly", v).apply()

    var dienst: Dienst
        get() {
            val gespeichert = prefs.getString("dienst", null)
            if (gespeichert != null) return runCatching { Dienst.valueOf(gespeichert) }.getOrDefault(Dienst.GEMINI)
            return if (offlineOnly) Dienst.OFFLINE else Dienst.GEMINI
        }
        set(v) = prefs.edit().putString("dienst", v.name).apply()

    var groqKey: String
        get() = prefs.getString("groqKey", "") ?: ""
        set(v) = prefs.edit().putString("groqKey", v).apply()

    var groqModell: String
        get() = prefs.getString("groqModell", GROQ_STANDARD_MODELL)?.ifBlank { GROQ_STANDARD_MODELL } ?: GROQ_STANDARD_MODELL
        set(v) = prefs.edit().putString("groqModell", v).apply()

    var reihenfolge: List<String>
        get() = try {
            val a = org.json.JSONArray(prefs.getString("reihenfolge", null) ?: return HOFER_REIHENFOLGE)
            gueltigeReihenfolge((0 until a.length()).map { a.getString(it) })
        } catch (e: Exception) {
            HOFER_REIHENFOLGE
        }
        set(v) = prefs.edit().putString("reihenfolge", org.json.JSONArray(v).toString()).apply()

    /** Vom Nutzer festgelegte Abteilungen: Artikelname (klein) → Abteilung. */
    var preiseAn: Boolean
        get() = prefs.getBoolean("preiseAn", true)
        set(v) = prefs.edit().putBoolean("preiseAn", v).apply()

    /** Vom Nutzer gewählte Hofer-Produkte: Artikelname (klein) → Produkt-URL ("" = ohne Preis). */
    var preisWahl: Map<String, String>
        get() = try {
            val o = org.json.JSONObject(prefs.getString("preisWahl", null) ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(v) = prefs.edit().putString("preisWahl", org.json.JSONObject(v).toString()).apply()

    var gelernteKategorien: Map<String, String>
        get() = try {
            val o = org.json.JSONObject(prefs.getString("gelernteKategorien", null) ?: "{}")
            o.keys().asSequence().associateWith { migriereKat(o.getString(it), it) }
        } catch (e: Exception) {
            emptyMap()
        }
        set(v) = prefs.edit().putString("gelernteKategorien", org.json.JSONObject(v).toString()).apply()

    var modell: String
        get() = prefs.getString("modell", STANDARD_MODELL)?.ifBlank { STANDARD_MODELL } ?: STANDARD_MODELL
        set(v) = prefs.edit().putString("modell", v).apply()

    // ---------- geteilter Nextcloud-Ordner ----------

    /**
     * Der Freigabelink des geteilten Ordners, so wie Nextcloud ihn anzeigt.
     *
     * Beim Wechsel auf einen anderen Ordner wird [wolkeStand] zurückgesetzt: Dort liegen andere
     * Zettel mit anderen Namen, und ein Stand aus dem alten Ordner würde beliebig viele davon
     * überspringen.
     */
    var wolkeLink: String
        get() = prefs.getString("wolkeLink", "") ?: ""
        set(v) {
            val neu = v.trim()
            if (neu == wolkeLink) return
            prefs.edit().putString("wolkeLink", neu).remove("wolkeStand").apply()
        }

    var wolkePasswort: String
        get() = prefs.getString("wolkePasswort", "") ?: ""
        set(v) = prefs.edit().putString("wolkePasswort", v.trim()).apply()

    /**
     * Dateiname des letzten verarbeiteten Zettels aus dem Posteingang.
     *
     * Die Namen beginnen mit der Zeit, sind also aufsteigend vergleichbar. Alles, was nicht danach
     * kommt, ist erledigt – auch dann, wenn das Löschen im Ordner nicht geklappt hat.
     */
    var wolkeStand: String
        get() = prefs.getString("wolkeStand", "") ?: ""
        set(v) = prefs.edit().putString("wolkeStand", v).apply()

    val wolkeAn: Boolean get() = wolkeLink.isNotBlank()
}

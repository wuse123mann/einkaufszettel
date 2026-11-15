package com.wuse123mann.einkaufszettel

import android.content.Context
import java.io.File

const val STANDARD_MODELL = "gemini-flash-latest"

enum class Dienst(val titel: String) { GROQ("Groq"), GEMINI("Gemini"), OFFLINE("Offline") }

/** Speichert die Liste als JSON-Datei und die Einstellungen in SharedPreferences. */
class Store(context: Context) {
    private val file = File(context.filesDir, "liste.json")
    private val prefs = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    fun load(): List<Item> = try {
        if (file.exists()) itemsFromJson(file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(items: List<Item>) {
        val tmp = File(file.parentFile, "liste.json.tmp")
        tmp.writeText(itemsToJson(items))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

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

    var modell: String
        get() = prefs.getString("modell", STANDARD_MODELL)?.ifBlank { STANDARD_MODELL } ?: STANDARD_MODELL
        set(v) = prefs.edit().putString("modell", v).apply()
}

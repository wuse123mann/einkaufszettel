package com.wuse123mann.einkaufszettel

import android.content.Context
import java.io.File

const val STANDARD_MODELL = "gemini-flash-latest"

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

    var modell: String
        get() = prefs.getString("modell", STANDARD_MODELL)?.ifBlank { STANDARD_MODELL } ?: STANDARD_MODELL
        set(v) = prefs.edit().putString("modell", v).apply()
}

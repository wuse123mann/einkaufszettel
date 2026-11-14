package com.wuse123mann.einkaufszettel

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Item(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val menge: String = "",
    val kat: String = SONSTIGES,
    val done: Boolean = false,
    val unsicher: Boolean = false,
)

const val SONSTIGES = "Sonstiges"

val KATEGORIEN: List<Pair<String, Color>> = listOf(
    "Obst & Gemüse" to Color(0xFF4F9D4A),
    "Brot & Backwaren" to Color(0xFFC48A3F),
    "Milch & Käse" to Color(0xFF5B8FC9),
    "Fleisch & Fisch" to Color(0xFFC4584A),
    "Kühlregal" to Color(0xFF3FA3A3),
    "Tiefkühl" to Color(0xFF7AA7D9),
    "Vorrat & Konserven" to Color(0xFFA07850),
    "Getränke" to Color(0xFF6B6FC9),
    "Süßes & Snacks" to Color(0xFFD06A9A),
    "Drogerie & Haushalt" to Color(0xFF8A8F99),
    SONSTIGES to Color(0xFF9A9A8A),
)
val KAT_NAMEN: List<String> = KATEGORIEN.map { it.first }

fun katColor(kat: String): Color = KATEGORIEN.firstOrNull { it.first == kat }?.second ?: Color(0xFF9A9A8A)

fun validKat(kat: String?): String = if (kat != null && kat in KAT_NAMEN) kat else SONSTIGES

private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)

/** Einfache Abteilungs-Zuordnung für Handeinträge und die Offline-Erkennung. */
private val STICHWORTE: List<Pair<String, Regex>> = listOf(
    "Obst & Gemüse" to rx("apfel|äpfel|banane|birne|tomate|paradeiser|gurke|salat|zwiebel|knoblauch|karotte|möhre|kartoffel|erdäpfel|paprika|zitrone|orange|obst|gemüse|beere|traube|pilz|champignon|zucchini|brokkoli|lauch|kräuter|petersilie|avocado|spinat|kohl|mango|kiwi|ingwer"),
    "Brot & Backwaren" to rx("brot|semmel|brötchen|weckerl|toast|croissant|kipferl|baguette|gebäck|kuchen|striezel"),
    "Milch & Käse" to rx("milch|käse|joghurt|butter|topfen|quark|sahne|obers|rahm|\\bei(?:er)?\\b|mozzarella|feta|parmesan|skyr"),
    "Fleisch & Fisch" to rx("fleisch|hendl|huhn|hähnchen|faschiert|hack|wurst|schinken|speck|lachs|fisch|thunfisch|steak|schnitzel|salami|leberkäs|würstel"),
    "Tiefkühl" to rx("tiefkühl|\\btk\\b|eis\\b|pizza|pommes|fischstäbchen"),
    "Vorrat & Konserven" to rx("nudel|pasta|spaghetti|reis|mehl|zucker|salz|öl\\b|essig|dose|konserve|linsen|bohnen|müsli|haferflocken|kaffee|tee\\b|honig|marmelade|senf|ketchup|gewürz|sauce|soße|brühe|suppe|backpulver|hefe|germ"),
    "Getränke" to rx("wasser|saft|bier|wein|cola|limo|mineral|sprudel|getränk|eistee|sirup"),
    "Süßes & Snacks" to rx("schoko|chips|kekse|keks|süß|gummi|nüsse|riegel|snack|bonbon|manner|praline"),
    "Drogerie & Haushalt" to rx("klopapier|toilettenpapier|küchenrolle|spülmittel|waschmittel|shampoo|seife|zahnpasta|zahnbürste|deo|müllsack|müllbeutel|schwamm|tabs|taschentücher|batterie|windeln|duschgel|creme"),
)

fun guessKat(name: String): String = STICHWORTE.firstOrNull { it.second.containsMatchIn(name) }?.first ?: SONSTIGES

private const val EINHEIT = "(?:x|kg|dag|g|l|ml|stk\\.?|stück|pkg\\.?|pack|packung(?:en)?|dosen?|flaschen?|becher|bund|gläser|glas)"
private val MENGE_VORNE = rx("^(\\d+(?:[.,]\\d+)?\\s*$EINHEIT?)\\s+(.+)$")
private val MENGE_HINTEN = rx("^(.+?)\\s+(\\d+(?:[.,]\\d+)?\\s*$EINHEIT?)$")

/** Zerlegt „2 Milch“ / „Mehl 1 kg“ in Name und Menge. */
fun parseEntry(raw: String): Pair<String, String> {
    val t = raw.trim()
    MENGE_VORNE.find(t)?.let { return it.groupValues[2].trim() to it.groupValues[1].trim() }
    MENGE_HINTEN.find(t)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
    return t to ""
}

fun itemsToJson(items: List<Item>): String {
    val arr = JSONArray()
    for (i in items) {
        arr.put(
            JSONObject()
                .put("id", i.id).put("name", i.name).put("menge", i.menge)
                .put("kat", i.kat).put("done", i.done).put("unsicher", i.unsicher)
        )
    }
    return arr.toString()
}

fun itemsFromJson(text: String): List<Item> {
    val arr = JSONArray(text)
    val out = ArrayList<Item>(arr.length())
    for (n in 0 until arr.length()) {
        val o = arr.optJSONObject(n) ?: continue
        val name = o.optString("name").trim()
        if (name.isEmpty()) continue
        out += Item(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = name,
            menge = o.optString("menge"),
            kat = validKat(o.optString("kat")),
            done = o.optBoolean("done"),
            unsicher = o.optBoolean("unsicher"),
        )
    }
    return out
}

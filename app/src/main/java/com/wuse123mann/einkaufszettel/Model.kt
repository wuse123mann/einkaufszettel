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
    "Obst" to Color(0xFFE0773A),
    "Gemüse" to Color(0xFF4F9D4A),
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

/**
 * Typischer Laufweg in einer Hofer-Filiale (Österreich): Eingang mit Marktplatz (Obst & Gemüse),
 * Backbox, gleich danach Coolbox/Kühlregal, dann Trockensortiment, Getränke, Drogerie,
 * Tiefkühl kurz vor der Kassa. Die Filialen unterscheiden sich – in der App anpassbar.
 */
val HOFER_REIHENFOLGE: List<String> = listOf(
    "Obst",
    "Gemüse",
    "Brot & Backwaren",
    "Kühlregal",
    "Milch & Käse",
    "Fleisch & Fisch",
    "Vorrat & Konserven",
    "Süßes & Snacks",
    "Getränke",
    "Drogerie & Haushalt",
    "Tiefkühl",
    SONSTIGES,
)

/** Bereinigt eine gespeicherte Reihenfolge: unbekannte raus, fehlende hinten anhängen. */
fun gueltigeReihenfolge(liste: List<String>): List<String> =
    (liste.flatMap { if (it == ALT_OBST_GEMUESE) listOf("Obst", "Gemüse") else listOf(it) }
        .filter { it in KAT_NAMEN } + KAT_NAMEN).distinct()

/** Frühere gemeinsame Abteilung – wird beim Laden auf „Obst“ bzw. „Gemüse“ aufgeteilt. */
const val ALT_OBST_GEMUESE = "Obst & Gemüse"

fun obstOderGemuese(name: String): String =
    if (guessKat(name) == "Obst") "Obst" else "Gemüse"

/** Wandelt alte Abteilungen um und prüft die Gültigkeit. */
fun migriereKat(kat: String?, name: String): String =
    if (kat == ALT_OBST_GEMUESE) obstOderGemuese(name) else validKat(kat)

fun katColor(kat: String): Color = KATEGORIEN.firstOrNull { it.first == kat }?.second ?: Color(0xFF9A9A8A)

fun validKat(kat: String?): String = if (kat != null && kat in KAT_NAMEN) kat else SONSTIGES

private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)

/** Einfache Abteilungs-Zuordnung für Handeinträge und die Offline-Erkennung. */
private val STICHWORTE: List<Pair<String, Regex>> = listOf(
    "Obst" to rx("apfel|äpfel|banane|birne|zitrone|orange|obst|beere|traube|mango|kiwi|ananas|melone|marille|aprikose|pfirsich|nektarine|zwetsch|pflaume|kirsche|mandarine|clementine|limette|grapefruit|feige|dattel|granatapfel|rosine"),
    "Gemüse" to rx("tomate|paradeiser|gurke|salat|zwiebel|knoblauch|karotte|möhre|kartoffel|erdäpfel|paprika|gemüse|pilz|champignon|zucchini|brokkoli|lauch|kräuter|petersilie|schnittlauch|basilikum|avocado|spinat|kohl|ingwer|kürbis|aubergine|melanzani|sellerie|radieschen|rettich|fenchel|spargel|fisolen|mais\\b|rucola|vogerl|jungzwiebel|chili"),
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
            kat = migriereKat(o.optString("kat"), name),
            done = o.optBoolean("done"),
            unsicher = o.optBoolean("unsicher"),
        )
    }
    return out
}

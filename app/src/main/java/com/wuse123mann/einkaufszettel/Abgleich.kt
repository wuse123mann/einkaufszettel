package com.wuse123mann.einkaufszettel

/**
 * Die Rechenregeln für den Abgleich mit dem geteilten Nextcloud-Ordner – ohne Netz und ohne Datei.
 *
 * Der Ablauf drumherum (abholen, sichern, hochladen, wegräumen) steckt in
 * [MainViewModel.abgleichenImHintergrund]; hier steht nur, *was* ein Zettel bedeutet. Getrennt, weil
 * das Zusammenführen auf dem Hauptthread gegen den frischesten Stand laufen muss und die
 * Netzzugriffe eben nicht.
 *
 * **Ein Schreiber je Datei** ist die Regel, auf der das Ganze steht. `listen.json` gehört dieser
 * App; der Sprachassistent liest sie und fasst sie nie an. Umgekehrt gehört `posteingang/` ihm, und
 * diese App holt die Zettel dort ab und räumt sie weg. Die beiden Schreibvorgänge begegnen sich
 * nie, darum braucht es weder Sperre noch ETag – und keiner verliert still eine Änderung.
 *
 * **Die Reihenfolge der Zettel ist bedeutungstragend.** Sie steckt im Dateinamen, und „dazu Milch,
 * weg Milch, dazu Milch“ endet mit Milch auf der Liste. Wer erst alle Zusätze und dann alle
 * Streichungen anwendet, bekommt das Gegenteil heraus.
 */

/**
 * Die Zettel der Reihe nach auf die Listen anwenden. Reine Funktion.
 *
 * @param katFuer bestimmt die Abteilung für einen Artikelnamen (die gelernte, sonst die geratene).
 */
fun zettelAnwenden(
    listen: List<Liste>,
    zettel: List<Zettel>,
    katFuer: (String) -> String,
): List<Liste> {
    if (listen.isEmpty()) return listen
    var aktuell = listen
    for (z in zettel) {
        val ziel = aktuell.firstOrNull { z.liste.isNotEmpty() && it.name.trim().equals(z.liste, true) }
            ?: aktuell.first()
        val neueItems = when (z.aktion) {
            "leeren" -> emptyList()
            "weg" -> ziel.items.filterNot { !it.done && passtZu(it, z.name) }
            else -> dazuTun(ziel.items, z.name, katFuer)
        }
        aktuell = aktuell.map { if (it.id == ziel.id) it.copy(items = neueItems) else it }
    }
    return aktuell
}

/**
 * Einen Artikel ergänzen – und nichts tun, wenn er schon offen auf der Liste steht.
 *
 * Das „nichts tun“ ist nicht nur Bequemlichkeit: Bleibt ein Zettel liegen, weil das Löschen
 * scheiterte, darf er beim nächsten Mal keinen zweiten Eintrag erzeugen. Abgehaktes zählt dabei
 * nicht – was gekauft ist, darf wieder gebraucht werden.
 */
private fun dazuTun(items: List<Item>, roh: String, katFuer: (String) -> String): List<Item> {
    val (name, menge) = parseEntry(roh)
    val schoen = name.trim().replaceFirstChar { it.uppercase() }
    if (schoen.isEmpty()) return items
    if (items.any { !it.done && it.name.equals(schoen, ignoreCase = true) }) return items
    return items + Item(name = schoen, menge = menge, kat = katFuer(schoen))
}

/** Trifft dieser Zettel diesen Artikel? „2 Milch“ und „Milch“ sind dasselbe. */
private fun passtZu(item: Item, roh: String): Boolean {
    val (name, _) = parseEntry(roh)
    return item.name.equals(name.trim(), ignoreCase = true) ||
        item.name.equals(roh.trim(), ignoreCase = true)
}

/** Was in der Einblendung steht, wenn Zettel übernommen wurden. */
fun uebernahmeMeldung(anzahl: Int): String? = when (anzahl) {
    0 -> null
    1 -> "Ein Eintrag vom Spiegel übernommen"
    else -> "$anzahl Einträge vom Spiegel übernommen"
}

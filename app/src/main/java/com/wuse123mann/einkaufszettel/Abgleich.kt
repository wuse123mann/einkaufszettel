package com.wuse123mann.einkaufszettel

/**
 * Abgleich mit dem geteilten Nextcloud-Ordner.
 *
 * **Ein Schreiber je Datei** – das ist die Regel, auf der das Ganze steht. `listen.json` gehört
 * dieser App; der Sprachassistent liest sie und fasst sie nie an. Umgekehrt gehört `posteingang/`
 * ihm, und diese App holt die Zettel dort ab und räumt sie weg. Die beiden Schreibvorgänge begegnen
 * sich nie, darum braucht es weder Sperre noch ETag – und keiner verliert still eine Änderung.
 *
 * **Die Reihenfolge der Zettel ist bedeutungstragend.** Sie steckt im Dateinamen, und „dazu Milch,
 * weg Milch, dazu Milch“ endet mit Milch auf der Liste. Wer erst alle Zusätze und dann alle
 * Streichungen anwendet, bekommt das Gegenteil heraus.
 *
 * **Angewendet wird jeder Zettel genau einmal.** Dafür merkt sich die App in [Store.wolkeStand] den
 * Dateinamen des letzten verarbeiteten Zettels und überspringt alles, was nicht danach kommt. Sich
 * stattdessen darauf zu verlassen, dass das Löschen klappt, wäre riskant: Bleibt ein `leeren`-Zettel
 * liegen, weil das DELETE scheiterte, würde er beim nächsten Öffnen die Liste noch einmal leeren –
 * samt allem, was inzwischen dazugekommen ist.
 */

/** Was ein Abgleich gebracht hat. */
data class AbgleichErgebnis(
    val listen: List<Liste>,
    val uebernommen: Int,
    val meldung: String?,
    val fehler: Boolean = false,
)

/**
 * Die Zettel der Reihe nach auf die Listen anwenden. Reine Funktion – kein Netz, keine Datei.
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
 * scheiterte, darf er beim nächsten Mal keinen zweiten Eintrag erzeugen.
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

/**
 * Ein Durchgang: abholen, anwenden, sichern, hochladen, wegräumen – in genau dieser Reihenfolge.
 *
 * Erst lokal sichern, dann den Stand weiterstellen: Sonst wäre ein Zettel als verarbeitet vermerkt,
 * dessen Inhalt noch nirgends steht, und „Kauz, merk dir Milch“ wäre lautlos verloren. Hochladen
 * kommt vor dem Löschen, damit der Ordner auch dann den zusammengeführten Stand zeigt, wenn das
 * Wegräumen scheitert.
 *
 * @param speichernLokal muss die Listen sofort und blockierend auf das Gerät schreiben.
 */
fun abgleichen(
    store: Store,
    listen: List<Liste>,
    katFuer: (String) -> String,
    speichernLokal: (List<Liste>) -> Unit,
): AbgleichErgebnis {
    val link = store.wolkeLink
    if (link.isBlank()) return AbgleichErgebnis(listen, 0, null)

    val wolke = try {
        Wolke(link, store.wolkePasswort)
    } catch (e: WolkeFehler) {
        return AbgleichErgebnis(listen, 0, e.message, fehler = true)
    }

    val zettel = try {
        wolke.zettelHolen(store.wolkeStand)
    } catch (e: WolkeFehler) {
        return AbgleichErgebnis(listen, 0, e.message, fehler = true)
    }

    var neu = listen
    if (zettel.isNotEmpty()) {
        neu = zettelAnwenden(listen, zettel, katFuer)
        speichernLokal(neu)
        store.wolkeStand = zettel.maxOf { it.datei }
    }

    try {
        wolke.schreiben(WOLKE_LISTE, listenToJson(neu))
    } catch (e: WolkeFehler) {
        // Die Zettel sind übernommen und lokal gesichert; nur der Ordner hinkt bis zum nächsten Mal nach.
        return AbgleichErgebnis(neu, zettel.size, e.message, fehler = true)
    }

    for (z in zettel) {
        try {
            wolke.loeschen("$WOLKE_POSTEINGANG/${z.datei}")
        } catch (_: WolkeFehler) {
            // Bleibt liegen; der Stand verhindert, dass er noch einmal angewendet wird.
        }
    }

    return AbgleichErgebnis(neu, zettel.size, meldungFuer(zettel.size))
}

private fun meldungFuer(anzahl: Int): String? = when (anzahl) {
    0 -> null
    1 -> "Ein Eintrag vom Spiegel übernommen"
    else -> "$anzahl Einträge vom Spiegel übernommen"
}

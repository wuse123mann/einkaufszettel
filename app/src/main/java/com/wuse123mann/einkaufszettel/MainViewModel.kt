package com.wuse123mann.einkaufszettel

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class ReviewItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val menge: String,
    val kat: String,
    val unsicher: Boolean,
    val on: Boolean = true,
)

sealed interface ScanState {
    data object Idle : ScanState
    data class Loading(val anzahl: Int, val thumbs: List<ImageBitmap>, val gemini: Boolean) : ScanState
    data class Review(val items: List<ReviewItem>, val thumbs: List<ImageBitmap>, val offline: Boolean) : ScanState
    data class Fehler(val text: String) : ScanState
}

const val MAX_BILDER = 4

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val saveLock = Mutex()
    private val fotoDir = File(app.cacheDir, "fotos")

    var listen by mutableStateOf(store.loadListen())
        private set
    var aktiveId by mutableStateOf(store.aktiveListe?.takeIf { id -> listen.any { it.id == id } } ?: listen.first().id)
        private set

    /** Artikel der gerade geöffneten Liste. */
    var items by mutableStateOf(listen.first { it.id == aktiveId }.items)
        private set

    val aktiveName: String get() = listen.find { it.id == aktiveId }?.name ?: "Einkaufsliste"

    private fun listenSpeichern(neu: List<Liste>) {
        listen = neu
        viewModelScope.launch(Dispatchers.IO) {
            saveLock.withLock { store.saveListen(neu) }
        }
        hochladenGebuendelt(neu)
    }

    fun listeWaehlen(id: String) {
        val l = listen.find { it.id == id } ?: return
        aktiveId = id
        items = l.items
        store.aktiveListe = id
    }

    fun listeAnlegen(name: String) {
        val neu = Liste(name = name.trim().ifBlank { "Neue Liste" })
        listenSpeichern(listen + neu)
        listeWaehlen(neu.id)
        meldung = "Liste „${neu.name}“ angelegt"
    }

    fun listeUmbenennen(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        listenSpeichern(listen.map { if (it.id == id) it.copy(name = n) else it })
    }

    fun listeLoeschen(id: String) {
        val l = listen.find { it.id == id } ?: return
        val rest = listen.filterNot { it.id == id }
        if (rest.isEmpty()) {
            // letzte Liste: nicht entfernen, nur leeren
            val leer = l.copy(items = emptyList())
            listenSpeichern(listOf(leer))
            listeWaehlen(leer.id)
        } else {
            listenSpeichern(rest)
            if (id == aktiveId) listeWaehlen(rest.first().id)
        }
        meldung = "Liste „${l.name}“ gelöscht"
    }
    var scan by mutableStateOf<ScanState>(ScanState.Idle)
        private set
    var meldung by mutableStateOf<String?>(null)

    var apiKey by mutableStateOf(store.apiKey)
        private set
    /** Reihenfolge der Abteilungen = Laufweg im Geschäft. */
    var reihenfolge by mutableStateOf(store.reihenfolge)
        private set

    fun verschieben(kat: String, richtung: Int) {
        val l = reihenfolge.toMutableList()
        val i = l.indexOf(kat)
        val j = i + richtung
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        reihenfolge = l
        store.reihenfolge = l
    }

    private var gelernt: Map<String, String> = store.gelernteKategorien
    private fun schluessel(name: String) = name.trim().lowercase()

    /** Abteilung für einen Artikel: eigene Zuordnung des Nutzers hat Vorrang. */
    fun katFuer(name: String, vorschlag: String): String = gelernt[schluessel(name)] ?: vorschlag

    fun kategorieAendern(id: String, kat: String) {
        val item = items.find { it.id == id } ?: return
        if (item.kat == kat) return
        speichern(items.map { if (it.id == id) it.copy(kat = kat) else it })
        gelernt = gelernt + (schluessel(item.name) to kat)
        store.gelernteKategorien = gelernt
        meldung = "„${item.name}“ ist jetzt bei $kat – auch beim nächsten Mal"
    }

    fun reihenfolgeSetzen(l: List<String>) {
        reihenfolge = gueltigeReihenfolge(l)
        store.reihenfolge = reihenfolge
    }

    var dienst by mutableStateOf(store.dienst)
        private set
    var groqKey by mutableStateOf(store.groqKey)
        private set
    var groqModell by mutableStateOf(store.groqModell)
        private set
    var groqTestErgebnis by mutableStateOf<String?>(null)
        private set
    var groqTestLaeuft by mutableStateOf(false)
        private set
    var modell by mutableStateOf(store.modell)
        private set
    var testErgebnis by mutableStateOf<String?>(null)
        private set
    var testLaeuft by mutableStateOf(false)
        private set

    /** Aktueller Schritt der Erkennung (für den Fortschrittsbalken). */
    var phase by mutableStateOf<Phase?>(null)
        private set
    var scanStart by mutableStateOf(0L)
        private set
    private val main = Handler(Looper.getMainLooper())
    private fun melden(p: Phase) {
        main.post { if (scan is ScanState.Loading) phase = p }
    }

    private var scanJob: Job? = null

    init {
        // alte Kamerafotos aufräumen
        fotoDir.listFiles()?.forEach { it.delete() }
        Http.init(app)
        // Der Abgleich wird nicht hier angestoßen: Dieser Block läuft vor den Feldern darunter, und
        // wolkeLaeuft wäre noch nicht angelegt. Die Oberfläche stößt ihn beim Öffnen an – was auch
        // besser passt, weil das bei jedem Zurückkehren zur App gilt und nicht nur einmal.
    }

    // ---------- geteilter Nextcloud-Ordner ----------

    var wolkeLink by mutableStateOf(store.wolkeLink)
        private set
    var wolkePasswort by mutableStateOf(store.wolkePasswort)
        private set
    var wolkeStatus by mutableStateOf<String?>(null)
        private set
    var wolkeFehler by mutableStateOf(false)
        private set
    var wolkeLaeuft by mutableStateOf(false)
        private set

    val wolkeAn: Boolean get() = wolkeLink.isNotBlank()

    fun updateWolkeLink(v: String) {
        wolkeLink = v.trim()
        store.wolkeLink = wolkeLink
        wolkeStatus = null
        wolkeFehler = false
    }

    fun updateWolkePasswort(v: String) {
        wolkePasswort = v.trim()
        store.wolkePasswort = wolkePasswort
        wolkeStatus = null
        wolkeFehler = false
    }

    /**
     * Einen Abgleich anstoßen. Läuft im Hintergrund und blockiert nie die Liste – eine Einkaufsliste
     * muss im Supermarkt auch ohne Empfang benutzbar bleiben.
     *
     * Die Schrittfolge ist mit Bedacht so und nicht anders:
     *
     *  1. **Abholen** (Netz). Nur lesen, nichts verändern.
     *  2. **Zusammenführen** – auf dem Hauptthread, gegen [listen] von *jetzt*. Gegen einen
     *     Schnappschuss von vor dem Netzzugriff zu rechnen wäre falsch: Wer in der Zwischenzeit
     *     etwas abhakt, verlöre das wieder, weil das Ergebnis es überschreibt.
     *  3. **Lokal sichern**, und erst danach den Stand weiterstellen. Umgekehrt wäre ein Zettel als
     *     verarbeitet vermerkt, dessen Inhalt noch nirgends steht – „Kauz, merk dir Milch“ wäre
     *     lautlos verloren.
     *  4. **Hochladen**, dann **wegräumen**. In dieser Reihenfolge, damit der Ordner auch dann den
     *     zusammengeführten Stand zeigt, wenn das Löschen scheitert.
     *
     * @param vonHand steuert nur die Rückmeldung: Beim Öffnen der App soll nicht jedes Mal „Nichts
     *   Neues“ aufpoppen, auf Knopfdruck erwartet man aber eine Antwort.
     */
    fun abgleichenImHintergrund(vonHand: Boolean = true) {
        if (!store.wolkeAn || wolkeLaeuft) return
        wolkeLaeuft = true
        viewModelScope.launch {
            val wolke = try {
                Wolke(store.wolkeLink, store.wolkePasswort)
            } catch (e: WolkeFehler) {
                melde(vonHand, e.message, fehler = true)
                return@launch
            }

            val post = try {
                withContext(Dispatchers.IO) { wolke.zettelHolen(store.wolkeStand) }
            } catch (e: WolkeFehler) {
                melde(vonHand, e.message, fehler = true)
                return@launch
            }

            // Zusammenführen auf dem Hauptthread, gegen den frischesten Stand
            val neu = if (post.zettel.isEmpty()) listen
            else zettelAnwenden(listen, post.zettel, { name -> katFuer(name, guessKat(name)) })
            if (post.zettel.isNotEmpty()) uebernehmen(neu)

            val fehlertext = withContext(Dispatchers.IO) {
                if (post.erledigt.isNotEmpty()) {
                    saveLock.withLock { store.saveListen(neu) }
                    store.wolkeStand = post.erledigt.max()
                }
                try {
                    wolke.schreiben(WOLKE_LISTE, listenToJson(neu))
                } catch (e: WolkeFehler) {
                    // Die Zettel sind übernommen und gesichert; nur der Ordner hinkt nach.
                    return@withContext e.message
                }
                for (datei in post.erledigt) {
                    try {
                        wolke.loeschen("$WOLKE_POSTEINGANG/$datei")
                    } catch (_: WolkeFehler) {
                        // Bleibt liegen; der Stand verhindert, dass er noch einmal angewendet wird.
                    }
                }
                null
            }

            melde(vonHand, fehlertext ?: uebernahmeMeldung(post.zettel.size), fehler = fehlertext != null,
                  uebernommen = post.zettel.size)
        }
    }

    /**
     * Ergebnis eines Abgleichs anzeigen.
     *
     * Eingeblendet wird nur, was jemanden interessiert: übernommene Einträge immer, ein Fehler nur,
     * wenn man selbst auf den Knopf gedrückt hat. Sonst meldete sich die App bei jedem Öffnen ohne
     * Empfang. In den Einstellungen steht der Stand trotzdem.
     */
    private fun melde(vonHand: Boolean, text: String?, fehler: Boolean, uebernommen: Int = 0) {
        wolkeLaeuft = false
        wolkeFehler = fehler
        wolkeStatus = text ?: if (vonHand) "Nichts Neues vom Spiegel" else null
        if (text != null && (uebernommen > 0 || vonHand)) meldung = text
    }

    /** Vom Abgleich zusammengeführte Listen in die Anzeige holen, ohne sie erneut hochzuladen. */
    private fun uebernehmen(neu: List<Liste>) {
        listen = neu
        if (neu.none { it.id == aktiveId }) aktiveId = neu.first().id
        items = neu.firstOrNull { it.id == aktiveId }?.items ?: neu.first().items
    }

    /**
     * Den eigenen Stand in den Ordner legen – gebündelt.
     *
     * Jede einzelne Änderung hochzuladen hieße: zwanzig Häkchen im Supermarkt, zwanzig
     * Netzzugriffe. Darum wartet der Upload kurz und läuft nur, wenn eine Weile nichts mehr
     * passiert ist. Geht die App vorher zu, bleibt der lokale Stand erhalten und der nächste
     * Abgleich schiebt ihn nach.
     */
    private var hochladeJob: Job? = null

    private fun hochladenGebuendelt(neu: List<Liste>) {
        if (!store.wolkeAn) return
        hochladeJob?.cancel()
        hochladeJob = viewModelScope.launch {
            delay(1500)
            withContext(Dispatchers.IO) {
                try {
                    Wolke(store.wolkeLink, store.wolkePasswort).schreiben(WOLKE_LISTE, listenToJson(neu))
                } catch (_: Exception) {
                    // Kein Netz, falsches Passwort – der nächste Abgleich holt es nach.
                }
            }
        }
    }

    /** Reihenfolge der Online-Dienste: gewählter zuerst, der andere springt ein (falls Schlüssel vorhanden). */
    val onlineReihenfolge: List<Dienst>
        get() {
            val g = Dienst.GEMINI.takeIf { apiKey.isNotBlank() }
            val q = Dienst.GROQ.takeIf { groqKey.isNotBlank() }
            return when (dienst) {
                Dienst.GROQ -> listOfNotNull(q, g)
                Dienst.GEMINI -> listOfNotNull(g, q)
                Dienst.OFFLINE -> emptyList()
            }
        }

    /** Ist für den gewählten Dienst ein Schlüssel eingetragen? */
    val gewaehlterBereit: Boolean
        get() = when (dienst) {
            Dienst.GROQ -> groqKey.isNotBlank()
            Dienst.GEMINI -> apiKey.isNotBlank()
            Dienst.OFFLINE -> true
        }

    private fun speichern(neu: List<Item>) {
        items = neu
        listenSpeichern(listen.map { if (it.id == aktiveId) it.copy(items = neu) else it })
    }

    // ---------- Liste ----------
    fun toggle(id: String) = speichern(items.map {
        if (it.id == id) it.copy(done = !it.done, unsicher = if (!it.done) false else it.unsicher) else it
    })

    fun delete(item: Item): Int {
        val index = items.indexOfFirst { it.id == item.id }
        speichern(items.filterNot { it.id == item.id })
        return index
    }

    fun restore(item: Item, index: Int) {
        if (items.any { it.id == item.id }) return
        val l = items.toMutableList()
        l.add(index.coerceIn(0, l.size), item)
        speichern(l)
    }

    fun clearDone() = speichern(items.filterNot { it.done })
    fun clearAll() = speichern(emptyList())

    fun addManual(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val (name, menge) = parseEntry(t)
        val schoen = name.replaceFirstChar { it.uppercase() }
        if (items.any { !it.done && it.name.equals(schoen, ignoreCase = true) }) {
            meldung = "„$schoen“ steht schon auf der Liste"
            return
        }
        speichern(items + Item(name = schoen, menge = menge, kat = katFuer(schoen, guessKat(schoen))))
    }

    fun shareText(): String {
        val offen = items.filter { !it.done }
        val sb = StringBuilder(aktiveName).append("\n")
        for (kat in reihenfolge) {
            val g = offen.filter { it.kat == kat }
            if (g.isEmpty()) continue
            sb.append("\n").append(kat).append(":\n")
            g.forEach { sb.append("☐ ").append(it.name).append(if (it.menge.isNotBlank()) " (${it.menge})" else "").append("\n") }
        }
        return sb.toString().trim()
    }

    // ---------- Scannen ----------
    fun newPhotoUri(): Uri {
        fotoDir.mkdirs()
        val f = File(fotoDir, "foto_${System.currentTimeMillis()}.jpg")
        val ctx = getApplication<Application>()
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    fun startScan(uris: List<Uri>) {
        if (uris.isEmpty() || scan is ScanState.Loading || scan is ScanState.Review) return
        if (uris.size > MAX_BILDER) meldung = "Es werden nur die ersten $MAX_BILDER Bilder verwendet"
        val liste = uris.take(MAX_BILDER)
        val reihenfolge = onlineReihenfolge
        val gemini = reihenfolge.isNotEmpty()
        val gKey = apiKey.trim()
        val gModell = modell
        val qKey = groqKey.trim()
        val qModell = groqModell
        scanJob = viewModelScope.launch {
            scan = ScanState.Loading(liste.size, emptyList(), gemini)
            scanStart = System.currentTimeMillis()
            phase = Phase("Bild wird geladen …", 0f, 0.05f, 0f)
            var t0 = System.currentTimeMillis()
            try {
                val ctx = getApplication<Application>()
                val bitmaps = withContext(Dispatchers.IO) { liste.map { Bilder.load(ctx, it, 1600) } }
                val thumbs = withContext(Dispatchers.Default) { bitmaps.map { Bilder.thumb(it).asImageBitmap() } }
                scan = ScanState.Loading(liste.size, thumbs, gemini)
                t0 = System.currentTimeMillis()
                val erkannt = if (!gemini) OfflineLeser.read(bitmaps, ::melden) else {
                    var ergebnis: List<Erkannt>? = null
                    for ((i, d) in reihenfolge.withIndex()) {
                        try {
                            t0 = System.currentTimeMillis()
                            ergebnis = when (d) {
                                Dienst.GROQ -> Groq.read(bitmaps, qKey, qModell, ::melden)
                                else -> Gemini.read(bitmaps, gKey, gModell, ::melden)
                            }
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: KeineEintraegeException) {
                            throw e
                        } catch (e: Exception) {
                            if (e !is RecognitionException && e !is IOException) throw e
                            val naechster = reihenfolge.getOrNull(i + 1) ?: throw e
                            melden(Phase("${d.titel} klappt gerade nicht – versuche ${naechster.titel} …", 0.05f, 0.12f, 0f))
                        }
                    }
                    ergebnis ?: emptyList()
                }
                scan = if (erkannt.isEmpty()) {
                    ScanState.Fehler("Auf dem Bild wurde kein Text gefunden. Achte auf gutes Licht und fotografier den Zettel möglichst gerade von oben.")
                } else {
                    ScanState.Review(
                        erkannt.map { ReviewItem(name = it.name, menge = it.menge, kat = it.kat, unsicher = it.unsicher) },
                        thumbs,
                        offline = !gemini,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecognitionException) {
                scan = ScanState.Fehler(e.message ?: "Unbekannter Fehler")
            } catch (e: IOException) {
                scan = ScanState.Fehler(
                    if (gemini) Gemini.netzFehlerText(e, Gemini.sekunden(t0))
                    else "Das Bild konnte nicht geöffnet werden (${e.message})."
                )
            } catch (e: Exception) {
                scan = ScanState.Fehler("Beim Lesen ist etwas schiefgelaufen: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Nur für den Screenshot-Test: Scan-Ansicht mit Beispieldaten anzeigen. */
    @androidx.annotation.VisibleForTesting
    fun demoScan(zustand: ScanState, p: Phase? = null) {
        scan = zustand
        phase = p
        scanStart = System.currentTimeMillis() - 6_000
    }

    fun updateReview(id: String, change: (ReviewItem) -> ReviewItem) {
        val s = scan as? ScanState.Review ?: return
        scan = s.copy(items = s.items.map { if (it.id == id) change(it) else it })
    }

    fun confirmReview() {
        val s = scan as? ScanState.Review ?: return
        val vorhanden = items.filter { !it.done }.map { it.name.trim().lowercase() }.toMutableSet()
        val neu = ArrayList<Item>()
        var doppelt = 0
        for (r in s.items) {
            val name = r.name.trim()
            if (!r.on || name.isEmpty()) continue
            if (!vorhanden.add(name.lowercase())) { doppelt++; continue }
            neu += Item(name = name, menge = r.menge.trim(), kat = katFuer(name, r.kat), unsicher = r.unsicher)
        }
        speichern(items + neu)
        scan = ScanState.Idle
        meldung = "${neu.size} Artikel hinzugefügt" + if (doppelt > 0) " · $doppelt waren schon drauf" else ""
    }

    fun closeScan() {
        scanJob?.cancel()
        scanJob = null
        scan = ScanState.Idle
    }

    // ---------- Einstellungen ----------
    fun updateApiKey(v: String) { apiKey = v.trim(); store.apiKey = apiKey; testErgebnis = null }
    fun updateDienst(v: Dienst) { dienst = v; store.dienst = v }
    fun updateGroqKey(v: String) { groqKey = v.trim(); store.groqKey = groqKey; groqTestErgebnis = null }
    fun updateGroqModell(v: String) { groqModell = v.trim(); store.groqModell = groqModell; groqTestErgebnis = null }

    fun testGroq() {
        if (groqKey.isBlank()) { groqTestErgebnis = "Bitte zuerst einen Schlüssel eintragen."; return }
        groqTestLaeuft = true
        groqTestErgebnis = null
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            groqTestErgebnis = try {
                Groq.test(groqKey, groqModell)
            } catch (e: RecognitionException) {
                e.message
            } catch (e: IOException) {
                Gemini.netzFehlerText(e, Gemini.sekunden(t0))
            } catch (e: Exception) {
                "Fehler: ${e.message}"
            }
            groqTestLaeuft = false
        }
    }
    fun updateModell(v: String) { modell = v.trim(); store.modell = modell; testErgebnis = null }

    fun testKey() {
        if (apiKey.isBlank()) { testErgebnis = "Bitte zuerst einen Schlüssel eintragen."; return }
        testLaeuft = true
        testErgebnis = null
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            testErgebnis = try {
                Gemini.test(apiKey, modell.ifBlank { STANDARD_MODELL })
            } catch (e: RecognitionException) {
                e.message
            } catch (e: IOException) {
                Gemini.netzFehlerText(e, Gemini.sekunden(t0))
            } catch (e: Exception) {
                "Fehler: ${e.message}"
            }
            testLaeuft = false
        }
    }

    // ---------- Hofer-Preise ----------

    private val hofer = HoferKatalog(app)

    var preiseAn by mutableStateOf(store.preiseAn)
        private set
    var katalogAnzahl by mutableStateOf(hofer.produkte.size)
        private set
    var katalogStand by mutableStateOf(hofer.stand)
        private set
    var katalogLaeuft by mutableStateOf(false)
        private set
    var katalogText by mutableStateOf<String?>(null)
        private set
    var katalogFortschritt by mutableStateOf(0f)
        private set

    /** Wird bei jeder Änderung an Katalog oder Zuordnung erhöht, damit die Liste neu rechnet. */
    private var preisVersion by mutableStateOf(0)
    private var preisWahl: Map<String, String> = store.preisWahl
    private val preisCache = HashMap<String, Preisinfo?>()

    fun updatePreiseAn(v: Boolean) {
        preiseAn = v
        store.preiseAn = v
        preisVersion++
        if (v && hofer.produkte.isEmpty()) katalogLaden()
    }

    /** Beim Öffnen der App: Sortiment laden, wenn es fehlt oder älter als eine Woche ist. */
    fun preiseBeimStart() {
        if (!preiseAn || katalogLaeuft) return
        val alt = System.currentTimeMillis() - hofer.stand > 7L * 24 * 3600 * 1000
        if (hofer.produkte.isEmpty() || alt) katalogLaden()
    }

    fun katalogLaden() {
        if (katalogLaeuft) return
        katalogLaeuft = true
        katalogFortschritt = 0f
        katalogText = "Verbinde mit hofer.at …"
        viewModelScope.launch {
            try {
                hofer.aktualisieren { text, anteil -> main.post { katalogText = text; katalogFortschritt = anteil } }
                katalogAnzahl = hofer.produkte.size
                katalogStand = hofer.stand
                katalogText = null
                preisCache.clear()
                preisVersion++
                meldung = "Hofer-Preise aktualisiert: ${hofer.produkte.size} Produkte"
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecognitionException) {
                katalogText = e.message
            } catch (e: IOException) {
                katalogText = Gemini.netzFehlerText(e)
            } catch (e: Exception) {
                katalogText = "Hofer-Preise konnten nicht geladen werden: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                katalogLaeuft = false
            }
        }
    }

    /** Hofer-Preis für einen Artikel (eigene Wahl, sonst beste automatische Zuordnung). */
    fun preisFuer(item: Item): Preisinfo? {
        @Suppress("UNUSED_VARIABLE") val v = preisVersion   // Abhängigkeit für die Oberfläche
        if (!preiseAn || hofer.produkte.isEmpty()) return null
        val key = "${item.name}|${item.kat}|${item.menge}"
        if (preisCache.containsKey(key)) return preisCache[key]
        val anzahl = anzahlAus(item.menge)
        val wahl = preisWahl[schluessel(item.name)]
        val info = when {
            wahl == "" -> null
            wahl != null -> hofer.produktZuUrl(wahl)?.let { Preisinfo(it, anzahl, sicher = true) } ?: automatisch(item, anzahl)
            else -> automatisch(item, anzahl)
        }
        preisCache[key] = info
        return info
    }

    private fun automatisch(item: Item, anzahl: Int): Preisinfo? =
        hofer.kandidaten(suchbegriff(item.name), item.kat, 1).firstOrNull()?.let { Preisinfo(it, anzahl, sicher = false) }

    fun kandidaten(suche: String, kat: String?): List<HoferProdukt> =
        if (suche.isBlank()) emptyList() else hofer.kandidaten(suchbegriff(suche), kat, 40)

    fun preisWaehlen(item: Item, produkt: HoferProdukt?) {
        preisWahl = preisWahl + (schluessel(item.name) to (produkt?.url ?: ""))
        store.preisWahl = preisWahl
        preisCache.clear()
        preisVersion++
    }

    /** Zuordnung wieder der App überlassen. */
    fun preisAutomatisch(item: Item) {
        preisWahl = preisWahl - schluessel(item.name)
        store.preisWahl = preisWahl
        preisCache.clear()
        preisVersion++
    }

    fun hatEigeneWahl(item: Item): Boolean = preisWahl.containsKey(schluessel(item.name))

    fun summe(liste: List<Item>): Pair<Double, Int> {
        var s = 0.0
        var n = 0
        for (i in liste) preisFuer(i)?.let { s += it.summe; n++ }
        return s to n
    }
}

/** Häufige Listenwörter, für die eine allgemeinere Suche das übliche Produkt trifft. */
private val SUCH_VORZUG = mapOf(
    "milch" to "frische vollmilch",
    "joghurt" to "naturjoghurt",
    "zucker" to "kristallzucker",
    "mehl" to "weizenmehl glatt",
    "salz" to "speisesalz",
    "eier" to "eier",
    "butter" to "teebutter",
    "obers" to "schlagobers",
    "sahne" to "schlagobers",
    "topfen" to "topfen",
    "brot" to "mischbrot",
)

private fun suchbegriff(name: String): String {
    val n = name.trim().lowercase()
    return SUCH_VORZUG[n] ?: name
}

/** „2“, „2x“, „6 Stk“, „3 Dosen“ → Stückzahl; Gewichte und Volumen → 1. */
internal fun anzahlAus(menge: String): Int {
    val m = Regex("""^\s*(\d{1,2})\s*(x|×|stk\.?|stück|pkg\.?|pack|packung(en)?|dosen?|flaschen?|becher|gläser|glas|bund|kiste|kisten)?\s*$""", RegexOption.IGNORE_CASE)
        .find(menge) ?: return 1
    return m.groupValues[1].toIntOrNull()?.coerceIn(1, 50) ?: 1
}

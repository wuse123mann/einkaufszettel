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
}

package com.wuse123mann.einkaufszettel

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.asImageBitmap
import android.util.Log
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Erzeugt die Screenshots für die Dokumentation (docs/screenshots).
 * Füllt die App mit Beispieldaten, klickt sich durch die wichtigsten Ansichten und
 * fotografiert jeweils den ganzen Bildschirm.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext

    private var letztes: Bitmap? = null
    private val fehler = mutableListOf<String>()

    /**
     * Fotografiert den Bildschirm. Der Emulator rendert langsam (Software-Grafik), darum wird
     * gewartet, bis sich das Bild gegenüber dem letzten Foto geändert hat und stabil ist.
     */
    private fun foto(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
        Thread.sleep(700)
        var bild = instrumentation.uiAutomation.takeScreenshot() ?: return
        val ende = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < ende) {
            Thread.sleep(800)
            val neu = instrumentation.uiAutomation.takeScreenshot() ?: break
            val geaendert = letztes?.let { !neu.sameAs(it) } ?: true
            if (geaendert && neu.sameAs(bild)) { bild = neu; break }
            bild = neu
        }
        letztes = bild
        TestStorage().openOutputFile("$name.png").use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Führt einen Schritt aus; ein Fehler verhindert nicht die übrigen Screenshots. */
    private fun schritt(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("ScreenshotTest", "Schritt $name fehlgeschlagen", t)
            fehler += "$name: ${t.javaClass.simpleName}: ${t.message?.lines()?.take(3)?.joinToString(" | ")}"
            zurueck()
        }
    }

    private fun zurueck() {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        Thread.sleep(500)
    }

    private fun i(name: String, kat: String, menge: String = "", done: Boolean = false, unsicher: Boolean = false) =
        Item(name = name, menge = menge, kat = kat, done = done, unsicher = unsicher)

    private fun beispieldaten() {
        File(ctx.filesDir, "listen.json").delete()
        File(ctx.filesDir, "liste.json").delete()
        ctx.getSharedPreferences("einstellungen", 0).edit().clear().commit()

        val woche = Liste(
            name = "Wocheneinkauf",
            items = listOf(
                i("Äpfel", "Obst", "1 kg"),
                i("Bananen", "Obst", done = true),
                i("Paradeiser", "Gemüse", "500 g"),
                i("Gurke", "Gemüse"),
                i("Zwiebeln", "Gemüse", done = true),
                i("Semmeln", "Brot & Backwaren", "6 Stk"),
                i("Milch", "Milch & Käse", "2 l"),
                i("Gouda", "Milch & Käse", done = true),
                i("Sauerrahm", "Vorrat & Konserven"),
                i("Faschiertes", "Fleisch & Fisch", "500 g"),
                i("Spaghetti", "Vorrat & Konserven"),
                i("Passata", "Vorrat & Konserven", unsicher = true),
                i("Mineralwasser", "Getränke", "6x"),
                i("Klopapier", "Drogerie & Haushalt"),
                i("Pizza", "Tiefkühl", "2 Stk"),
            ),
        )
        val grill = Liste(
            name = "Grillfest",
            items = listOf(
                i("Würstel", "Fleisch & Fisch", "2 Pkg"),
                i("Grillkohle", "Sonstiges"),
                i("Ketchup", "Vorrat & Konserven", done = true),
                i("Bier", "Getränke", "1 Kiste"),
            ),
        )
        val drogerie = Liste(
            name = "Drogerie",
            items = listOf(
                i("Zahnpasta", "Drogerie & Haushalt", done = true),
                i("Duschgel", "Drogerie & Haushalt", done = true),
            ),
        )
        val store = Store(ctx)
        store.saveListen(listOf(woche, grill, drogerie))
        store.aktiveListe = woche.id
        store.dienst = Dienst.GROQ
        store.groqKey = "gsk_beispielschluessel_nur_fuer_screenshots"
        store.reihenfolge = HOFER_REIHENFOLGE
    }

    /** Ein „handgeschriebener“ Zettel als Vorschaubild. */
    private fun zettel(): Bitmap {
        val b = Bitmap.createBitmap(600, 760, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(250, 248, 238))
        val linie = Paint().apply { color = Color.rgb(196, 214, 232); strokeWidth = 2f }
        for (y in 120 until 760 step 80) c.drawLine(30f, y.toFloat(), 570f, y.toFloat(), linie)
        val stift = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 48, 120)
            textSize = 54f
            typeface = Typeface.create("cursive", Typeface.NORMAL)
        }
        listOf("2 l Milch", "10 Eier", "Sauerrahm", "1 kg Äpfel", "Germ", "Kaffee", "Klopapier")
            .forEachIndexed { n, t -> c.drawText(t, 52f, 108f + n * 80f, stift) }
        return b
    }

    @Test
    fun screenshots() {
        beispieldaten()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm: MainViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }

        // 1. Liste nach Laufweg sortiert
        schritt("liste") { foto("01_liste") }

        // 2. Listen-Auswahl
        schritt("listen") {
            compose.onNodeWithText("Wocheneinkauf").performClick()
            foto("02_listen")
            zurueck()
        }

        // 3. Abteilung ändern (langer Druck)
        schritt("abteilung") {
            compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Sauerrahm"))
            compose.waitForIdle()
            compose.onNodeWithText("Sauerrahm", useUnmergedTree = false).performTouchInput { longClick(center, 1500) }
            foto("03_abteilung")
            zurueck()
        }

        // 4. Erkennung läuft
        val bild = zettel().asImageBitmap()
        schritt("laden") {
            scenario.onActivity {
                vm.demoScan(ScanState.Loading(1, listOf(bild), true), Phase("KI liest die Handschrift …", 0.30f, 0.95f, 0.55f))
            }
            foto("04_laden")
            scenario.onActivity { vm.closeScan() }
        }

        // 5. Erkannte Einträge prüfen
        schritt("pruefen") {
            val erkannt = listOf(
                ReviewItem(name = "Milch", menge = "2 l", kat = "Milch & Käse", unsicher = false),
                ReviewItem(name = "Eier", menge = "10 Stk", kat = "Milch & Käse", unsicher = false),
                ReviewItem(name = "Sauerrahm", menge = "", kat = "Milch & Käse", unsicher = false),
                ReviewItem(name = "Äpfel", menge = "1 kg", kat = "Obst", unsicher = false),
                ReviewItem(name = "Germ", menge = "", kat = "Vorrat & Konserven", unsicher = true),
                ReviewItem(name = "Kaffee", menge = "", kat = "Vorrat & Konserven", unsicher = false),
                ReviewItem(name = "Klopapier", menge = "", kat = "Drogerie & Haushalt", unsicher = false, on = false),
            )
            scenario.onActivity { vm.demoScan(ScanState.Review(erkannt, listOf(bild), offline = false)) }
            foto("05_pruefen")
            scenario.onActivity { vm.closeScan() }
            compose.waitForIdle()
        }

        // 6.–8. Einstellungen
        schritt("einstellungen") {
            compose.onNodeWithContentDescription("Einstellungen").performClick()
            foto("06_einstellungen")
            compose.onNodeWithText("Google AI Studio öffnen").performScrollTo()
            foto("07_gemini")
            compose.onNodeWithText("Hofer-Vorlage").performScrollTo()
            foto("08_reihenfolge")
            zurueck()
        }

        // 9. Andere Liste
        schritt("grillfest") {
            scenario.onActivity { _ ->
                vm.listen.firstOrNull { it.name == "Grillfest" }?.let { vm.listeWaehlen(it.id) }
            }
            foto("09_grillfest")
        }

        scenario.close()
        // Fehler sichtbar machen (die Screenshots werden trotzdem übernommen)
        if (fehler.isNotEmpty()) throw AssertionError("Fehlgeschlagene Schritte:\n" + fehler.joinToString("\n"))
    }
}

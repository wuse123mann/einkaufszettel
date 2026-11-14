package com.wuse123mann.einkaufszettel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            EinkaufTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Bilder, die per „Teilen“ aus der Galerie o. ä. kommen. */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            vm.closeScan()
            vm.startScan(uris)
        }
    }
}

val Gruen = Color(0xFF2F7D55)
val Warnung = Color(0xFFC47A12)

private val Hell = lightColorScheme(
    primary = Gruen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F1E8),
    onPrimaryContainer = Color(0xFF0F3A24),
    secondaryContainer = Color(0xFFE3F1E8),
    onSecondaryContainer = Color(0xFF0F3A24),
    background = Color(0xFFF6F3EC),
    onBackground = Color(0xFF1F2A24),
    surface = Color(0xFFF6F3EC),
    onSurface = Color(0xFF1F2A24),
    surfaceContainerLowest = Color(0xFFFFFDF8),
    surfaceContainerLow = Color(0xFFFFFDF8),
    surfaceContainer = Color(0xFFF9F6EF),
    surfaceContainerHigh = Color(0xFFFFFDF8),
    onSurfaceVariant = Color(0xFF7A8079),
    outlineVariant = Color(0xFFE2DCCD),
    error = Color(0xFFB8412F),
    tertiaryContainer = Color(0xFFFBEFD9),
    onTertiaryContainer = Color(0xFF5A3A06),
)

private val Dunkel = darkColorScheme(
    primary = Color(0xFF5CC28B),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF1F3328),
    onPrimaryContainer = Color(0xFFCDEBD9),
    secondaryContainer = Color(0xFF1F3328),
    onSecondaryContainer = Color(0xFFCDEBD9),
    background = Color(0xFF141815),
    onBackground = Color(0xFFE9ECE7),
    surface = Color(0xFF141815),
    onSurface = Color(0xFFE9ECE7),
    surfaceContainerLowest = Color(0xFF1D221E),
    surfaceContainerLow = Color(0xFF1D221E),
    surfaceContainer = Color(0xFF1A1F1B),
    surfaceContainerHigh = Color(0xFF232924),
    onSurfaceVariant = Color(0xFF9AA29B),
    outlineVariant = Color(0xFF2C332E),
    error = Color(0xFFE26B57),
    tertiaryContainer = Color(0xFF3A2E18),
    onTertiaryContainer = Color(0xFFF4D9A8),
)

@Composable
fun EinkaufTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dunkel else Hell, content = content)
}

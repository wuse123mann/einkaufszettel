package com.wuse123mann.einkaufszettel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun App(vm: MainViewModel) {
    var einstellungen by rememberSaveable { mutableStateOf(false) }
    if (einstellungen) {
        BackHandler { einstellungen = false }
        SettingsScreen(vm, onBack = { einstellungen = false })
    } else {
        ListScreen(vm, onSettings = { einstellungen = true })
    }
    ScanSheet(vm, onSettings = { vm.closeScan(); einstellungen = true })
}

/* ======================= Liste ======================= */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var neuText by rememberSaveable { mutableStateOf("") }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingPhoto
        pendingPhoto = null
        if (ok && u != null) vm.startScan(listOf(u))
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_BILDER)) { uris ->
        vm.startScan(uris)
    }

    LaunchedEffect(vm.meldung) {
        val m = vm.meldung ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.meldung = null
    }

    val items = vm.items
    val offen = items.filter { !it.done }
    val erledigt = items.filter { it.done }
    val untertitel = when {
        items.isEmpty() -> "Noch nichts auf der Liste"
        offen.isEmpty() -> "Alles erledigt – ${items.size} Artikel im Wagen 🎉"
        else -> "${offen.size} von ${items.size} noch offen"
    }

    fun loeschen(item: Item) {
        val idx = vm.delete(item)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("„${item.name}“ gelöscht", actionLabel = "Rückgängig", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restore(item, idx)
        }
    }

    fun hinzufuegen() {
        vm.addManual(neuText)
        neuText = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Einkaufsliste", fontWeight = FontWeight.Bold)
                        Text(untertitel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (offen.isNotEmpty()) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, vm.shareText())
                            }
                            context.startActivity(Intent.createChooser(send, "Liste teilen"))
                        }) { Icon(Icons.Filled.Share, contentDescription = "Liste teilen") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .consumeWindowInsets(pad)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
        ) {
            item(key = "fortschritt") {
                LinearProgressIndicator(
                    progress = { if (items.isEmpty()) 0f else erledigt.size.toFloat() / items.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.height(14.dp))
            }
            item(key = "knoepfe") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val u = vm.newPhotoUri()
                            pendingPhoto = u
                            try {
                                takePicture.launch(u)
                            } catch (e: ActivityNotFoundException) {
                                vm.meldung = "Keine Kamera-App gefunden"
                            }
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Fotografieren")
                    }
                    OutlinedButton(
                        onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Bild hochladen")
                    }
                }
                Text(
                    "Bis zu $MAX_BILDER Bilder auf einmal – oder ein Bild aus der Galerie an die App teilen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 12.dp),
                )
            }
            if (!vm.geminiAktiv && !vm.offlineOnly) {
                item(key = "tipp") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
                            Text(
                                "Tipp: Mit einem kostenlosen Gemini-Schlüssel liest die App Handschrift viel zuverlässiger. Ohne Schlüssel wird offline am Handy gelesen – das klappt bei Handschrift oft nur mäßig.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.End)) { Text("Einrichten") }
                        }
                    }
                }
            }
            item(key = "eingabe") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = neuText,
                        onValueChange = { neuText = it },
                        placeholder = { Text("Artikel von Hand hinzufügen …") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { hinzufuegen() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                    FilledTonalIconButton(onClick = { hinzufuegen() }, enabled = neuText.isNotBlank(), modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = "Hinzufügen")
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (items.isEmpty()) {
                item(key = "leer") { LeerZustand() }
            } else {
                for ((kat, farbe) in KATEGORIEN) {
                    val g = offen.filter { it.kat == kat }
                    if (g.isEmpty()) continue
                    item(key = "kat_$kat") {
                        Gruppe(kat, farbe, g, onToggle = { vm.toggle(it.id) }, onDelete = { loeschen(it) })
                    }
                }
                if (erledigt.isNotEmpty()) {
                    item(key = "erledigt") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 4.dp),
                        ) {
                            GruppenTitel("Im Wagen (${erledigt.size})", null, Modifier.weight(1f))
                            TextButton(onClick = { vm.clearDone() }) { Text("Entfernen") }
                        }
                        Karte {
                            erledigt.forEachIndexed { i, it ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                ItemRow(it, onToggle = { vm.toggle(it.id) }, onDelete = { loeschen(it) })
                            }
                        }
                    }
                }
                item(key = "alles_loeschen") {
                    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = { confirmClear = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Ganze Liste löschen") }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Ganze Liste löschen?") },
            text = { Text("Alle ${items.size} Artikel werden entfernt.") },
            confirmButton = {
                TextButton(
                    onClick = { vm.clearAll(); confirmClear = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun GruppenTitel(titel: String, farbe: Color?, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (farbe != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(farbe))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            titel.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Karte(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
private fun Gruppe(titel: String, farbe: Color, items: List<Item>, onToggle: (Item) -> Unit, onDelete: (Item) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        GruppenTitel(titel, farbe, Modifier.padding(start = 4.dp, bottom = 6.dp))
        Karte {
            items.forEachIndexed { i, it ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ItemRow(it, onToggle = { onToggle(it) }, onDelete = { onDelete(it) })
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item, onToggle: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (item.done) cs.primary else Color.Transparent)
                .border(2.dp, if (item.done) cs.primary else cs.outlineVariant, CircleShape),
        ) {
            if (item.done) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color = if (item.done) cs.onSurfaceVariant else cs.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.unsicher && !item.done) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(20.dp), color = cs.tertiaryContainer) {
                        Text(
                            "?",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = cs.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            if (item.menge.isNotBlank()) {
                Text(item.menge, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "${item.name} löschen", tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun LeerZustand() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 12.dp, end = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(Icons.Outlined.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Fotografiere deinen Einkaufszettel.", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Die Handschrift wird gelesen, nach Abteilungen sortiert und als Liste zum Abhaken angelegt.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/* ======================= Scan ======================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(vm: MainViewModel, onSettings: () -> Unit) {
    val s = vm.scan
    if (s is ScanState.Idle) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { vm.closeScan() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
        ) {
            when (s) {
                is ScanState.Loading -> LadeInhalt(s, onCancel = { vm.closeScan() })
                is ScanState.Review -> ReviewInhalt(s, vm, onSettings)
                is ScanState.Fehler -> FehlerInhalt(s, onClose = { vm.closeScan() }, onSettings = onSettings)
                ScanState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Vorschau(thumbs: List<ImageBitmap>, hoehe: Int) {
    if (thumbs.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(thumbs) { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = "Foto der Liste",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(hoehe.dp)
                    .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

@Composable
private fun ColumnScope.LadeInhalt(s: ScanState.Loading, onCancel: () -> Unit) {
    Text(
        if (s.anzahl > 1) "${s.anzahl} Bilder werden gelesen" else "Zettel wird gelesen",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(12.dp))
    Vorschau(s.thumbs, 160)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            if (s.gemini) "Handschrift wird entziffert …" else "Offline-Erkennung läuft …",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
}

@Composable
private fun ColumnScope.ReviewInhalt(s: ScanState.Review, vm: MainViewModel, onSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val aktiv = s.items.count { it.on && it.name.isNotBlank() }
    val unsicher = s.items.count { it.unsicher && it.on }

    Text("${s.items.size} Artikel erkannt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        "Tippe in ein Feld, um etwas zu korrigieren. Häkchen weg = nicht übernehmen.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )
    if (s.offline) {
        Surface(shape = RoundedCornerShape(12.dp), color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                Text(
                    "Offline erkannt – bei Handschrift oft ungenau. Mit einem Gratis-Gemini-Schlüssel wird's viel besser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSettings) { Text("Einrichten") }
            }
        }
    }
    if (unsicher > 0) {
        Text(
            if (unsicher == 1) "1 Eintrag war schwer lesbar (gelb markiert) – kurz prüfen."
            else "$unsicher Einträge waren schwer lesbar (gelb markiert) – kurz prüfen.",
            style = MaterialTheme.typography.bodySmall,
            color = Warnung,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    Vorschau(s.thumbs, 90)
    Spacer(Modifier.height(6.dp))
    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
        items(s.items, key = { it.id }) { r -> ReviewZeile(r, vm) }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.closeScan() }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Verwerfen") }
        Button(onClick = { vm.confirmReview() }, enabled = aktiv > 0, modifier = Modifier.weight(1f).height(50.dp)) {
            Text(if (aktiv > 0) "$aktiv übernehmen" else "Nichts ausgewählt")
        }
    }
}

@Composable
private fun ReviewZeile(r: ReviewItem, vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val hintergrund = if (r.unsicher) cs.tertiaryContainer else Color.Transparent
    val farben = OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = hintergrund,
        focusedContainerColor = hintergrund,
        unfocusedBorderColor = if (r.unsicher) Warnung else cs.outlineVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).alpha(if (r.on) 1f else 0.45f),
    ) {
        Checkbox(checked = r.on, onCheckedChange = { c -> vm.updateReview(r.id) { it.copy(on = c) } })
        OutlinedTextField(
            value = r.name,
            onValueChange = { v -> vm.updateReview(r.id) { it.copy(name = v, unsicher = false) } },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = farben,
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = r.menge,
            onValueChange = { v -> vm.updateReview(r.id) { it.copy(menge = v) } },
            singleLine = true,
            placeholder = { Text("Menge", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = cs.outlineVariant),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.width(96.dp),
        )
    }
}

@Composable
private fun ColumnScope.FehlerInhalt(s: ScanState.Fehler, onClose: () -> Unit, onSettings: () -> Unit) {
    Text("Das hat nicht geklappt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
    ) {
        Text(s.text, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(14.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("Einstellungen") }
        Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("OK") }
    }
}

/* ======================= Einstellungen ======================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    var zeigen by remember { mutableStateOf(false) }
    var keyText by rememberSaveable { mutableStateOf(vm.apiKey) }
    var modellText by rememberSaveable { mutableStateOf(vm.modell) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
        containerColor = cs.background,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Handschrifterkennung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Am zuverlässigsten liest die App Handschrift mit Googles KI Gemini. Der Gratis-Tarif reicht für Einkaufszettel locker und braucht keine Kreditkarte.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Karte {
                Column(Modifier.padding(14.dp)) {
                    Schritt("1", "Google AI Studio öffnen und mit deinem Google-Konto anmelden.")
                    Schritt("2", "„Create API key“ bzw. „API-Schlüssel erstellen“ antippen und den Schlüssel kopieren.")
                    Schritt("3", "Unten einfügen – fertig.")
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }) {
                        Text("Google AI Studio öffnen")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it; vm.updateApiKey(it) },
                label = { Text("Gemini-API-Schlüssel") },
                singleLine = true,
                visualTransformation = if (zeigen) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { zeigen = !zeigen }) {
                        Icon(
                            if (zeigen) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (zeigen) "Verbergen" else "Anzeigen",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.testKey() }, enabled = !vm.testLaeuft && keyText.isNotBlank()) {
                    if (vm.testLaeuft) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                    } else {
                        Text("Schlüssel testen")
                    }
                }
                Spacer(Modifier.width(12.dp))
                vm.testErgebnis?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nur offline erkennen", fontWeight = FontWeight.Medium)
                    Text(
                        "Ohne Internet direkt am Handy. Bei Druckschrift gut, bei Handschrift oft ungenau.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = vm.offlineOnly, onCheckedChange = { vm.updateOfflineOnly(it) })
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Aktuell: " + if (vm.geminiAktiv) "Gemini (online)" else "Offline-Erkennung am Handy",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
            )

            Spacer(Modifier.height(22.dp))
            Text("Für Fortgeschrittene", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = modellText,
                onValueChange = { modellText = it; vm.updateModell(it) },
                label = { Text("Gemini-Modell") },
                supportingText = { Text("Standard: $STANDARD_MODELL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))
            Text(
                "Datenschutz: Zur Erkennung wird das Foto an Google geschickt. Im Gratis-Tarif darf Google die Daten zur Verbesserung seiner Dienste verwenden. Deine Liste selbst bleibt nur auf deinem Handy.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Schritt(nummer: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(nummer, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

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
    var katItem by remember { mutableStateOf<Item?>(null) }
    var preisItem by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(Unit) { vm.preiseBeimStart() }
    var listenAuswahl by remember { mutableStateOf(false) }
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
    } + vm.summe(offen).let { (betrag, n) -> if (n > 0) " · ca. ${euro(betrag)}" else "" }

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { listenAuswahl = true }
                            .padding(end = 6.dp),
                    ) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(vm.aktiveName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(untertitel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Liste wechseln")
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
                    // Nur sichtbar, wenn ein geteilter Ordner eingetragen ist: Im Supermarkt will
                    // man nachsehen können, ob noch etwas vom Spiegel dazugekommen ist.
                    if (vm.wolkeAn) {
                        IconButton(
                            onClick = { vm.abgleichenImHintergrund() },
                            enabled = !vm.wolkeLaeuft,
                        ) { Icon(Icons.Filled.Refresh, contentDescription = "Mit dem Spiegel abgleichen") }
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
            if (!vm.gewaehlterBereit) {
                item(key = "tipp") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
                            Text(
                                "Tipp: Mit einem kostenlosen Groq- oder Gemini-Schlüssel liest die App Handschrift viel zuverlässiger. Ohne Schlüssel wird offline am Handy gelesen – das klappt bei Handschrift oft nur mäßig.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.End)) { Text("Einrichten") }
                        }
                    }
                }
            }
            if (vm.preiseAn && (vm.katalogLaeuft || vm.katalogAnzahl == 0)) {
                item(key = "hofer") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = if (vm.katalogLaeuft) 12.dp else 4.dp)) {
                            Text(
                                if (vm.katalogLaeuft) "Hofer-Preise werden geladen …" else "Hofer-Preise sind noch nicht geladen.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            vm.katalogText?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            if (vm.katalogLaeuft) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { vm.katalogFortschritt }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                            } else {
                                TextButton(onClick = { vm.katalogLaden() }, modifier = Modifier.align(Alignment.End)) { Text("Jetzt laden") }
                            }
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
                for (kat in vm.reihenfolge) {
                    val farbe = katColor(kat)
                    val g = offen.filter { it.kat == kat }
                    if (g.isEmpty()) continue
                    item(key = "kat_$kat") {
                        Gruppe(kat, farbe, g, onToggle = { vm.toggle(it.id) }, onDelete = { loeschen(it) }, onLongPress = { katItem = it }, preis = { vm.preisFuer(it) }, onPreis = { preisItem = it })
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
                                ItemRow(it, onToggle = { vm.toggle(it.id) }, onDelete = { loeschen(it) }, onLongPress = { katItem = it }, preis = vm.preisFuer(it), onPreis = { preisItem = it })
                            }
                        }
                    }
                }
                item(key = "alles_loeschen") {
                    val (offenSumme, offenN) = vm.summe(offen)
                    val (wagenSumme, wagenN) = vm.summe(erledigt)
                    if (offenN + wagenN > 0) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                SummenZeile("Noch zu kaufen", offenSumme, fett = true)
                                if (wagenN > 0) SummenZeile("Im Wagen", wagenSumme)
                                SummenZeile("Gesamt", offenSumme + wagenSumme)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Hofer-Preise von hofer.at, ohne Gewähr – ${offenN + wagenN} von ${items.size} Artikeln mit Preis. Preis antippen, um das Produkt zu ändern.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        "Tipp: Artikel lange drücken, um ihn in eine andere Abteilung zu schieben.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = { confirmClear = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Ganze Liste löschen") }
                    }
                }
            }
        }
    }

    if (listenAuswahl) {
        ListenSheet(vm, onClose = { listenAuswahl = false })
    }

    preisItem?.let { item ->
        PreisSheet(vm, item, onClose = { preisItem = null })
    }

    katItem?.let { item ->
        AlertDialog(
            onDismissRequest = { katItem = null },
            title = { Text("Abteilung für „${item.name}“") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (kat in vm.reihenfolge) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vm.kategorieAendern(item.id, kat); katItem = null }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(selected = item.kat == kat, onClick = { vm.kategorieAendern(item.id, kat); katItem = null })
                            Box(Modifier.size(10.dp).clip(CircleShape).background(katColor(kat)))
                            Spacer(Modifier.width(10.dp))
                            Text(kat)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Die App merkt sich das und sortiert „${item.name}“ künftig automatisch hierher.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { katItem = null }) { Text("Abbrechen") } },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenSheet(vm: MainViewModel, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var nameDialog by remember { mutableStateOf<Liste?>(null) }   // id leer = neue Liste
    var loeschen by remember { mutableStateOf<Liste?>(null) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            Text("Meine Listen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(vm.listen, key = { it.id }) { l ->
                    val offen = l.items.count { !it.done }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.listeWaehlen(l.id); onClose() }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = l.id == vm.aktiveId, onClick = { vm.listeWaehlen(l.id); onClose() })
                        Column(Modifier.weight(1f)) {
                            Text(l.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    l.items.isEmpty() -> "leer"
                                    offen == 0 -> "alles erledigt"
                                    else -> "$offen offen"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { nameDialog = l }) {
                            Icon(Icons.Filled.Edit, contentDescription = "${l.name} umbenennen", tint = cs.onSurfaceVariant)
                        }
                        IconButton(onClick = { loeschen = l }) {
                            Icon(Icons.Filled.Delete, contentDescription = "${l.name} löschen", tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { nameDialog = Liste(id = "", name = "") }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Neue Liste")
            }
        }
    }

    nameDialog?.let { l ->
        val neu = l.id.isEmpty()
        var text by remember(l.id) { mutableStateOf(l.name) }
        val fertig = {
            if (neu) { vm.listeAnlegen(text); onClose() } else vm.listeUmbenennen(l.id, text)
            nameDialog = null
        }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (neu) "Neue Liste" else "Liste umbenennen") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("z. B. Wochenende, Grillfest, Drogerie") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) fertig() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { fertig() }, enabled = text.isNotBlank()) { Text(if (neu) "Anlegen" else "Speichern") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Abbrechen") } },
        )
    }

    loeschen?.let { l ->
        AlertDialog(
            onDismissRequest = { loeschen = null },
            title = { Text("„${l.name}“ löschen?") },
            text = {
                Text(
                    if (vm.listen.size <= 1) "Das ist deine einzige Liste – sie wird geleert."
                    else "Die Liste mit ${l.items.size} Artikeln wird entfernt."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.listeLoeschen(l.id); loeschen = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                ) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { loeschen = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun SummenZeile(text: String, betrag: Double, fett: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(text, modifier = Modifier.weight(1f), fontWeight = if (fett) FontWeight.SemiBold else FontWeight.Normal)
        Text("ca. ${euro(betrag)}", fontWeight = if (fett) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Hofer-Produkt für einen Artikel wählen; die Wahl wird für diesen Artikelnamen gemerkt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreisSheet(vm: MainViewModel, item: Item, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var suche by remember(item.id) { mutableStateOf(item.name) }
    val aktuell = vm.preisFuer(item)
    val treffer = remember(suche, vm.katalogAnzahl) { vm.kandidaten(suche, item.kat) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            Text("Hofer-Produkt für „${item.name}“", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Die Wahl gilt für diesen Artikel – auch auf künftigen Listen.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (vm.katalogAnzahl == 0) {
                Text(
                    if (vm.katalogLaeuft) "Hofer-Preise werden gerade geladen …" else "Hofer-Preise sind noch nicht geladen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!vm.katalogLaeuft) {
                    Button(onClick = { vm.katalogLaden() }, modifier = Modifier.padding(top = 8.dp)) { Text("Jetzt laden") }
                }
            } else {
                OutlinedTextField(
                    value = suche,
                    onValueChange = { suche = it },
                    label = { Text("Bei Hofer suchen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(treffer, key = { it.url }) { p ->
                        val gewaehlt = aktuell?.produkt?.url == p.url
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vm.preisWaehlen(item, p); onClose() }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = gewaehlt, onClick = { vm.preisWaehlen(item, p); onClose() })
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(p.marke, p.groesse, p.grundpreis).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(euro(p.preis), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (treffer.isEmpty()) {
                        item {
                            Text(
                                "Nichts gefunden. Probier ein anderes Wort, z. B. „Vollmilch“ statt „Milch“.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.preisWaehlen(item, null); onClose() }, modifier = Modifier.weight(1f)) { Text("Ohne Preis") }
                    if (vm.hatEigeneWahl(item)) {
                        OutlinedButton(onClick = { vm.preisAutomatisch(item); onClose() }, modifier = Modifier.weight(1f)) { Text("Automatisch") }
                    }
                }
            }
        }
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
private fun Gruppe(
    titel: String,
    farbe: Color,
    items: List<Item>,
    onToggle: (Item) -> Unit,
    onDelete: (Item) -> Unit,
    onLongPress: (Item) -> Unit,
    preis: (Item) -> Preisinfo?,
    onPreis: (Item) -> Unit,
) {
    Column(Modifier.padding(top = 14.dp)) {
        GruppenTitel(titel, farbe, Modifier.padding(start = 4.dp, bottom = 6.dp))
        Karte {
            items.forEachIndexed { i, it ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ItemRow(it, onToggle = { onToggle(it) }, onDelete = { onDelete(it) }, onLongPress = { onLongPress(it) }, preis = preis(it), onPreis = { onPreis(it) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: Item,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit = {},
    preis: Preisinfo? = null,
    onPreis: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress, onLongClickLabel = "Abteilung ändern")
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
            if (preis != null && !item.done) {
                Text(
                    (if (preis.sicher) "" else "≈ ") + preis.produkt.anzeige +
                        (if (preis.produkt.groesse.isNotBlank()) " · ${preis.produkt.groesse}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (preis != null) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPreis)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    euro(preis.summe),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.done) cs.onSurfaceVariant else cs.primary,
                )
                if (preis.anzahl > 1) {
                    Text("${preis.anzahl} × ${euro(preis.produkt.preis)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        } else if (!item.done) {
            TextButton(onClick = onPreis, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("€ ?", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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
                is ScanState.Loading -> LadeInhalt(s, vm.phase, vm.scanStart, onCancel = { vm.closeScan() })
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
private fun ColumnScope.LadeInhalt(s: ScanState.Loading, phase: Phase?, start: Long, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            jetzt = System.currentTimeMillis()
            delay(100)
        }
    }
    // Zielwert: genauer Anteil, falls bekannt – sonst langsam mit der Zeit Richtung Phasenende
    val ziel = phase?.let { p ->
        val a = p.anteil ?: (1f - exp(-((jetzt - p.seit) / 1000f) / p.tau))
        p.von + (p.bis - p.von) * a.coerceIn(0f, 1f)
    } ?: 0f
    val hoechster = remember { FloatArray(1) }   // Balken läuft nie rückwärts
    hoechster[0] = maxOf(hoechster[0], ziel)
    val angezeigt by animateFloatAsState(hoechster[0], animationSpec = tween(300), label = "fortschritt")
    val sekunden = ((jetzt - start).coerceAtLeast(0) / 1000)

    Text(
        if (s.anzahl > 1) "${s.anzahl} Bilder werden gelesen" else "Zettel wird gelesen",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(12.dp))
    Vorschau(s.thumbs, 160)
    Spacer(Modifier.height(22.dp))
    LinearProgressIndicator(
        progress = { angezeigt },
        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(10.dp)),
        trackColor = cs.outlineVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            phase?.text ?: "Wird vorbereitet …",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${(angezeigt * 100).toInt()} % · $sekunden s",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (s.gemini) "Die Dauer hängt davon ab, wie ausgelastet die Server gerade sind."
        else "Die Offline-Erkennung läuft direkt am Handy.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )
    Spacer(Modifier.height(18.dp))
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
                    "Offline erkannt – bei Handschrift oft ungenau. Mit einem Gratis-Schlüssel (Groq oder Gemini) wird's viel besser.",
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
    var groqKeyText by rememberSaveable { mutableStateOf(vm.groqKey) }
    var groqModellText by rememberSaveable { mutableStateOf(vm.groqModell) }
    var keyText by rememberSaveable { mutableStateOf(vm.apiKey) }
    var modellText by rememberSaveable { mutableStateOf(vm.modell) }
    var wolkeLinkText by rememberSaveable { mutableStateOf(vm.wolkeLink) }
    var wolkePasswortText by rememberSaveable { mutableStateOf(vm.wolkePasswort) }

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
            Spacer(Modifier.height(4.dp))
            Text(
                "Wähle, wer deine Zettel liest. Beide Online-Dienste sind im Gratis-Tarif ohne Kreditkarte nutzbar.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Karte {
                DienstOption("Groq", "Sehr schnell (meist wenige Sekunden).", vm.dienst == Dienst.GROQ) { vm.updateDienst(Dienst.GROQ) }
                HorizontalDivider(color = cs.outlineVariant)
                DienstOption("Gemini", "Sehr genau, kann bei Auslastung aber lange dauern.", vm.dienst == Dienst.GEMINI) { vm.updateDienst(Dienst.GEMINI) }
                HorizontalDivider(color = cs.outlineVariant)
                DienstOption("Nur offline", "Ohne Internet direkt am Handy. Bei Handschrift oft ungenau.", vm.dienst == Dienst.OFFLINE) { vm.updateDienst(Dienst.OFFLINE) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Sind bei Groq und Gemini Schlüssel eingetragen, springt der andere Dienst automatisch ein, wenn der gewählte nicht antwortet.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )

            // ---------- Groq ----------
            Spacer(Modifier.height(24.dp))
            Text("Groq einrichten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Karte {
                Column(Modifier.padding(14.dp)) {
                    Schritt("1", "Groq-Konsole öffnen und anmelden (z. B. mit deinem Google-Konto).")
                    Schritt("2", "„Create API Key“ antippen, einen Namen vergeben und den Schlüssel kopieren.")
                    Schritt("3", "Unten einfügen – fertig.")
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { uriHandler.openUri("https://console.groq.com/keys") }) {
                        Text("Groq-Konsole öffnen")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SchluesselFeld("Groq-API-Schlüssel", groqKeyText) { groqKeyText = it; vm.updateGroqKey(it) }
            TestZeile(vm.groqTestLaeuft, groqKeyText.isNotBlank(), vm.groqTestErgebnis) { vm.testGroq() }

            // ---------- Gemini ----------
            Spacer(Modifier.height(24.dp))
            Text("Gemini einrichten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(12.dp))
            SchluesselFeld("Gemini-API-Schlüssel", keyText) { keyText = it; vm.updateApiKey(it) }
            TestZeile(vm.testLaeuft, keyText.isNotBlank(), vm.testErgebnis) { vm.testKey() }

            // ---------- Hofer-Preise ----------
            Spacer(Modifier.height(24.dp))
            Text("Hofer-Preise", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Preise anzeigen", fontWeight = FontWeight.Medium)
                    Text(
                        "Die App lädt das Hofer-Sortiment von hofer.at und zeigt zu jedem Artikel den Preis und die Summe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = vm.preiseAn, onCheckedChange = { vm.updatePreiseAn(it) })
            }
            if (vm.preiseAn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (vm.katalogAnzahl > 0) {
                        val datum = java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.GERMAN).format(java.util.Date(vm.katalogStand))
                        "${vm.katalogAnzahl} Produkte · Stand $datum"
                    } else "Noch nicht geladen",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.primary,
                )
                vm.katalogText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (vm.katalogLaeuft) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { vm.katalogFortschritt }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.katalogLaden() }, enabled = !vm.katalogLaeuft) { Text("Jetzt aktualisieren") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wird automatisch einmal pro Woche aktualisiert. Aktionspreise und Obst/Gemüse nach Gewicht können abweichen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }

            // ---------- Laufweg ----------
            Spacer(Modifier.height(24.dp))
            Text("Reihenfolge im Geschäft", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Die Liste wird nach deinem Weg durch das Geschäft sortiert. Vorlage ist der typische Hofer-Laufweg – mit den Pfeilen passt du ihn an deine Filiale an.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Karte {
                vm.reihenfolge.forEachIndexed { i, kat ->
                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                    ) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.width(26.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(katColor(kat)))
                        Spacer(Modifier.width(10.dp))
                        Text(kat, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.verschieben(kat, -1) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "$kat nach oben")
                        }
                        IconButton(onClick = { vm.verschieben(kat, 1) }, enabled = i < vm.reihenfolge.size - 1) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "$kat nach unten")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.reihenfolgeSetzen(HOFER_REIHENFOLGE) }) { Text("Hofer-Vorlage") }
                OutlinedButton(onClick = { vm.reihenfolgeSetzen(KAT_NAMEN) }) { Text("Standard") }
            }

            // ---------- Geteilter Ordner ----------
            Spacer(Modifier.height(24.dp))
            Text("Liste mit dem Spiegel teilen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Trag einen freigegebenen Nextcloud-Ordner ein, dann landet „Kauz, merk dir Milch“ an " +
                    "der Wohnungstür in dieser Liste. Der Ordner muss als öffentlicher Link mit " +
                    "„Bearbeiten erlauben“ geteilt sein; setz dabei ein Freigabepasswort.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = wolkeLinkText,
                onValueChange = { wolkeLinkText = it; vm.updateWolkeLink(it) },
                label = { Text("Freigabelink") },
                supportingText = { Text("z. B. https://cloud.example.org/s/AbC123 – leer lassen schaltet das Teilen ab") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            SchluesselFeld("Freigabepasswort", wolkePasswortText) {
                wolkePasswortText = it
                vm.updateWolkePasswort(it)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.abgleichenImHintergrund() },
                enabled = vm.wolkeAn && !vm.wolkeLaeuft,
            ) { Text(if (vm.wolkeLaeuft) "Gleicht ab …" else "Jetzt abgleichen") }
            vm.wolkeStatus?.let { stand ->
                Spacer(Modifier.height(6.dp))
                Text(
                    stand,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vm.wolkeFehler) cs.error else cs.onSurfaceVariant,
                )
            }

            // ---------- Fortgeschritten ----------
            Spacer(Modifier.height(24.dp))
            Text("Für Fortgeschrittene", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = groqModellText,
                onValueChange = { groqModellText = it; vm.updateGroqModell(it) },
                label = { Text("Groq-Modell") },
                supportingText = { Text("Standard: $GROQ_STANDARD_MODELL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
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
                "Datenschutz: Zur Erkennung wird das Foto an den gewählten Dienst (Groq bzw. Google) geschickt. Im Gemini-Gratis-Tarif darf Google die Daten zur Verbesserung seiner Dienste verwenden. Deine Liste selbst bleibt nur auf deinem Handy.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DienstOption(titel: String, text: String, gewaehlt: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        RadioButton(selected = gewaehlt, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(titel, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SchluesselFeld(label: String, wert: String, onChange: (String) -> Unit) {
    var zeigen by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = wert,
        onValueChange = onChange,
        label = { Text(label) },
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
}

@Composable
private fun TestZeile(laeuft: Boolean, aktiv: Boolean, ergebnis: String?, onTest: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onTest, enabled = !laeuft && aktiv) {
            if (laeuft) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Schlüssel testen")
            }
        }
        Spacer(Modifier.width(12.dp))
        if (ergebnis != null) {
            Text(ergebnis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
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

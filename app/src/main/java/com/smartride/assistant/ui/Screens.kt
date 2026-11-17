package com.smartride.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartride.assistant.data.AlertLanguage
import com.smartride.assistant.data.Classification
import com.smartride.assistant.data.RideAnalysis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Actions(val start: () -> Unit, val stop: () -> Unit)

private data class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("Dashboard", Icons.Default.Home),
    Tab("Live", Icons.Default.PlayArrow),
    Tab("Results", Icons.Default.Check),
    Tab("History", Icons.Default.DateRange),
    Tab("Settings", Icons.Default.Settings),
)

@Composable
fun AppRoot(vm: MainViewModel, actions: Actions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.analyseScreenshot(uri)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.title) }, label = { Text(t.title) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> DashboardScreen(vm, actions, pick)
                1 -> LiveScreen(vm, actions)
                2 -> ResultsScreen(vm)
                3 -> HistoryScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

// ---- Screen 1: Dashboard ------------------------------------------------------------------------

@Composable
fun DashboardScreen(vm: MainViewModel, actions: Actions, onPickImage: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val m by vm.monitor.collectAsStateWithLifecycle()
    val last by vm.lastRide.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val today = remember(history) { val t = startOfToday(); history.count { it.time >= t } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Place, contentDescription = null, tint = Color.White)
            }
            Column {
                Text("Smart Ride Assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("You decide. The app only advises.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("Monitoring") {
            Text(if (m.running) "ACTIVE - ${m.captureStatus}" else "NOT RUNNING",
                color = if (m.running) Color(0xFF15803D) else Color(0xFF475569), fontWeight = FontWeight.Bold)
            m.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.start, enabled = !m.running) { Text("Start Monitoring") }
                Button(onClick = actions.stop, enabled = m.running,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))) { Text("Stop Monitoring") }
            }
        }

        SectionCard("Saved commute") {
            LabelValue("Home", s.homeText)
            LabelValue("", if (s.homePoint != null) "Located: ${s.homePoint!!.asText()}" else "NOT LOCATED - open Settings > Find on map")
            LabelValue("Office", s.officeText)
            LabelValue("", if (s.officePoint != null) "Located: ${s.officePoint!!.asText()}" else "NOT LOCATED - open Settings > Find on map")
            LabelValue("Maximum detour", fmtKm(s.maxDetourKm))
        }

        SectionCard("Today") {
            LabelValue("Rides analysed today", today.toString())
            val l = last
            if (l == null) Text("No ride detected yet.", style = MaterialTheme.typography.bodyMedium)
            else {
                Text("Last detected ride", fontWeight = FontWeight.SemiBold)
                ClassBadge(l.classification)
                LabelValue("Fare / pickup", "Rs. ${l.offer.fare ?: "?"} - ${l.offer.pickup ?: "?"}")
                LabelValue("Destination", l.offer.destination ?: "?")
                LabelValue("Additional distance", fmtKm(l.additionalKm?.coerceAtLeast(0.0)))
            }
        }

        SectionCard("Test without driving") {
            Text("Pick a saved screenshot of an inDrive offer screen. It is read on this phone only.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onPickImage) { Text("Analyse a screenshot") }
        }
    }
}

// ---- Screen 2: Live monitoring ------------------------------------------------------------------

@Composable
fun LiveScreen(vm: MainViewModel, actions: Actions) {
    val m by vm.monitor.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live monitoring", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SectionCard("Status") {
            LabelValue("Monitoring state", if (m.running) "Running" else "Stopped")
            LabelValue("Capture status", m.captureStatus)
            LabelValue("OCR processing", m.ocrStatus)
            LabelValue("Rides detected (this session)", m.detectedRides.toString())
            LabelValue("Frames analysed / skipped (unchanged)", "${m.framesAnalysed} / ${m.framesSkipped}")
            LabelValue("Last analysis", fmtTime(m.lastAnalysisAt))
            LabelValue("Last OCR time", m.lastOcrMs?.let { "$it ms" } ?: "-")
            m.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB45309)) }
        }
        Button(onClick = actions.stop, enabled = m.running, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))) { Text("Stop Monitoring") }
        Text("Open inDrive normally. This app listens in the background and speaks only when a ride fits your route. " +
            "If Android or ColorOS ends capture, monitoring stops and you must start it again.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ---- Screen 3: Results --------------------------------------------------------------------------

@Composable
fun ResultsScreen(vm: MainViewModel) {
    val results by vm.results.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RideAnalysis?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ride analysis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (busy) item { Text("Working...") }
        if (results.isEmpty()) item { Text("No ride-offer screen has been analysed yet.") }
        items(results, key = { it.offer.key + it.analysedAt }) { a -> RideCard(a, onEdit = { editing = a }) }
    }

    editing?.let { a ->
        var fare by remember(a) { mutableStateOf(a.offer.fare?.toString() ?: "") }
        var pickup by remember(a) { mutableStateOf(a.offer.pickup ?: "") }
        var dest by remember(a) { mutableStateOf(a.offer.destination ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Correct the ride text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fare, { fare = it.filter(Char::isDigit) }, label = { Text("Fare (Rs.)") }, singleLine = true)
                    OutlinedTextField(pickup, { pickup = it }, label = { Text("Pickup") })
                    OutlinedTextField(dest, { dest = it }, label = { Text("Destination") })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.applyCorrection(a, fare.toIntOrNull(), pickup, dest); editing = null }) { Text("Re-check") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ---- Screen 5: History --------------------------------------------------------------------------

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (history.isEmpty()) item { Text("Nothing analysed yet.") }
        items(history, key = { it.key + it.time }) { e ->
            SectionCard(title = "Rs. ${e.fare ?: "?"}  -  ${fmt.format(Date(e.time))}") {
                ClassBadge(e.classification)
                LabelValue("Pickup", e.pickup ?: "-")
                LabelValue("Destination", e.destination ?: "-")
                LabelValue("Estimated additional distance", fmtKm(e.additionalKm?.coerceAtLeast(0.0)))
            }
        }
    }
}

// ---- Screen 4: Settings -------------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var home by remember(s.homeText) { mutableStateOf(s.homeText) }
    var office by remember(s.officeText) { mutableStateOf(s.officeText) }
    var detour by remember(s.maxDetourKm) { mutableStateOf(s.maxDetourKm.toFloat()) }
    var volume by remember(s.alertVolume) { mutableStateOf(s.alertVolume) }
    var cooldown by remember(s.cooldownSec) { mutableStateOf(s.cooldownSec.toFloat()) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        SectionCard("Commute route") {
            Text("Type an address or paste exact coordinates such as 30.1234, 71.5678 (long-press a spot in a maps app to copy them). " +
                "Check the saved result: a wrong office location makes every detour wrong.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(home, { home = it }, label = { Text("Home") }, modifier = Modifier.fillMaxWidth())
            Text(s.homePoint?.let { "Saved: ${s.homeResolvedName ?: ""} (${it.asText()})" } ?: "Not located yet", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.findOnMap(true, home) }, enabled = !busy) { Text("Find on map") }
                OutlinedButton(onClick = { vm.useCurrentGps(true) }) { Text("Use GPS here") }
            }
            HorizontalDivider()
            OutlinedTextField(office, { office = it }, label = { Text("Office") }, modifier = Modifier.fillMaxWidth())
            Text(s.officePoint?.let { "Saved: ${s.officeResolvedName ?: ""} (${it.asText()})" } ?: "Not located yet", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.findOnMap(false, office) }, enabled = !busy) { Text("Find on map") }
                OutlinedButton(onClick = { vm.useCurrentGps(false) }) { Text("Use GPS here") }
            }
            HorizontalDivider()
            Text("Maximum additional distance: ${"%.1f".format(Locale.US, detour)} km")
            Slider(detour, { detour = it }, valueRange = 0.5f..10f, steps = 18,
                onValueChangeFinished = { vm.update { st -> st.copy(maxDetourKm = (Math.round(detour * 2) / 2.0)) } })
            Text("Currency: Pakistani Rupees (PKR)", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Voice alerts") {
            ToggleRow("Speak when a ride fits", s.voiceEnabled) { v -> vm.update { it.copy(voiceEnabled = v) } }
            ToggleRow("Also announce MODERATE rides", s.announceModerate) { v -> vm.update { it.copy(announceModerate = v) } }
            Text("Language")
            AlertLanguage.values().forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.language == l, onClick = { vm.update { it.copy(language = l) } })
                    Text(l.label)
                }
            }
            Text("Alert volume (relative to your media volume)")
            Slider(volume, { volume = it }, valueRange = 0.2f..1f, onValueChangeFinished = { vm.update { it.copy(alertVolume = volume) } })
            Text("Do not repeat the same ride for ${cooldown.toInt()} s")
            Slider(cooldown, { cooldown = it }, valueRange = 30f..600f, steps = 18,
                onValueChangeFinished = { vm.update { it.copy(cooldownSec = cooldown.toInt()) } })
            OutlinedButton(onClick = { vm.testVoice() }) { Text("Test voice") }
        }

        SectionCard("Advanced") {
            Text("OCR confidence below ${(s.minOcrConfidence * 100).toInt()}% marks a ride NEEDS REVIEW.", style = MaterialTheme.typography.bodySmall)
            var url by remember(s.routingBaseUrl) { mutableStateOf(s.routingBaseUrl) }
            OutlinedTextField(url, { url = it }, label = { Text("OSRM routing server") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("The default is the public OSRM demo server: light personal use only. Use your own server for heavy use.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.update { it.copy(routingBaseUrl = url.trim().ifEmpty { "https://router.project-osrm.org" }) } }) { Text("Save server") }
        }

        SectionCard("Oppo Reno 14 / ColorOS - keep it running") {
            Text("ColorOS may stop background apps to save battery. Menu names differ between versions, but usually:\n" +
                "1. Settings > Battery > App battery management > Smart Ride Assistant > allow background activity, no optimisation.\n" +
                "2. Settings > Apps > Smart Ride Assistant > allow notifications, location 'Allow all the time' or 'While using'.\n" +
                "3. Lock the app in the recent-apps screen.\n" +
                "If Android or ColorOS still ends capture, monitoring stops; the app will not pretend otherwise. " +
                "Press Start Monitoring again and accept the system dialog.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openSettings(ctx, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("Battery settings") }
                OutlinedButton(onClick = {
                    openSettings(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                }) { Text("App settings") }
            }
        }

        SectionCard("Privacy and permissions") {
            Text("- Screen frames are read on this phone and discarded; nothing is recorded or uploaded.\n" +
                "- Network use is limited to OpenStreetMap geocoding (Nominatim) and road routing (OSRM): only place names and coordinates are sent, never images.\n" +
                "- Screen capture: Android's own consent dialog, every time.\n" +
                "- Location: your current position to compute the detour.\n" +
                "- Notifications: required to show the monitoring notice.\n" +
                "- The app never taps, accepts or bids in inDrive. No inDrive login or API is used.\n" +
                "- History holds only time, fare, pickup, destination, result and extra distance, on this phone.\n" +
                "- You are responsible for checking that using this tool is allowed by inDrive's terms.\n" +
                "Map data (c) OpenStreetMap contributors.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear local history") }
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear history?") },
                text = { Text("This deletes all stored ride history on this phone.") },
                confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun openSettings(ctx: Context, action: String, data: Uri? = null) {
    try {
        val i = Intent(action).apply { if (data != null) this.data = data }
        ctx.startActivity(i)
    } catch (e: Exception) {
        // some ColorOS builds hide these screens; the manual steps above still apply
    }
}

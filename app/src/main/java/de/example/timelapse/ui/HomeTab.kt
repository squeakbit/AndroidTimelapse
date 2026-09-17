package de.example.timelapse.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.example.timelapse.AlarmScheduler
import de.example.timelapse.SettingsManager
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.camera.CameraRepository
import de.example.timelapse.camera.PhotoCaptureHelper
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.mqtt.MqttDiscovery
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.smb.SmbUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
    testModeEnabled: Boolean,
    onTestModeChange: (Boolean) -> Unit,
    onEnsureCameraServiceRunning: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    var enabled by remember { mutableStateOf(settings.timelapseEnabled) }
    var interval by remember { mutableStateOf(settings.captureIntervalMinutes.toString()) }
    var cameras by remember { mutableStateOf(emptyList<CameraInfo>()) }
    var selectedIds by remember { mutableStateOf(settings.selectedCameraIds) }
    var uploadStatus by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var testIntervalSeconds by remember { mutableIntStateOf(10) }
    var testShotsTaken by remember { mutableIntStateOf(0) }
    var testStatus by remember { mutableStateOf("") }
    val testModeMaxShots = 30

    LaunchedEffect(Unit) {
        cameras = withContext(Dispatchers.IO) { CameraRepository(context).list() }
        if (selectedIds.isEmpty() && cameras.isNotEmpty()) {
            selectedIds = setOf(cameras.first().id)
            settings.selectedCameraIds = selectedIds
        }
    }

    LaunchedEffect(testModeEnabled, testIntervalSeconds) {
        if (testModeEnabled) {
            testShotsTaken = 0
            while (testModeEnabled && testShotsTaken < testModeMaxShots) {
                testStatus = "Nächstes Testfoto in ${testIntervalSeconds}s …"
                delay(testIntervalSeconds.seconds)
                if (!testModeEnabled) break
                testStatus = "Nehme Testfoto(s) auf …"
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val liveSettings = SettingsManager(context)
                        val resolvedCameras = PhotoCaptureHelper.resolveCameras(context, liveSettings)
                        if (resolvedCameras.isEmpty()) throw IllegalStateException("Keine Kamera verfügbar")
                        for (camera in resolvedCameras) {
                            val (w, h) = PhotoCaptureHelper.resolveResolution(liveSettings, camera.id)
                            PhotoCaptureHelper.captureAndSave(
                                context,
                                camera.id,
                                w,
                                h,
                                liveSettings.jpegQuality,
                                PhotoCaptureHelper.cameraLabel(camera)
                            )
                        }
                        val result = SmbUploader(context).uploadPendingPhotos()
                        "OK (${resolvedCameras.size} Kamera(s)) – hochgeladen: ${result.uploaded}"
                    } catch (t: Throwable) {
                        "Fehler: ${t.message ?: t.javaClass.simpleName}"
                    }
                }
                testShotsTaken++
                testStatus = "Foto #$testShotsTaken: $outcome"
            }
            if (testShotsTaken >= testModeMaxShots) {
                testStatus += " — Limit erreicht."
                onTestModeChange(false)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (enabled) Icons.Default.Timer else Icons.Default.TimerOff, null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Timelapse Modus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (enabled) "Aktiv • Alle $interval Min." else "Deaktiviert", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            settings.timelapseEnabled = it
                            if (it) settings.lastCaptureAt = 0L
                            AlarmScheduler(context).scheduleAll()
                            if (it) {
                                onEnsureCameraServiceRunning()
                                scope.launch {
                                    try { withContext(Dispatchers.IO) { MqttClientManager(context).connectAndDiscover() } } catch (_: Exception) { }
                                }
                            } else {
                                try { context.startService(Intent(context, CameraForegroundService::class.java).setAction(CameraForegroundService.ACTION_STOP)) } catch (_: Throwable) { }
                            }
                        }
                    )
                }
            }
        }

        item {
            SectionHeader("Zeitsteuerung", Icons.Default.Schedule)
            OutlinedTextField(
                value = interval,
                onValueChange = { value ->
                    interval = value.filter(Char::isDigit)
                    value.toIntOrNull()?.let {
                        settings.captureIntervalMinutes = it
                        AlarmScheduler(context).scheduleAll()
                    }
                },
                label = { Text("Intervall (Minuten)") },
                leadingIcon = { Icon(Icons.Default.Update, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            SectionHeader("Aktive Kameras", Icons.Default.PhotoCamera)
            if (cameras.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(onClick = { selectedIds = cameras.map { it.id }.toSet(); settings.selectedCameraIds = selectedIds }, label = { Text("Alle") })
                    SuggestionChip(onClick = { selectedIds = emptySet(); settings.selectedCameraIds = selectedIds }, label = { Text("Keine") })
                }
            }
        }

        items(cameras, key = { "home_select_${it.id}" }) { camera ->
            CameraSelectionRow(
                camera = camera,
                checked = selectedIds.contains(camera.id),
                onCheckedChange = { checked ->
                    selectedIds = if (checked) selectedIds + camera.id else selectedIds - camera.id
                    settings.selectedCameraIds = selectedIds
                },
                settings = settings
            )
        }

        item {
            SectionHeader("Manuelle Aktionen", Icons.Default.CloudUpload)
            Button(
                enabled = !uploading,
                onClick = {
                    uploading = true
                    uploadStatus = "Lade hoch …"
                    scope.launch {
                        val result = try { SmbUploader(context).uploadPendingPhotos() } catch (_: Throwable) { null }
                        uploadStatus = if (result != null) "Erfolgreich: ${result.uploaded}" else "Fehler"
                        withContext(Dispatchers.IO) {
                            try {
                                val mqtt = MqttClientManager(context)
                                if (result != null) {
                                    mqtt.publish("timelapse/${settings.deviceId}/last_upload", Instant.now().toString())
                                }
                                MqttDiscovery(mqtt, settings, context).publishState()
                                mqtt.close()
                            } catch (_: Throwable) { }
                        }
                        uploading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Upload, null)
                Spacer(Modifier.width(8.dp))
                Text("Jetzt hochladen")
            }
            if (uploadStatus.isNotBlank()) Text(uploadStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        item {
            SectionHeader("Testmodus", Icons.Default.BugReport)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Kurzzeit-Testlauf", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = testModeEnabled, onCheckedChange = { onTestModeChange(it); if (it) testStatus = "" })
                    }
                    if (testModeEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = testIntervalSeconds == 10, onClick = { testIntervalSeconds = 10 }, label = { Text("10s") })
                            FilterChip(selected = testIntervalSeconds == 30, onClick = { testIntervalSeconds = 30 }, label = { Text("30s") })
                        }
                        Text("Fortschritt: $testShotsTaken / $testModeMaxShots", style = MaterialTheme.typography.bodySmall)
                        if (testStatus.isNotBlank()) Text(testStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

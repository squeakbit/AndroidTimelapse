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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.example.timelapse.AlarmScheduler
import de.example.timelapse.R
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    cameras = withContext(Dispatchers.IO) { CameraRepository(context).list() }
                    if (selectedIds.isEmpty() && cameras.isNotEmpty()) {
                        selectedIds = setOf(cameras.first().id)
                        settings.selectedCameraIds = selectedIds
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val uploadSuccessStr = stringResource(R.string.upload_success)
    val uploadFailedStr = stringResource(R.string.upload_failed)

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
                        Text(stringResource(R.string.timelapse_mode), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (enabled) stringResource(R.string.active_every, interval) else stringResource(R.string.disabled), style = MaterialTheme.typography.bodyMedium)
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
            SectionHeader(stringResource(R.string.time_control), Icons.Default.Schedule)
            OutlinedTextField(
                value = interval,
                onValueChange = { value ->
                    interval = value.filter(Char::isDigit)
                    value.toIntOrNull()?.let {
                        settings.captureIntervalMinutes = it
                        AlarmScheduler(context).scheduleAll()
                    }
                },
                label = { Text(stringResource(R.string.interval_minutes)) },
                leadingIcon = { Icon(Icons.Default.Update, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            SectionHeader(stringResource(R.string.active_cameras), Icons.Default.PhotoCamera)
            if (cameras.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(onClick = { selectedIds = cameras.map { it.id }.toSet(); settings.selectedCameraIds = selectedIds }, label = { Text(stringResource(R.string.all)) })
                    SuggestionChip(onClick = { selectedIds = emptySet(); settings.selectedCameraIds = selectedIds }, label = { Text(stringResource(R.string.none)) })
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
            SectionHeader(stringResource(R.string.manual_actions), Icons.Default.CloudUpload)
            val uploadingStr = stringResource(R.string.uploading)
            Button(
                enabled = !uploading,
                onClick = {
                    uploading = true
                    uploadStatus = uploadingStr
                    scope.launch {
                        val result = try { 
                            SmbUploader(context).uploadPendingPhotos() 
                        } catch (t: Throwable) { 
                            null 
                        }
                        
                        uploadStatus = if (result != null) {
                            if (result.uploaded > 0) uploadSuccessStr.format(result.uploaded)
                            else if (result.failed > 0) "${uploadFailedStr}: ${result.lastError ?: "Upload failed"}"
                            else "No photos pending"
                        } else uploadFailedStr
                        
                        // MQTT Update
                        withContext(Dispatchers.IO) {
                            try {
                                val mqtt = MqttClientManager(context)
                                if (result != null && result.uploaded > 0) {
                                    mqtt.publish("timelapse/${settings.deviceId}/last_upload", Instant.now().toString())
                                    // Clear pending manual upload requests if we just finished one
                                    settings.manualUploadRequested = false
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
                Text(stringResource(R.string.upload_now))
            }
            if (uploadStatus.isNotBlank()) Text(uploadStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

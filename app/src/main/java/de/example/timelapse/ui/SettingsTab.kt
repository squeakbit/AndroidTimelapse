package de.example.timelapse.ui

import android.app.TimePickerDialog
import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.example.timelapse.AlarmScheduler
import de.example.timelapse.SecureSecrets
import de.example.timelapse.SettingsManager
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.camera.CameraRepository
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.smb.SmbUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(onRequestIgnoreBatteryOptimizations: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    val secrets = remember { SecureSecrets(context) }
    var cameras by remember { mutableStateOf(emptyList<CameraInfo>()) }
    var smbTesting by remember { mutableStateOf(false) }
    var smbTestStatus by remember { mutableStateOf("") }
    var discoveryStatus by remember { mutableStateOf("") }
    var discoveryTesting by remember { mutableStateOf(false) }

    // Proper Compose state to ensure UI updates immediately
    var smbUploadEnabled by remember { mutableStateOf(settings.smbUploadEnabled) }
    var deleteAfterUpload by remember { mutableStateOf(settings.deleteAfterUpload) }
    var smbUploadHour by remember { mutableIntStateOf(settings.smbUploadHour) }
    var smbUploadMinute by remember { mutableIntStateOf(settings.smbUploadMinute) }

    LaunchedEffect(Unit) { 
        cameras = withContext(Dispatchers.IO) { CameraRepository(context).list() } 
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionHeader("Allgemein", Icons.Default.Info)
            OutlinedTextField(
                value = settings.deviceName,
                onValueChange = { settings.deviceName = it },
                label = { Text("Gerätename") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Label, null) }
            )
            Text("ID: ${settings.deviceId}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        item {
            SectionHeader("Standard-Auflösung", Icons.Default.AspectRatio)
            val selectedCamera = cameras.firstOrNull { it.id in settings.selectedCameraIds } ?: cameras.firstOrNull()
            var resolutionExpanded by remember { mutableStateOf(false) }
            val currentSize = selectedCamera?.sizes?.firstOrNull { it.width == settings.cameraWidth && it.height == settings.cameraHeight } ?: selectedCamera?.sizes?.firstOrNull()

            ExposedDropdownMenuBox(expanded = resolutionExpanded, onExpandedChange = { resolutionExpanded = it }) {
                OutlinedTextField(
                    value = currentSize?.toString() ?: "Lade...",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Bildgröße") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = resolutionExpanded, onDismissRequest = { resolutionExpanded = false }) {
                    (selectedCamera?.sizes ?: emptyList()).forEach { size ->
                        DropdownMenuItem(text = { Text(size.toString()) }, onClick = {
                            settings.cameraWidth = size.width; settings.cameraHeight = size.height
                            resolutionExpanded = false
                        })
                    }
                }
            }
        }

        item {
            SectionHeader("SMB Cloud", Icons.Default.Cloud)
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Autoupload", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Switch(
                            checked = smbUploadEnabled,
                            onCheckedChange = {
                                smbUploadEnabled = it
                                settings.smbUploadEnabled = it
                                AlarmScheduler(context).scheduleAll()
                            }
                        )
                    }

                    Button(
                        onClick = {
                            TimePickerDialog(context, { _, h, m ->
                                smbUploadHour = h; smbUploadMinute = m
                                settings.smbUploadHour = h; settings.smbUploadMinute = m
                                AlarmScheduler(context).scheduleUpload()
                            }, smbUploadHour, smbUploadMinute, true).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Schedule, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Upload täglich um %02d:%02d".format(smbUploadHour, smbUploadMinute))
                    }

                    OutlinedTextField(value = settings.smbHost, onValueChange = { settings.smbHost = it }, label = { Text("Server") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = settings.smbShare, onValueChange = { settings.smbShare = it }, label = { Text("Share") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = secrets.smbUsername, onValueChange = { secrets.smbUsername = it }, label = { Text("User") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = secrets.smbPassword, onValueChange = { secrets.smbPassword = it }, label = { Text("Passwort") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bilder nach Upload löschen", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = deleteAfterUpload,
                            onCheckedChange = {
                                deleteAfterUpload = it
                                settings.deleteAfterUpload = it
                            }
                        )
                    }
                    Text(
                        "Das aktuellste Referenzfoto pro Kamera bleibt als 'Ghost' erhalten.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )

                    Button(
                        enabled = !smbTesting,
                        onClick = {
                            smbTesting = true; smbTestStatus = "Teste..."
                            scope.launch {
                                val r = SmbUploader(context).testConnection()
                                smbTestStatus = r.fold(onSuccess = { "OK: $it" }, onFailure = { "Fehler" })
                                smbTesting = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("SMB Testen") }
                    if (smbTestStatus.isNotBlank()) Text(smbTestStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionHeader("MQTT / HA", Icons.Default.Wifi)
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = settings.mqttHost, onValueChange = { settings.mqttHost = it }, label = { Text("Server") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = secrets.mqttUsername, onValueChange = { secrets.mqttUsername = it }, label = { Text("Benutzername") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = secrets.mqttPassword, onValueChange = { secrets.mqttPassword = it }, label = { Text("Passwort") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())

                    Button(
                        enabled = !discoveryTesting,
                        onClick = {
                            discoveryTesting = true; discoveryStatus = "Sende..."
                            scope.launch {
                                try { withContext(Dispatchers.IO) { MqttClientManager(context).connectAndDiscover() }; discoveryStatus = "OK" }
                                catch (e: Exception) { discoveryStatus = "Fehler" }
                                discoveryTesting = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Discovery Senden") }
                    if (discoveryStatus.isNotBlank()) Text(discoveryStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionHeader("System", Icons.Default.Build)
            val powerManager = remember { context.getSystemService(PowerManager::class.java) }
            val ignoringOpt = remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

            Button(
                onClick = { onRequestIgnoreBatteryOptimizations() },
                colors = ButtonDefaults.buttonColors(containerColor = if (ignoringOpt.value) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (ignoringOpt.value) "Akku-Optimierung: AUS" else "Optimierung deaktivieren!", color = if (ignoringOpt.value) MaterialTheme.colorScheme.onSurfaceVariant else Color.White)
            }
        }
    }
}

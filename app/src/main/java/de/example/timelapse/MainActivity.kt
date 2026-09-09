package de.example.timelapse

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import de.example.timelapse.camera.Camera2Capture
import de.example.timelapse.camera.CameraPreviewController
import de.example.timelapse.camera.CameraRepository
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.camera.PhotoCaptureHelper
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.mqtt.MqttDiscovery
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.smb.SmbUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermission.launch(Manifest.permission.CAMERA)
        // Self-heal scheduling on every launch: if alarms were ever lost
        // (fresh install instead of update, OS/OEM cleared them, etc.) the
        // app previously only re-armed them when a switch was toggled.
        AlarmScheduler(this).scheduleAll()
        ensureCameraServiceRunning()
        setContent { AppRoot() }
    }

    override fun onResume() {
        super.onResume()
        // The camera-type foreground service can only be *started* while
        // the app is in the foreground (Android 14+ restriction) - so every
        // time the app becomes visible is also our best opportunity to
        // revive the service if it died in the background since we can't
        // reliably restart it from a background alarm.
        ensureCameraServiceRunning()
    }

    /** Starts CameraForegroundService if timelapse is enabled and it isn't already running. */
    private fun ensureCameraServiceRunning() {
        if (!SettingsManager(this).timelapseEnabled) return
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CameraForegroundService::class.java).setAction(CameraForegroundService.ACTION_START)
            )
        } catch (t: Throwable) {
            android.util.Log.w("Timelapse", "failed to start camera service from foreground", t)
        }
    }

    /** Scales down to [maxLongSide] while preserving the aspect ratio of the selected resolution. */
    private fun previewCaptureSize(width: Int, height: Int, maxLongSide: Int = 1280): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 640 to 480
        val long = maxOf(width, height)
        if (long <= maxLongSide) return width to height
        val scale = maxLongSide.toFloat() / long
        return (width * scale).toInt().coerceAtLeast(2) to (height * scale).toInt().coerceAtLeast(2)
    }

    private suspend fun capturePreview(cameraId: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val settings = SettingsManager(this@MainActivity)
            val (w, h) = previewCaptureSize(settings.cameraWidth, settings.cameraHeight)
            val temp = File.createTempFile("preview-", ".jpg", cacheDir)
            Camera2Capture(this@MainActivity).capture(cameraId, w, h, 80, temp)
            val bmp = BitmapFactory.decodeFile(temp.absolutePath)
            temp.delete()
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    @Composable
    private fun AppRoot() {
        var tab by remember { mutableIntStateOf(0) }
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Android Timelapse",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Start") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Einstellungen") })
                }
                when (tab) {
                    0 -> HomeTab()
                    else -> SettingsTab()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeTab() {
        val settings = remember { SettingsManager(this) }
        val previewController = remember { CameraPreviewController(this) }

        var enabled by remember { mutableStateOf(settings.timelapseEnabled) }
        var interval by remember { mutableStateOf(settings.captureIntervalMinutes.toString()) }

        var cameras by remember { mutableStateOf(emptyList<CameraInfo>()) }
        var selectedCameraId by remember { mutableStateOf(settings.cameraId) }
        var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewLoading by remember { mutableStateOf(false) }
        var liveEnabled by remember { mutableStateOf(false) }
        var textureSurface by remember { mutableStateOf<Surface?>(null) }

        var windowEnabled by remember { mutableStateOf(settings.timeWindowEnabled) }
        var windowStartHour by remember { mutableIntStateOf(settings.windowStartHour) }
        var windowStartMinute by remember { mutableIntStateOf(settings.windowStartMinute) }
        var windowEndHour by remember { mutableIntStateOf(settings.windowEndHour) }
        var windowEndMinute by remember { mutableIntStateOf(settings.windowEndMinute) }

        var uploadStatus by remember { mutableStateOf("") }
        var uploading by remember { mutableStateOf(false) }

        var testModeEnabled by remember { mutableStateOf(false) }
        var testIntervalSeconds by remember { mutableIntStateOf(10) }
        var testShotsTaken by remember { mutableIntStateOf(0) }
        var testStatus by remember { mutableStateOf("") }
        val testModeMaxShots = 30

        LaunchedEffect(Unit) {
            cameras = withContext(Dispatchers.IO) { CameraRepository(this@MainActivity).list() }
            if (selectedCameraId.isBlank()) {
                selectedCameraId = cameras.firstOrNull()?.id ?: ""
                settings.cameraId = selectedCameraId
            }
        }

        LaunchedEffect(selectedCameraId, liveEnabled) {
            if (!liveEnabled && selectedCameraId.isNotBlank()) {
                previewLoading = true
                previewBitmap = capturePreview(selectedCameraId)
                previewLoading = false
            }
        }

        // (Re)starts the live preview whenever the switch, selected camera,
        // or the TextureView's surface changes; stops it otherwise.
        LaunchedEffect(liveEnabled, selectedCameraId, textureSurface) {
            val surface = textureSurface
            if (liveEnabled && surface != null && selectedCameraId.isNotBlank()) {
                previewController.start(selectedCameraId, surface)
            } else {
                previewController.stop()
            }
        }

        // Safety net: the camera is exclusive, so don't let a forgotten live
        // preview or test mode block scheduled captures once the app is
        // backgrounded.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    liveEnabled = false
                    testModeEnabled = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        DisposableEffect(Unit) {
            onDispose {
                previewController.stop()
                textureSurface?.release()
                textureSurface = null
            }
        }

        // Captures a test photo and uploads it immediately (bypassing the
        // normal schedule), pausing testIntervalSeconds between shots, up to
        // a safety limit so it can't be forgotten running indefinitely.
        LaunchedEffect(testModeEnabled, testIntervalSeconds, selectedCameraId) {
            if (testModeEnabled && selectedCameraId.isNotBlank()) {
                testShotsTaken = 0
                while (testModeEnabled && testShotsTaken < testModeMaxShots) {
                    testStatus = "Nächstes Testfoto in ${testIntervalSeconds}s …"
                    delay(testIntervalSeconds.seconds)
                    if (!testModeEnabled) break
                    testStatus = "Nehme Testfoto auf …"
                    val outcome = withContext(Dispatchers.IO) {
                        try {
                            val liveSettings = SettingsManager(this@MainActivity)
                            PhotoCaptureHelper.captureAndSave(
                                this@MainActivity,
                                selectedCameraId,
                                liveSettings.cameraWidth,
                                liveSettings.cameraHeight,
                                liveSettings.jpegQuality
                            )
                            val result = SmbUploader(this@MainActivity).uploadPendingPhotos()
                            "OK – hochgeladen: ${result.uploaded}, fehlgeschlagen: ${result.failed}"
                        } catch (t: Throwable) {
                            "Fehler: ${t.message ?: t.javaClass.simpleName}"
                        }
                    }
                    testShotsTaken++
                    testStatus = "Foto #$testShotsTaken: $outcome"
                }
                if (testShotsTaken >= testModeMaxShots) {
                    testStatus += " — Sicherheitslimit erreicht, Testmodus automatisch beendet."
                    testModeEnabled = false
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Timelapse aktiv", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            settings.timelapseEnabled = it
                            AlarmScheduler(this@MainActivity).scheduleAll()
                            if (it) {
                                ensureCameraServiceRunning()
                                lifecycleScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            MqttClientManager(this@MainActivity).connectAndDiscover()
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                            } else {
                                try {
                                    startService(
                                        Intent(this@MainActivity, CameraForegroundService::class.java)
                                            .setAction(CameraForegroundService.ACTION_STOP)
                                    )
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = interval,
                    onValueChange = { value ->
                        interval = value.filter(Char::isDigit)
                        value.toIntOrNull()?.let { settings.captureIntervalMinutes = it }
                    },
                    label = { Text("Intervall Minuten") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Text("Kamera", style = MaterialTheme.typography.titleMedium)
            }

            item {
                val aspect = if (settings.cameraHeight > 0)
                    settings.cameraWidth.toFloat() / settings.cameraHeight else 4f / 3f
                Card(modifier = Modifier.fillMaxWidth().aspectRatio(aspect)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            liveEnabled -> AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    TextureView(ctx).apply {
                                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                                val (pw, ph) = previewCaptureSize(settings.cameraWidth, settings.cameraHeight)
                                                st.setDefaultBufferSize(pw, ph)
                                                // Release any previously held Surface before replacing
                                                // it, so nothing leaks if this fires again without an
                                                // intervening onSurfaceTextureDestroyed.
                                                textureSurface?.release()
                                                textureSurface = Surface(st)
                                            }
                                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                textureSurface?.release()
                                                textureSurface = null
                                                return true
                                            }
                                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                        }
                                    }
                                }
                            )
                            previewLoading -> CircularProgressIndicator()
                            previewBitmap != null -> Image(
                                bitmap = previewBitmap!!.asImageBitmap(),
                                contentDescription = "Kameravorschau",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            else -> Text("Keine Vorschau verfügbar")
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live-Vorschau")
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = liveEnabled,
                        onCheckedChange = {
                            liveEnabled = it
                            if (it) testModeEnabled = false
                        }
                    )
                }
            }

            if (!liveEnabled) {
                item {
                    Button(
                        enabled = !previewLoading && selectedCameraId.isNotBlank(),
                        onClick = {
                            lifecycleScope.launch {
                                previewLoading = true
                                previewBitmap = capturePreview(selectedCameraId)
                                previewLoading = false
                            }
                        }
                    ) {
                        Text(if (previewLoading) "Nehme Vorschau auf …" else "Vorschau aktualisieren")
                    }
                }
            } else {
                item {
                    Text(
                        "Live-Vorschau blockiert geplante Aufnahmen, solange sie läuft – " +
                                "wird beim Verlassen der App automatisch beendet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            items(cameras, key = { it.id }) { camera ->
                val facingText = when (camera.facing) {
                    0 -> "Front"
                    1 -> "Back"
                    2 -> "External"
                    else -> "Unbekannt"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedCameraId == camera.id,
                        onClick = {
                            selectedCameraId = camera.id
                            settings.cameraId = camera.id
                        }
                    )
                    Text("${camera.id} ($facingText${if (camera.logicalMultiCamera) ", logical" else ""})")
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Zeitfenster", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nur in Zeitfenster aufnehmen")
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = windowEnabled,
                        onCheckedChange = {
                            windowEnabled = it
                            settings.timeWindowEnabled = it
                        }
                    )
                }
            }

            if (windowEnabled) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                TimePickerDialog(
                                    this@MainActivity,
                                    { _, h, m ->
                                        windowStartHour = h
                                        windowStartMinute = m
                                        settings.windowStartHour = h
                                        settings.windowStartMinute = m
                                    },
                                    windowStartHour,
                                    windowStartMinute,
                                    true
                                ).show()
                            }
                        ) {
                            Text("Start %02d:%02d".format(windowStartHour, windowStartMinute))
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                TimePickerDialog(
                                    this@MainActivity,
                                    { _, h, m ->
                                        windowEndHour = h
                                        windowEndMinute = m
                                        settings.windowEndHour = h
                                        settings.windowEndMinute = m
                                    },
                                    windowEndHour,
                                    windowEndMinute,
                                    true
                                ).show()
                            }
                        ) {
                            Text("Ende %02d:%02d".format(windowEndHour, windowEndMinute))
                        }
                    }
                }
                item {
                    Text(
                        "Läuft täglich, auch über Mitternacht hinweg (z.B. 18:00–06:00).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Übertragung", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Button(
                    enabled = !uploading,
                    onClick = {
                        uploading = true
                        uploadStatus = "Lade hoch …"
                        lifecycleScope.launch {
                            val result = try {
                                SmbUploader(this@MainActivity).uploadPendingPhotos()
                            } catch (_: Throwable) {
                                null
                            }
                            uploadStatus = if (result != null) {
                                buildString {
                                    append("Hochgeladen: ${result.uploaded}, fehlgeschlagen: ${result.failed}")
                                    if (result.removed > 0) append(", entfernt (Datei fehlte): ${result.removed}")
                                }
                            } else {
                                "Upload fehlgeschlagen"
                            }
                            // Keep MQTT sensors in sync immediately instead of
                            // waiting for the next scheduled capture or the daily
                            // upload sync, since this manual trigger bypasses both.
                            withContext(Dispatchers.IO) {
                                try {
                                    val mqtt = MqttClientManager(this@MainActivity)
                                    if (result != null) {
                                        mqtt.publish("timelapse/${settings.deviceId}/last_upload_count", result.uploaded.toString())
                                        mqtt.publish("timelapse/${settings.deviceId}/last_upload_failed", result.failed.toString())
                                        mqtt.publish("timelapse/${settings.deviceId}/last_upload", java.time.Instant.now().toString())
                                    }
                                    MqttDiscovery(mqtt, settings, this@MainActivity).publishState()
                                    mqtt.close()
                                } catch (_: Throwable) {
                                }
                            }
                            uploading = false
                        }
                    }
                ) {
                    Text(if (uploading) "Lade hoch …" else "Jetzt hochladen")
                }
            }

            if (uploadStatus.isNotBlank()) {
                item { Text(uploadStatus) }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Testmodus", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ignoriert Intervall und Zeitfenster: nimmt in kurzem Abstand Testfotos auf " +
                            "und lädt sie sofort per SMB hoch. Läuft nur im Vordergrund und stoppt " +
                            "automatisch nach $testModeMaxShots Fotos.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Testmodus aktiv")
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = testModeEnabled,
                        onCheckedChange = {
                            testModeEnabled = it
                            if (it) {
                                liveEnabled = false
                                testStatus = ""
                            }
                        }
                    )
                }
            }

            if (testModeEnabled) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = testIntervalSeconds == 10, onClick = { testIntervalSeconds = 10 }, label = { Text("10 Sek.") })
                        FilterChip(selected = testIntervalSeconds == 30, onClick = { testIntervalSeconds = 30 }, label = { Text("30 Sek.") })
                        FilterChip(selected = testIntervalSeconds == 60, onClick = { testIntervalSeconds = 60 }, label = { Text("1 Min.") })
                    }
                }
                item { Text("Fotos in diesem Lauf: $testShotsTaken / $testModeMaxShots") }
            }

            if (testStatus.isNotBlank()) {
                item { Text(testStatus) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsTab() {
        val settings = remember { SettingsManager(this) }
        val secrets = remember { SecureSecrets(this) }

        var deviceName by remember { mutableStateOf(settings.deviceName) }
        var jpegQuality by remember { mutableStateOf(settings.jpegQuality.toString()) }
        var cameras by remember { mutableStateOf(emptyList<CameraInfo>()) }

        LaunchedEffect(Unit) {
            cameras = withContext(Dispatchers.IO) { CameraRepository(this@MainActivity).list() }
        }

        var smbEnabled by remember { mutableStateOf(settings.smbUploadEnabled) }
        var smbHost by remember { mutableStateOf(settings.smbHost) }
        var smbShare by remember { mutableStateOf(settings.smbShare) }
        var smbRemoteDirectory by remember { mutableStateOf(settings.smbRemoteDirectory) }
        var smbUsername by remember { mutableStateOf(secrets.smbUsername) }
        var smbPassword by remember { mutableStateOf(secrets.smbPassword) }
        var smbDomain by remember { mutableStateOf(settings.smbDomain) }
        var smbTestStatus by remember { mutableStateOf("") }
        var smbTesting by remember { mutableStateOf(false) }
        var smbUploadHour by remember { mutableIntStateOf(settings.smbUploadHour) }
        var smbUploadMinute by remember { mutableIntStateOf(settings.smbUploadMinute) }
        var deleteAfterUpload by remember { mutableStateOf(settings.deleteAfterUpload) }

        var mqttHost by remember { mutableStateOf(settings.mqttHost) }
        var mqttUsername by remember { mutableStateOf(secrets.mqttUsername) }
        var mqttPassword by remember { mutableStateOf(secrets.mqttPassword) }
        var discoveryStatus by remember { mutableStateOf("") }
        var discoveryTesting by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = {
                        deviceName = it
                        settings.deviceName = it
                    },
                    label = { Text("Gerätename") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { Text("Geräte-ID: ${settings.deviceId}") }

            item {
                Text("Kamera", style = MaterialTheme.typography.titleMedium)
            }

            item { Text("Speicherort: Pictures/Timelapse/<Datum>/") }

            item {
                val selectedCamera = cameras.firstOrNull { it.id == settings.cameraId }
                var resolutionExpanded by remember { mutableStateOf(false) }
                var selectedSize by remember(selectedCamera) {
                    mutableStateOf(
                        selectedCamera?.sizes?.firstOrNull { it.width == settings.cameraWidth && it.height == settings.cameraHeight }
                            ?: selectedCamera?.sizes?.firstOrNull()
                    )
                }
                LaunchedEffect(selectedCamera) {
                    if (selectedCamera != null &&
                        selectedCamera.sizes.none { it.width == settings.cameraWidth && it.height == settings.cameraHeight }
                    ) {
                        selectedCamera.sizes.firstOrNull()?.let {
                            settings.cameraWidth = it.width
                            settings.cameraHeight = it.height
                        }
                    }
                }
                Column {
                    ExposedDropdownMenuBox(
                        expanded = resolutionExpanded,
                        onExpandedChange = { resolutionExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedSize?.toString() ?: "Keine Auflösung erkannt",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Auflösung") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = resolutionExpanded,
                            onDismissRequest = { resolutionExpanded = false }
                        ) {
                            (selectedCamera?.sizes ?: emptyList()).forEach { size ->
                                DropdownMenuItem(
                                    text = { Text(size.toString()) },
                                    onClick = {
                                        selectedSize = size
                                        settings.cameraWidth = size.width
                                        settings.cameraHeight = size.height
                                        resolutionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (selectedCamera == null) {
                        Text(
                            "Kamera wird geladen – bitte kurz warten oder zuerst im Start-Tab eine Kamera auswählen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = jpegQuality,
                    onValueChange = { value ->
                        jpegQuality = value.filter(Char::isDigit)
                        value.toIntOrNull()?.let { settings.jpegQuality = it }
                    },
                    label = { Text("JPEG Qualität (1-100)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }


            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("SMB", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Automatischer Upload")
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = smbEnabled,
                        onCheckedChange = {
                            smbEnabled = it
                            settings.smbUploadEnabled = it
                            AlarmScheduler(this@MainActivity).scheduleAll()
                        }
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = smbHost,
                    onValueChange = {
                        smbHost = it
                        settings.smbHost = it
                    },
                    label = { Text("SMB Server") },
                    placeholder = { Text("z.B. 192.168.1.10") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = smbShare,
                    onValueChange = {
                        smbShare = it
                        settings.smbShare = it
                    },
                    label = { Text("SMB Share") },
                    placeholder = { Text("z.B. timelapse") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = smbRemoteDirectory,
                    onValueChange = {
                        smbRemoteDirectory = it
                        settings.smbRemoteDirectory = it
                    },
                    label = { Text("SMB Zielverzeichnis") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = smbUsername,
                    onValueChange = {
                        smbUsername = it
                        secrets.smbUsername = it
                    },
                    label = { Text("SMB Benutzername") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = smbPassword,
                    onValueChange = {
                        smbPassword = it
                        secrets.smbPassword = it
                    },
                    label = { Text("SMB Passwort") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = smbDomain,
                    onValueChange = {
                        smbDomain = it
                        settings.smbDomain = it
                    },
                    label = { Text("SMB Domain (optional)") },
                    placeholder = { Text("z.B. WORKGROUP") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    enabled = !smbTesting,
                    onClick = {
                        smbTesting = true
                        smbTestStatus = "Teste Verbindung …"
                        lifecycleScope.launch {
                            val result = SmbUploader(this@MainActivity).testConnection()
                            smbTestStatus = result.fold(
                                onSuccess = { it },
                                onFailure = { "Fehler: ${it.message ?: it.javaClass.simpleName}" }
                            )
                            smbTesting = false
                        }
                    }
                ) {
                    Text(if (smbTesting) "Teste …" else "SMB-Verbindung testen")
                }
            }

            if (smbTestStatus.isNotBlank()) {
                item { Text(smbTestStatus) }
            }

            // Der Intervall-Upload wurde wieder entfernt (unzuverlässig, nur
            // erhöhter Akkuverbrauch). Upload läuft ausschließlich täglich
            // zu einer festen Uhrzeit.
            item {
                Button(
                    onClick = {
                        TimePickerDialog(
                            this@MainActivity,
                            { _, h, m ->
                                smbUploadHour = h
                                smbUploadMinute = m
                                settings.smbUploadHour = h
                                settings.smbUploadMinute = m
                                AlarmScheduler(this@MainActivity).scheduleUpload()
                            },
                            smbUploadHour,
                            smbUploadMinute,
                            true
                        ).show()
                    }
                ) {
                    Text("Uploadzeit %02d:%02d".format(smbUploadHour, smbUploadMinute))
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nach erfolgreichem Upload löschen")
                        Text(
                            "Andernfalls bleiben Fotos dauerhaft auf dem Gerät, auch nach Upload.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = deleteAfterUpload,
                        onCheckedChange = {
                            deleteAfterUpload = it
                            settings.deleteAfterUpload = it
                        }
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("MQTT", style = MaterialTheme.typography.titleMedium)
            }

            item {
                OutlinedTextField(
                    value = mqttHost,
                    onValueChange = {
                        mqttHost = it
                        settings.mqttHost = it
                    },
                    label = { Text("MQTT Server") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = mqttUsername,
                    onValueChange = {
                        mqttUsername = it
                        secrets.mqttUsername = it
                    },
                    label = { Text("MQTT Benutzername") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = mqttPassword,
                    onValueChange = {
                        mqttPassword = it
                        secrets.mqttPassword = it
                    },
                    label = { Text("MQTT Passwort") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    enabled = !discoveryTesting,
                    onClick = {
                        discoveryTesting = true
                        discoveryStatus = "Verbinde …"
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    MqttClientManager(this@MainActivity).connectAndDiscover()
                                }
                                discoveryStatus = "Discovery erfolgreich gesendet"
                            } catch (e: Exception) {
                                discoveryStatus = "MQTT-Fehler: ${e.message ?: e.javaClass.simpleName}"
                            }
                            discoveryTesting = false
                        }
                    }
                ) {
                    Text(if (discoveryTesting) "Teste …" else "MQTT Discovery senden")
                }
            }

            if (discoveryStatus.isNotBlank()) {
                item { Text(discoveryStatus) }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Zuverlässigkeit", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Damit geplante Aufnahmen/Uploads nicht vom System verzögert " +
                            "oder unterdrückt werden, sollte die App von der Akku-" +
                            "Optimierung ausgenommen werden (besonders wichtig bei " +
                            "Samsung/Xiaomi/Huawei & Co).",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                val powerManager = remember { getSystemService(android.os.PowerManager::class.java) }
                val ignoringOptimizations = remember {
                    mutableStateOf(powerManager.isIgnoringBatteryOptimizations(packageName))
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            ignoringOptimizations.value = powerManager.isIgnoringBatteryOptimizations(packageName)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                Button(
                    enabled = !ignoringOptimizations.value,
                    onClick = { requestIgnoreBatteryOptimizations() }
                ) {
                    Text(
                        if (ignoringOptimizations.value) "Akku-Optimierung bereits deaktiviert"
                        else "Akku-Optimierung deaktivieren"
                    )
                }
            }

            // SCHEDULE_EXACT_ALARM permission management only exists from API 31
            // onward; below that, the manifest-declared permission is always
            // granted and there is nothing for the user to enable here.
            if (Build.VERSION.SDK_INT >= 31) {
                item {
                    val alarmManager = remember { getSystemService(android.app.AlarmManager::class.java) }
                    val canScheduleExact = remember { mutableStateOf(alarmManager.canScheduleExactAlarms()) }
                    val lifecycleOwner3 = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner3) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                canScheduleExact.value = alarmManager.canScheduleExactAlarms()
                            }
                        }
                        lifecycleOwner3.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner3.lifecycle.removeObserver(observer) }
                    }
                    Button(
                        enabled = !canScheduleExact.value,
                        onClick = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    "package:$packageName".toUri()
                                )
                            )
                        }
                    ) {
                        Text(
                            if (canScheduleExact.value) "Alarm-Berechtigung bereits erteilt"
                            else "Alarm-Berechtigung öffnen"
                        )
                    }
                }
            }

            item {
                var lastCapture by remember { mutableLongStateOf(settings.lastCaptureAt) }
                val lifecycleOwner2 = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner2) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) lastCapture = settings.lastCaptureAt
                    }
                    lifecycleOwner2.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner2.lifecycle.removeObserver(observer) }
                }
                Text(
                    if (lastCapture > 0)
                        "Letzte Aufnahme: ${java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date(lastCapture))} " +
                                "(vor ${(System.currentTimeMillis() - lastCapture) / 60000} Min.)"
                    else "Noch keine Aufnahme ausgeführt",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Aktualisiert sich mit jeder geplanten Aufnahme, solange die App läuft " +
                            "(App erneut öffnen, um den Wert hier zu aktualisieren). Bleibt er " +
                            "über Stunden stehen, prüfe die Akku-Optimierung und die Alarm-" +
                            "Berechtigung oben.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // This app is an unattended background capture/upload tool, which is one of
    // the accepted use cases for asking the user to exempt it from battery
    // optimizations (Play policy requires this to be a deliberate,
    // user-initiated action rather than something the app does silently -
    // which is exactly what the "Akku-Optimierung deaktivieren" button above is).
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:$packageName".toUri()
            )
        )
    }
}
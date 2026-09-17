package de.example.timelapse

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import de.example.timelapse.camera.Camera2Capture
import de.example.timelapse.camera.CameraPreviewController
import de.example.timelapse.camera.CameraRepository
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.camera.PhotoCaptureHelper
import de.example.timelapse.data.AppDatabase
import de.example.timelapse.data.PhotoEntity
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.mqtt.MqttDiscovery
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.smb.SmbUploader
import de.example.timelapse.ui.theme.TimelapseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private sealed class GhostPhotoState {
    object Idle : GhostPhotoState()
    object Loading : GhostPhotoState()
    object NoPhoto : GhostPhotoState()
    object LoadFailed : GhostPhotoState()
    data class Loaded(val bitmap: Bitmap) : GhostPhotoState()
}

private suspend fun loadGhostPhotoState(context: Context, cameraLabel: String?): GhostPhotoState =
    withContext(Dispatchers.IO) {
        if (cameraLabel == null) return@withContext GhostPhotoState.NoPhoto
        try {
            val photo = AppDatabase.getInstance(context).photoDao().getLastPhotoByCameraLabel(cameraLabel)
                ?: return@withContext GhostPhotoState.NoPhoto
            val bitmap = decodeOrientedBitmap(context, Uri.parse(photo.localPath))
            if (bitmap != null) GhostPhotoState.Loaded(bitmap) else GhostPhotoState.LoadFailed
        } catch (_: Throwable) {
            GhostPhotoState.LoadFailed
        }
    }

/**
 * Checks whether a DB entry not only exists but its underlying file/URI is
 * actually openable right now. A DB row alone isn't enough evidence that a
 * "ghost" photo is usable - e.g. after the user deleted the file outside
 * the app, or copied a replacement back without the app having read access
 * to it (see Android manifest for READ_MEDIA_IMAGES permission) - so every
 * Storage angle) - so every caller that decides whether to enable/show the
 * GHOST feature should go through this instead of a bare null-check.
 */
private suspend fun hasReadableGhostPhoto(context: Context, cameraLabel: String?): Boolean =
    withContext(Dispatchers.IO) {
        if (cameraLabel == null) return@withContext false
        val entry = AppDatabase.getInstance(context).photoDao().getLastPhotoByCameraLabel(cameraLabel)
            ?: return@withContext false
        try {
            context.contentResolver.openInputStream(Uri.parse(entry.localPath))?.use { true } ?: false
        } catch (_: Throwable) {
            false
        }
    }

private fun exifRotationDegrees(exif: ExifInterface): Int =
    when (exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

private fun rotateBitmapIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun decodeOrientedBitmap(path: String): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(path) ?: return null
    val degrees = try {
        exifRotationDegrees(ExifInterface(path))
    } catch (_: Throwable) {
        0
    }
    return rotateBitmapIfNeeded(bitmap, degrees)
}

private fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bitmap = try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) {
        null
    } ?: return null

    val degrees = try {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            exifRotationDegrees(ExifInterface(pfd.fileDescriptor))
        } ?: 0
    } catch (_: Throwable) {
        0
    }
    return rotateBitmapIfNeeded(bitmap, degrees)
}

class MainActivity : ComponentActivity() {

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Needed to READ media that this app did not itself create via MediaStore
    // (e.g. "ghost" reference photos copied back onto the device manually).
    // Photos captured by the app itself (PhotoCaptureHelper) don't need this,
    // since apps always retain read/write access to their own MediaStore
    // entries - this is specifically for third-party-written files.
    private val mediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermission.launch(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < 29) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else if (Build.VERSION.SDK_INT >= 33) {
            mediaPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            mediaPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        AlarmScheduler(this).scheduleAll()
        ensureCameraServiceRunning()
        setContent {
            TimelapseTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensureCameraServiceRunning()
    }

    private fun ensureCameraServiceRunning() {
        if (!SettingsManager(this).timelapseEnabled) return
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CameraForegroundService::class.java).setAction(CameraForegroundService.ACTION_START)
            )
        } catch (t: Throwable) {
            Log.w("Timelapse", "failed to start camera service from foreground", t)
        }
    }

    private suspend fun capturePreview(cameraId: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val settings = SettingsManager(this@MainActivity)
            val (rw, rh) = PhotoCaptureHelper.resolveResolution(settings, cameraId)
            val (w, h) = rw to rh
            val temp = File.createTempFile("preview-", ".jpg", cacheDir)
            val camera = Camera2Capture(this@MainActivity)
            try {
                camera.capture(cameraId, w, h, 80, temp)
            } finally {
                camera.close()
            }
            val bmp = decodeOrientedBitmap(temp.absolutePath)
            temp.delete()
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun facingLabel(facing: Int): String = when (facing) {
        0 -> "Front"
        1 -> "Back"
        2 -> "External"
        else -> "Unbekannt"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot() {
        var tab by remember { mutableIntStateOf(0) }
        var liveEnabled by remember { mutableStateOf(false) }
        var testModeEnabled by remember { mutableStateOf(false) }
        LaunchedEffect(liveEnabled) { if (liveEnabled) testModeEnabled = false }
        LaunchedEffect(testModeEnabled) { if (testModeEnabled) liveEnabled = false }

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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Android Timelapse", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Start") }, icon = { Icon(Icons.Default.PlayArrow, null) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Kamera") }, icon = { Icon(Icons.Default.CameraAlt, null) })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Setup") }, icon = { Icon(Icons.Default.Settings, null) })
                }
                when (tab) {
                    0 -> HomeTab(testModeEnabled = testModeEnabled, onTestModeChange = { testModeEnabled = it })
                    1 -> CameraTab(liveEnabled = liveEnabled, onLiveEnabledChange = { liveEnabled = it })
                    else -> SettingsTab()
                }
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String, icon: ImageVector) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CameraSelectionRow(
        camera: CameraInfo,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        settings: SettingsManager
    ) {
        var resExpanded by remember(camera.id) { mutableStateOf(false) }
        var override by remember(camera.id) { mutableStateOf(settings.cameraResolutionOverride(camera.id)) }
        val defaultLabel = "${settings.cameraWidth} × ${settings.cameraHeight}"
        val currentLabel = override?.let { "${it.first} × ${it.second}" } ?: "Standard ($defaultLabel)"

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Kamera ${camera.id}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = facingLabel(camera.facing) + (if (camera.logicalMultiCamera) " (Multi)" else ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (checked && camera.sizes.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = resExpanded,
                        onExpandedChange = { resExpanded = it },
                        modifier = Modifier.padding(start = 48.dp, top = 4.dp)
                    ) {
                        OutlinedTextField(
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Auflösung") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        ExposedDropdownMenu(
                            expanded = resExpanded,
                            onDismissRequest = { resExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Standard ($defaultLabel)") },
                                onClick = {
                                    settings.clearCameraResolutionOverride(camera.id)
                                    override = null
                                    resExpanded = false
                                }
                            )
                            camera.sizes.forEach { size ->
                                DropdownMenuItem(
                                    text = { Text(size.toString()) },
                                    onClick = {
                                        settings.setCameraResolutionOverride(camera.id, size.width, size.height)
                                        override = size.width to size.height
                                        resExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeTab(testModeEnabled: Boolean, onTestModeChange: (Boolean) -> Unit) {
        val settings = remember { SettingsManager(this) }
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
            cameras = withContext(Dispatchers.IO) { CameraRepository(this@MainActivity).list() }
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
                            val liveSettings = SettingsManager(this@MainActivity)
                            val resolvedCameras = PhotoCaptureHelper.resolveCameras(this@MainActivity, liveSettings)
                            if (resolvedCameras.isEmpty()) throw IllegalStateException("Keine Kamera verfügbar")
                            for (camera in resolvedCameras) {
                                val (w, h) = PhotoCaptureHelper.resolveResolution(liveSettings, camera.id)
                                PhotoCaptureHelper.captureAndSave(
                                    this@MainActivity,
                                    camera.id,
                                    w,
                                    h,
                                    liveSettings.jpegQuality,
                                    PhotoCaptureHelper.cameraLabel(camera)
                                )
                            }
                            val result = SmbUploader(this@MainActivity).uploadPendingPhotos()
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
                                AlarmScheduler(this@MainActivity).scheduleAll()
                                if (it) {
                                    ensureCameraServiceRunning()
                                    lifecycleScope.launch {
                                        try { withContext(Dispatchers.IO) { MqttClientManager(this@MainActivity).connectAndDiscover() } } catch (_: Exception) { }
                                    }
                                } else {
                                    try { startService(Intent(this@MainActivity, CameraForegroundService::class.java).setAction(CameraForegroundService.ACTION_STOP)) } catch (_: Throwable) { }
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
                            AlarmScheduler(this@MainActivity).scheduleAll()
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
                        lifecycleScope.launch {
                            val result = try { SmbUploader(this@MainActivity).uploadPendingPhotos() } catch (_: Throwable) { null }
                            uploadStatus = if (result != null) "Erfolgreich: ${result.uploaded}" else "Fehler"
                            withContext(Dispatchers.IO) {
                                try {
                                    val mqtt = MqttClientManager(this@MainActivity)
                                    if (result != null) {
                                        mqtt.publish("timelapse/${settings.deviceId}/last_upload", Instant.now().toString())
                                    }
                                    MqttDiscovery(mqtt, settings, this@MainActivity).publishState()
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

    @Composable
    private fun AlignmentGridOverlay(modifier: Modifier = Modifier) {
        val lineColor = Color.White.copy(alpha = 0.5f)
        Canvas(modifier = modifier) {
            val w = size.width; val h = size.height; val stroke = 1.dp.toPx()
            drawLine(lineColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
            drawLine(lineColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), stroke)
            drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
            drawLine(lineColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), stroke)
            drawLine(lineColor, Offset(0f, 0f), Offset(w, h), stroke)
            drawLine(lineColor, Offset(w, 0f), Offset(0f, h), stroke)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CameraTab(liveEnabled: Boolean, onLiveEnabledChange: (Boolean) -> Unit) {
        val settings = remember { SettingsManager(this) }
        val previewController = remember { CameraPreviewController(this) }
        var cameras by remember { mutableStateOf(emptyList<CameraInfo>()) }

        var selectedCameraId by remember { mutableStateOf(settings.lastPreviewCameraId) }
        var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewLoading by remember { mutableStateOf(false) }
        var textureSurface by remember { mutableStateOf<Surface?>(null) }
        var showGrid by remember { mutableStateOf(settings.showGrid) }
        var showGhost by remember { mutableStateOf(settings.showGhost) }
        var ghostOpacity by remember { mutableFloatStateOf(settings.ghostOpacity) }
        var ghostPhotoState by remember { mutableStateOf<GhostPhotoState>(GhostPhotoState.Idle) }

        LaunchedEffect(Unit) {
            cameras = withContext(Dispatchers.IO) { CameraRepository(this@MainActivity).list() }
            val lastUsed = settings.lastPreviewCameraId
            if (lastUsed.isBlank() || cameras.none { it.id == lastUsed }) {
                val bestDefault = withContext(Dispatchers.IO) {
                    cameras.firstOrNull { cam ->
                        val label = PhotoCaptureHelper.cameraLabel(cam)
                        AppDatabase.getInstance(this@MainActivity).photoDao().getLastPhotoByCameraLabel(label) != null
                    }?.id ?: cameras.firstOrNull()?.id ?: ""
                }
                selectedCameraId = bestDefault
            }
        }

        val selectedCamera = cameras.firstOrNull { it.id == selectedCameraId }
        val selectedCameraLabel = selectedCamera?.let { PhotoCaptureHelper.cameraLabel(it) }

        val hasGhostPhoto = remember(selectedCameraLabel) { mutableStateOf(false) }
        LaunchedEffect(selectedCameraLabel) {
            // Uses the shared readability check (DB entry AND the file/URI
            // actually opens) rather than a bare DB null-check, so a photo
            // that's registered but not readable (e.g. missing storage
            // permission, or the file was deleted/replaced externally)
            // correctly disables the GHOST button instead of enabling a
            // feature that then silently shows nothing.
            val exists = hasReadableGhostPhoto(this@MainActivity, selectedCameraLabel)
            hasGhostPhoto.value = exists
            if (!exists) showGhost = false
        }

        LaunchedEffect(showGrid) { settings.showGrid = showGrid }
        LaunchedEffect(showGhost) { settings.showGhost = showGhost }
        LaunchedEffect(ghostOpacity) { settings.ghostOpacity = ghostOpacity }
        LaunchedEffect(selectedCameraId) { settings.lastPreviewCameraId = selectedCameraId }

        LaunchedEffect(showGhost, selectedCameraLabel) {
            if (showGhost && hasGhostPhoto.value) {
                ghostPhotoState = GhostPhotoState.Loading
                ghostPhotoState = loadGhostPhotoState(this@MainActivity, selectedCameraLabel)
            } else {
                ghostPhotoState = GhostPhotoState.Idle
            }
        }

        val (rawWidth, rawHeight) = if (selectedCameraId.isNotBlank())
            PhotoCaptureHelper.resolveResolution(settings, selectedCameraId)
        else settings.cameraWidth to settings.cameraHeight

        val displayRotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        val deviceRotationDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        val sensorOrientation = selectedCamera?.orientation ?: 0
        val isPortrait = (sensorOrientation + deviceRotationDegrees) % 180 != 0
        
        // Use the native landscape aspect for the internal content, 
        // but the uiAspect for the container.
        val uiAspect = if (isPortrait) rawHeight.toFloat() / rawWidth else rawWidth.toFloat() / rawHeight
        
        val rotationAngle = if (selectedCamera?.facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - deviceRotationDegrees + 360) % 360
        } else {
            (sensorOrientation + deviceRotationDegrees) % 360
        }

        LaunchedEffect(selectedCameraId, liveEnabled) {
            if (!liveEnabled && selectedCameraId.isNotBlank()) {
                previewLoading = true; previewBitmap = capturePreview(selectedCameraId); previewLoading = false
            }
        }

        LaunchedEffect(liveEnabled, selectedCameraId, textureSurface) {
            val surface = textureSurface
            if (liveEnabled && surface != null && selectedCameraId.isNotBlank()) previewController.start(selectedCameraId, surface)
            else previewController.stop()
        }

        DisposableEffect(Unit) {
            onDispose { previewController.release(); textureSurface?.release(); textureSurface = null }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(uiAspect)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            liveEnabled -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                                TextureView(ctx).apply {
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                            val sizes = selectedCamera?.previewSizes ?: emptyList()
                                            val targetAspect = rawWidth.toFloat() / rawHeight
                                            val bestSize = sizes.filter { Math.abs((it.width.toFloat() / it.height) - targetAspect) < 0.01 }.firstOrNull() ?: sizes.firstOrNull()
                                            val pw = bestSize?.width ?: 1280; val ph = bestSize?.height ?: 960
                                            st.setDefaultBufferSize(pw, ph)
                                            textureSurface?.release(); textureSurface = Surface(st)
                                        }
                                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { textureSurface?.release(); textureSurface = null; return true }
                                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                    }
                                }
                            })
                            previewLoading -> CircularProgressIndicator()
                            previewBitmap != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = previewBitmap!!.asImageBitmap(), 
                                    contentDescription = "Preview", 
                                    modifier = Modifier
                                        .then(
                                            if (rotationAngle % 180 != 0) {
                                                Modifier.layout { measurable, constraints ->
                                                    val placeable = measurable.measure(constraints.copy(
                                                        minWidth = constraints.maxHeight,
                                                        maxWidth = constraints.maxHeight,
                                                        minHeight = constraints.maxWidth,
                                                        maxHeight = constraints.maxWidth
                                                    ))
                                                    layout(constraints.maxWidth, constraints.maxHeight) {
                                                        placeable.place(
                                                            x = (constraints.maxWidth - placeable.width) / 2,
                                                            y = (constraints.maxHeight - placeable.height) / 2
                                                        )
                                                    }
                                                }
                                            } else Modifier.fillMaxSize()
                                        )
                                        .graphicsLayer { rotationZ = rotationAngle.toFloat() }, 
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                            else -> Text("Kamera bereit", color = Color.Gray)
                        }
                    }
                    if (showGhost) {
                        val gs = ghostPhotoState
                        if (gs is GhostPhotoState.Loaded) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = gs.bitmap.asImageBitmap(),
                                    contentDescription = "Ghost",
                                    modifier = Modifier
                                        .alpha(ghostOpacity)
                                        .then(
                                            if (rotationAngle % 180 != 0) {
                                                Modifier.layout { measurable, constraints ->
                                                    val placeable = measurable.measure(constraints.copy(
                                                        minWidth = constraints.maxHeight,
                                                        maxWidth = constraints.maxHeight,
                                                        minHeight = constraints.maxWidth,
                                                        maxHeight = constraints.maxWidth
                                                    ))
                                                    layout(constraints.maxWidth, constraints.maxHeight) {
                                                        placeable.place(
                                                            x = (constraints.maxWidth - placeable.width) / 2,
                                                            y = (constraints.maxHeight - placeable.height) / 2
                                                        )
                                                    }
                                                }
                                            } else Modifier.fillMaxSize()
                                        )
                                        .graphicsLayer {
                                            rotationZ = rotationAngle.toFloat()
                                            if (selectedCamera?.facing == CameraCharacteristics.LENS_FACING_FRONT) {
                                                if (rotationAngle % 180 != 0) scaleY = -1f else scaleX = -1f
                                            }
                                        },
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }
                    }
                    if (showGrid) AlignmentGridOverlay(modifier = Modifier.fillMaxSize())

                    if (showGhost && ghostPhotoState is GhostPhotoState.Loaded) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp)
                                .alpha(ghostOpacity),
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Letztes Foto",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color.Yellow,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .clip(CircleShape)
                            .clickable { onLiveEnabledChange(!liveEnabled) },
                        color = if (liveEnabled) Color.Red.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Box(Modifier.size(8.dp).background(if (liveEnabled) Color.White else Color.Gray, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clip(CircleShape)
                            .clickable(enabled = hasGhostPhoto.value) { showGhost = !showGhost },
                        color = when {
                            !hasGhostPhoto.value -> Color.Gray.copy(alpha = 0.2f)
                            showGhost -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            else -> Color.Black.copy(alpha = 0.6f)
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Icon(
                                Icons.Default.Layers,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = if (hasGhostPhoto.value) Color.White else Color.Gray
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "GHOST",
                                color = if (hasGhostPhoto.value) Color.White else Color.Gray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .height(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            !hasGhostPhoto.value -> {
                                Text(
                                    "Kein Referenzfoto für diese Kamera vorhanden",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            showGhost && ghostPhotoState is GhostPhotoState.Loaded -> {
                                Slider(
                                    value = ghostOpacity,
                                    onValueChange = { ghostOpacity = it },
                                    valueRange = 0.05f..0.95f,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color.White,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                    )
                                )
                            }
                            showGhost && ghostPhotoState is GhostPhotoState.Loading -> {
                                Text("Lade Foto …", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            showGhost && ghostPhotoState is GhostPhotoState.LoadFailed -> {
                                Text(
                                    "Referenzfoto konnte nicht geladen werden (fehlende Berechtigung?)",
                                    color = Color(0xFFFF8A80),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            else -> {
                                Text(
                                    "${facingLabel(selectedCamera?.facing ?: -1)} Kamera ${selectedCameraId}",
                                    color = Color.DarkGray,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            if (!liveEnabled && !previewLoading && selectedCameraId.isNotBlank()) {
                item {
                    Button(
                        onClick = { lifecycleScope.launch { previewLoading = true; previewBitmap = capturePreview(selectedCameraId); previewLoading = false } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Vorschau aktualisieren")
                    }
                }
            }

            item {
                SectionHeader("Hilfsmittel", Icons.Default.Handyman)
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Raster einblenden", modifier = Modifier.weight(1f))
                            Switch(checked = showGrid, onCheckedChange = { showGrid = it })
                        }

                        if (showGhost) {
                            HorizontalDivider(modifier = Modifier.alpha(0.3f))
                            TextButton(
                                onClick = { lifecycleScope.launch { ghostPhotoState = GhostPhotoState.Loading; ghostPhotoState = loadGhostPhotoState(this@MainActivity, selectedCameraLabel) } },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Icon(Icons.Default.Cached, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Foto neu laden")
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Kamera wählen", Icons.Default.Cameraswitch) }

            items(cameras, key = { "tab_select_${it.id}" }) { camera ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selectedCameraId = camera.id
                        settings.lastPreviewCameraId = camera.id
                    },
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (selectedCameraId == camera.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedCameraId == camera.id, onClick = {
                            selectedCameraId = camera.id
                            settings.lastPreviewCameraId = camera.id
                        })
                        Column {
                            Text("Kamera ${camera.id}", fontWeight = FontWeight.Bold)
                            Text(facingLabel(camera.facing), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsTab() {
        val settings = remember { SettingsManager(this) }
        val secrets = remember { SecureSecrets(this) }
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

        LaunchedEffect(Unit) { cameras = withContext(Dispatchers.IO) { CameraRepository(this@MainActivity).list() } }

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
                val selectedCamera = cameras.firstOrNull { it.id == settings.cameraId } ?: cameras.firstOrNull()
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
                                    AlarmScheduler(this@MainActivity).scheduleAll()
                                }
                            )
                        }

                        Button(
                            onClick = {
                                TimePickerDialog(this@MainActivity, { _, h, m ->
                                    smbUploadHour = h; smbUploadMinute = m
                                    settings.smbUploadHour = h; settings.smbUploadMinute = m
                                    AlarmScheduler(this@MainActivity).scheduleUpload()
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
                                lifecycleScope.launch {
                                    val r = SmbUploader(this@MainActivity).testConnection()
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
                                lifecycleScope.launch {
                                    try { withContext(Dispatchers.IO) { MqttClientManager(this@MainActivity).connectAndDiscover() }; discoveryStatus = "OK" }
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
                val powerManager = remember { getSystemService(PowerManager::class.java) }
                val ignoringOpt = remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(packageName)) }

                Button(
                    onClick = { requestIgnoreBatteryOptimizations() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (ignoringOpt.value) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (ignoringOpt.value) "Akku-Optimierung: AUS" else "Optimierung deaktivieren!", color = if (ignoringOpt.value) MaterialTheme.colorScheme.onSurfaceVariant else Color.White)
                }
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    }
}

package de.example.timelapse.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import de.example.timelapse.SettingsManager
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.camera.CameraPreviewController
import de.example.timelapse.camera.CameraRepository
import de.example.timelapse.camera.PhotoCaptureHelper
import de.example.timelapse.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraTab(liveEnabled: Boolean, onLiveEnabledChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    val previewController = remember { CameraPreviewController(context) }
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
        cameras = withContext(Dispatchers.IO) { CameraRepository(context).list() }
        val lastUsed = settings.lastPreviewCameraId
        if (lastUsed.isBlank() || cameras.none { it.id == lastUsed }) {
            val bestDefault = withContext(Dispatchers.IO) {
                cameras.firstOrNull { cam ->
                    val label = PhotoCaptureHelper.cameraLabel(cam)
                    AppDatabase.getInstance(context).photoDao().getLastPhotoByCameraLabel(label) != null
                }?.id ?: cameras.firstOrNull()?.id ?: ""
            }
            selectedCameraId = bestDefault
        }
    }

    val selectedCamera = cameras.firstOrNull { it.id == selectedCameraId }
    val selectedCameraLabel = selectedCamera?.let { PhotoCaptureHelper.cameraLabel(it) }

    val hasGhostPhoto = remember(selectedCameraLabel) { mutableStateOf(false) }
    LaunchedEffect(selectedCameraLabel) {
        val exists = hasReadableGhostPhoto(context, selectedCameraLabel)
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
            ghostPhotoState = loadGhostPhotoState(context, selectedCameraLabel)
        } else {
            ghostPhotoState = GhostPhotoState.Idle
        }
    }

    val (rawWidth, rawHeight) = if (selectedCameraId.isNotBlank())
        PhotoCaptureHelper.resolveResolution(settings, selectedCameraId)
    else settings.cameraWidth to settings.cameraHeight

    // We need the display rotation to correctly orient the preview
    val display = if (Build.VERSION.SDK_INT >= 30) {
        context.display
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
    
    val displayRotation = display?.rotation ?: Surface.ROTATION_0
    val deviceRotationDegrees = when (displayRotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }
    val sensorOrientation = selectedCamera?.orientation ?: 0
    val isPortrait = (sensorOrientation + deviceRotationDegrees) % 180 != 0
    
    val uiAspect = if (isPortrait) rawHeight.toFloat() / rawWidth else rawWidth.toFloat() / rawHeight
    
    val rotationAngle = if (selectedCamera?.facing == CameraCharacteristics.LENS_FACING_FRONT) {
        (sensorOrientation - deviceRotationDegrees + 360) % 360
    } else {
        (sensorOrientation + deviceRotationDegrees) % 360
    }

    suspend fun refreshPreview() {
        if (selectedCameraId.isNotBlank()) {
            previewLoading = true
            val file = PhotoCaptureHelper.capturePreview(context, selectedCameraId)
            previewBitmap = file?.let { decodeOrientedBitmap(it.absolutePath) }
            file?.delete()
            previewLoading = false
        }
    }

    LaunchedEffect(selectedCameraId, liveEnabled) {
        if (!liveEnabled) refreshPreview()
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
                    onClick = { scope.launch { refreshPreview() } },
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
                            onClick = { scope.launch { ghostPhotoState = GhostPhotoState.Loading; ghostPhotoState = loadGhostPhotoState(context, selectedCameraLabel) } },
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

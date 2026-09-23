package de.example.timelapse.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import android.net.Uri
import de.example.timelapse.data.PhotoEntity
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import de.example.timelapse.R
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
fun CameraTab(
    liveEnabled: Boolean, 
    onLiveEnabledChange: (Boolean) -> Unit,
    showGhost: Boolean,
    onShowGhostChange: (Boolean) -> Unit
) {
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
    var ghostMode by remember { mutableIntStateOf(settings.ghostMode) }
    var ghostOpacity by remember { mutableFloatStateOf(settings.ghostOpacity) }
    var ghostOscillationEnabled by remember { mutableStateOf(settings.ghostOscillationEnabled) }
    var ghostOscillationRange by remember { mutableStateOf(settings.ghostOscillationMin..settings.ghostOscillationMax) }
    var ghostPhotoState by remember { mutableStateOf<GhostPhotoState>(GhostPhotoState.Idle) }

    var showGhostSelector by remember { mutableStateOf(false) }
    var recentPhotos by remember { mutableStateOf(emptyList<PhotoEntity>()) }
    var loadingRecent by remember { mutableStateOf(false) }

    // Use a VSync-synced metronome for stable animation on older Android versions.
    var metronomeNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ghostOscillationEnabled, ghostMode) {
        if (ghostOscillationEnabled || ghostMode == 2) {
            val startNanos = System.nanoTime()
            while (true) {
                withFrameNanos { metronomeNanos = it - startNanos }
            }
        }
    }

    LaunchedEffect(Unit) {
        cameras = withContext(Dispatchers.IO) { CameraRepository(context).list() }
        syncExistingPhotosFromStorage(context)
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

    var currentPinnedId by remember(selectedCameraLabel, showGhostSelector) {
        mutableLongStateOf(selectedCameraLabel?.let { settings.getPinnedGhostPhotoId(it) } ?: -1L)
    }

    val hasGhostPhoto = remember(selectedCameraLabel) { mutableStateOf(false) }

    LaunchedEffect(showGhost, selectedCameraLabel, currentPinnedId) {
        if (showGhost && selectedCameraLabel != null) {
            val dao = AppDatabase.getInstance(context).photoDao()
            dao.observeLastPhotoByCameraLabel(selectedCameraLabel).collect {
                currentPinnedId = settings.getPinnedGhostPhotoId(selectedCameraLabel)
                val exists = hasReadableGhostPhoto(context, selectedCameraLabel)
                hasGhostPhoto.value = exists
                if (exists) {
                    ghostPhotoState = GhostPhotoState.Loading
                    ghostPhotoState = loadGhostPhotoState(context, selectedCameraLabel)
                } else {
                    ghostPhotoState = GhostPhotoState.NoPhoto
                }
            }
        } else if (selectedCameraLabel != null) {
            currentPinnedId = settings.getPinnedGhostPhotoId(selectedCameraLabel)
            val exists = hasReadableGhostPhoto(context, selectedCameraLabel)
            hasGhostPhoto.value = exists
            ghostPhotoState = GhostPhotoState.Idle
        } else {
            ghostPhotoState = GhostPhotoState.Idle
        }
    }

    LaunchedEffect(showGhostSelector, selectedCameraLabel) {
        if (showGhostSelector && selectedCameraLabel != null) {
            loadingRecent = true
            recentPhotos = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(context).photoDao().getRecentPhotosByCameraLabel(selectedCameraLabel, 50)
            }
            loadingRecent = false
            scope.launch(Dispatchers.IO) {
                syncExistingPhotosFromStorage(context)
                recentPhotos = AppDatabase.getInstance(context).photoDao().getRecentPhotosByCameraLabel(selectedCameraLabel, 50)
            }
        }
    }

    LaunchedEffect(showGrid) { settings.showGrid = showGrid }
    LaunchedEffect(showGhost) { settings.showGhost = showGhost }
    LaunchedEffect(ghostMode) { settings.ghostMode = ghostMode }
    LaunchedEffect(ghostOpacity) { /* Saved onValueChangeFinished in Slider */ }
    LaunchedEffect(ghostOscillationEnabled) { settings.ghostOscillationEnabled = ghostOscillationEnabled }
    LaunchedEffect(selectedCameraId) { settings.lastPreviewCameraId = selectedCameraId }

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
                                contentScale = ContentScale.Fit
                            )
                        }
                        else -> Text(stringResource(R.string.camera_ready), color = Color.Gray)
                    }
                }
                if (showGhost) {
                    val gs = ghostPhotoState
                    if (gs is GhostPhotoState.Loaded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val bitmapToDraw = if (ghostMode == 3 && gs.edgeBitmap != null) gs.edgeBitmap else gs.bitmap
                            Image(
                                bitmap = bitmapToDraw.asImageBitmap(),
                                contentDescription = "Ghost",
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
                                    .graphicsLayer {
                                        // Consolidated graphics layer for better stability on Android 9
                                        rotationZ = rotationAngle.toFloat()
                                        if (selectedCamera?.facing == CameraCharacteristics.LENS_FACING_FRONT) {
                                            if (rotationAngle % 180 != 0) scaleY = -1f else scaleX = -1f
                                        }

                                        blendMode = when (ghostMode) {
                                            1 -> BlendMode.Difference
                                            else -> BlendMode.SrcOver
                                        }

                                        alpha = when {
                                            ghostMode == 2 -> {
                                                // Blink: 1s cycle (500ms on, 500ms off)
                                                if ((metronomeNanos / 500_000_000L) % 2 == 0L) 1f else 0f
                                            }
                                            ghostMode == 1 || ghostMode == 3 -> 1f // Difference/Edge mode works best at 100% alpha
                                            ghostOscillationEnabled -> {
                                                // 3000ms full cycle (1500ms each way)
                                                val period = 3000_000_000L
                                                val progress = (metronomeNanos % period).toFloat() / period
                                                val wave = if (progress < 0.5f) progress * 2f else (1f - progress) * 2f
                                                // Apply easing manually for smoother turns
                                                val eased = if (wave < 0.5f) 2f * wave * wave else 1f - (-2f * wave + 2f).let { it * it } / 2f
                                                ghostOscillationRange.start + (ghostOscillationRange.endInclusive - ghostOscillationRange.start) * eased
                                            }
                                            else -> ghostOpacity
                                        }
                                    },
                                contentScale = ContentScale.Fit
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
                            .graphicsLayer {
                                alpha = when {
                                    ghostMode == 2 -> if ((metronomeNanos / 500_000_000L) % 2 == 0L) 1f else 0f
                                    ghostMode == 1 || ghostMode == 3 -> 1f
                                    ghostOscillationEnabled -> {
                                        val period = 3000_000_000L
                                        val progress = (metronomeNanos % period).toFloat() / period
                                        val wave = if (progress < 0.5f) progress * 2f else (1f - progress) * 2f
                                        val eased = if (wave < 0.5f) 2f * wave * wave else 1f - (-2f * wave + 2f).let { it * it } / 2f
                                        ghostOscillationRange.start + (ghostOscillationRange.endInclusive - ghostOscillationRange.start) * eased
                                    }
                                    else -> ghostOpacity
                                }
                            },
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            if (currentPinnedId != -1L) stringResource(R.string.pinned_indicator) else stringResource(R.string.last_photo),
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
                        .clickable(enabled = hasGhostPhoto.value) { onShowGhostChange(!showGhost) },
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
                        .height(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        !hasGhostPhoto.value -> {
                            Text(
                                stringResource(R.string.no_reference_photo),
                                color = Color.Gray,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        showGhost && ghostPhotoState is GhostPhotoState.Loaded -> {
                            Slider(
                                value = ghostOpacity,
                                onValueChange = { ghostOpacity = it },
                                onValueChangeFinished = { settings.ghostOpacity = ghostOpacity },
                                enabled = ghostMode == 0 && !ghostOscillationEnabled,
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
                            Text(stringResource(R.string.loading_photo), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        showGhost && ghostPhotoState is GhostPhotoState.LoadFailed -> {
                            Text(
                                stringResource(R.string.load_failed_ghost),
                                color = Color(0xFFFF8A80),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        else -> {
                            Text(
                                "${facingLabel(context, selectedCamera?.facing ?: -1)} Kamera ${selectedCameraId}",
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
                    Text(stringResource(R.string.refresh_preview))
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.tools), Icons.Default.Handyman)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.show_grid), modifier = Modifier.weight(1f))
                        Switch(checked = showGrid, onCheckedChange = { showGrid = it })
                    }

                    if (showGhost) {
                        HorizontalDivider(modifier = Modifier.alpha(0.3f))
                        Text(stringResource(R.string.ghost_mode), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val modes = listOf(
                                stringResource(R.string.ghost_mode_normal),
                                stringResource(R.string.ghost_mode_difference),
                                stringResource(R.string.ghost_mode_blink),
                                stringResource(R.string.ghost_mode_edges)
                            )
                            val isDifferenceSupported = Build.VERSION.SDK_INT >= 29
                            
                            modes.forEachIndexed { index, label ->
                                val enabled = if (index == 1) isDifferenceSupported else true
                                SegmentedButton(
                                    selected = ghostMode == index,
                                    onClick = { ghostMode = index },
                                    enabled = enabled,
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                                ) {
                                    Text(label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (Build.VERSION.SDK_INT < 29) {
                            Text(
                                stringResource(R.string.difference_mode_requirement),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.alpha(0.3f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.auto_oscillation), modifier = Modifier.weight(1f))
                            Switch(
                                checked = ghostOscillationEnabled, 
                                onCheckedChange = { ghostOscillationEnabled = it },
                                enabled = ghostMode != 2 // Disable oscillation in blink mode
                            )
                        }
                        if (ghostOscillationEnabled) {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    stringResource(R.string.oscillation_range, (ghostOscillationRange.start * 100).toInt(), (ghostOscillationRange.endInclusive * 100).toInt()),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                RangeSlider(
                                    value = ghostOscillationRange,
                                    onValueChange = { ghostOscillationRange = it },
                                    onValueChangeFinished = {
                                        settings.ghostOscillationMin = ghostOscillationRange.start
                                        settings.ghostOscillationMax = ghostOscillationRange.endInclusive
                                    },
                                    valueRange = 0.05f..0.95f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        TextButton(
                            onClick = { showGhostSelector = true },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.ImageSearch, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.change_reference))
                        }

                        TextButton(
                            onClick = { scope.launch { ghostPhotoState = GhostPhotoState.Loading; ghostPhotoState = loadGhostPhotoState(context, selectedCameraLabel) } },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Cached, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.reload_photo))
                        }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.select_camera), Icons.Default.Cameraswitch) }

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
                        Text(stringResource(R.string.camera_label, camera.id), fontWeight = FontWeight.Bold)
                        Text(facingLabel(context, camera.facing), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showGhostSelector) {
        ModalBottomSheet(
            onDismissRequest = { showGhostSelector = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.select_reference_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = { showGhostSelector = false }) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        selectedCameraLabel?.let { settings.setPinnedGhostPhotoId(it, -1L) }
                        currentPinnedId = -1L
                        showGhostSelector = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = currentPinnedId != -1L
                ) {
                    Icon(Icons.Default.History, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.use_latest))
                }

                Spacer(Modifier.height(16.dp))

                if (loadingRecent) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (recentPhotos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_reference_photo), color = Color.Gray)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(100.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(recentPhotos, key = { it.id }) { photo ->
                            val isPinned = photo.id == currentPinnedId
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isPinned) MaterialTheme.colorScheme.primary else Color.DarkGray)
                                    .clickable {
                                        selectedCameraLabel?.let { settings.setPinnedGhostPhotoId(it, photo.id) }
                                        currentPinnedId = photo.id
                                        showGhostSelector = false
                                    }
                            ) {
                                // Simple thumbnail loader using our existing helper
                                var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
                                LaunchedEffect(photo.localPath) {
                                    thumbnail = withContext(Dispatchers.IO) {
                                        val validUri = resolveValidPhotoUri(context, photo) ?: Uri.parse(photo.localPath)
                                        decodeOrientedBitmap(context, validUri, targetSize = 300)
                                    }
                                }

                                if (thumbnail != null) {
                                    Image(
                                        bitmap = thumbnail!!.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().alpha(if (isPinned) 0.6f else 1f),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Image, null, tint = Color.Gray)
                                    }
                                }

                                if (isPinned) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        null,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package de.example.timelapse.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import de.example.timelapse.SettingsManager
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class GhostPhotoState {
    object Idle : GhostPhotoState()
    object Loading : GhostPhotoState()
    object NoPhoto : GhostPhotoState()
    object LoadFailed : GhostPhotoState()
    data class Loaded(val bitmap: Bitmap) : GhostPhotoState()
}

suspend fun loadGhostPhotoState(context: Context, cameraLabel: String?): GhostPhotoState =
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
 * actually openable right now.
 */
suspend fun hasReadableGhostPhoto(context: Context, cameraLabel: String?): Boolean =
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

fun exifRotationDegrees(exif: ExifInterface): Int =
    when (exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

fun rotateBitmapIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun decodeOrientedBitmap(path: String): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(path) ?: return null
    val degrees = try {
        exifRotationDegrees(ExifInterface(path))
    } catch (_: Throwable) {
        0
    }
    return rotateBitmapIfNeeded(bitmap, degrees)
}

fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? {
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

fun facingLabel(facing: Int): String = when (facing) {
    0 -> "Front"
    1 -> "Back"
    2 -> "External"
    else -> "Unbekannt"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSelectionRow(
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

@Composable
fun SectionHeader(title: String, icon: ImageVector) {
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

@Composable
fun AlignmentGridOverlay(modifier: Modifier = Modifier) {
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

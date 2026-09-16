package de.example.timelapse.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import de.example.timelapse.SettingsManager
import de.example.timelapse.data.AppDatabase
import de.example.timelapse.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures a still photo with [cameraId] at [width]x[height]/[jpegQuality],
 * stores it under Pictures/Timelapse/<date>/, and records it in the local
 * Room database as a pending upload. Used by both the scheduled
 * [de.example.timelapse.service.CameraForegroundService] and the manual
 * test mode in [de.example.timelapse.MainActivity] so both paths behave
 * identically.
 *
 * Storage: on API 29+ (Android 10+), uses MediaStore's scoped-storage APIs
 * (RELATIVE_PATH/IS_PENDING) - no storage permission needed since the app
 * only touches media it created itself. Below API 29 (down to the app's
 * minSdk 26, i.e. Android 8.0/8.1/9), scoped storage doesn't exist yet: the
 * file is written directly under the public Pictures directory instead,
 * which requires WRITE_EXTERNAL_STORAGE (declared in the manifest with
 * maxSdkVersion=28, requested at runtime by MainActivity on those OS
 * versions only) and is then registered with the media scanner so it (a)
 * shows up in gallery apps and (b) gets a proper content:// URI - the rest
 * of the app (SmbUploader's delete-after-upload, the camera tab's
 * ghost-overlay reader) already assumes a content:// URI everywhere, and a
 * bare file:// URI wouldn't support ContentResolver.delete().
 *
 * Filename scheme: "<cameraLabel>_<yyMMdd>-<seq>.jpg", e.g. "B0_260915-0000.jpg".
 * - cameraLabel is a one-letter facing code (F/B/E/C) plus the raw Camera2
 *   ID, e.g. "B0" for the back-facing camera with ID "0". This keeps
 *   multiple cameras' output distinguishable even though they all write
 *   into the same date folder (no per-camera subfolders) and even if all
 *   files ever end up copied into one flat directory.
 * - seq is a zero-padded 4-digit sequence number, counted separately per
 *   camera label and reset to 0000 the first time that camera captures on
 *   a new calendar day. This makes capture order within a day unambiguous
 *   without needing a clock-time field in the filename at all. Four digits
 *   comfortably covers even a 1-minute capture interval run for a full day
 *   (1440 shots) without wrapping around.
 */
object PhotoCaptureHelper {
    private const val COUNTER_PREFS = "capture_counters"

    /** One-letter facing code used as the first character of a camera label. */
    private fun facingCode(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "F"
        CameraCharacteristics.LENS_FACING_BACK -> "B"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "E"
        else -> "C"
    }

    /**
     * Short, stable label like "B0" (back-facing, Camera2 ID "0") used in
     * filenames, derived directly from an already-resolved [CameraInfo].
     * Prefer this overload whenever the caller already has the camera list
     * (e.g. from [resolveCameras]) to avoid a redundant camera enumeration -
     * each [CameraRepository.list] call re-queries every camera's
     * characteristics from the camera service.
     */
    fun cameraLabel(camera: CameraInfo): String = "${facingCode(camera.facing)}${camera.id}"

    /**
     * Convenience overload for callers that only have a camera ID and don't
     * already have the camera list at hand. Re-enumerates all cameras to
     * find the matching facing - prefer [cameraLabel] with a [CameraInfo]
     * when possible.
     */
    suspend fun cameraLabel(context: Context, cameraId: String): String {
        val facing = CameraRepository(context).list().firstOrNull { it.id == cameraId }?.facing
        return "${facingCode(facing)}$cameraId"
    }

    /**
     * Resolves the effective capture resolution for [cameraId]: its own
     * override if one was set (see [SettingsManager.cameraResolutionOverride]),
     * otherwise the global default resolution.
     */
    fun resolveResolution(settings: SettingsManager, cameraId: String): Pair<Int, Int> =
        settings.cameraResolutionOverride(cameraId) ?: (settings.cameraWidth to settings.cameraHeight)

    /**
     * Returns the next zero-padded 4-digit sequence number for [label] on
     * [dateKey] ("yyMMdd"), persisted in SharedPreferences so it survives
     * app/service restarts. Automatically resets to 0 the moment the stored
     * date for this camera no longer matches [dateKey], i.e. on the first
     * capture of a new day. Each camera label keeps its own independent
     * counter, so e.g. front and back cameras both start at 0000 on a new
     * day rather than sharing one running total.
     *
     * Not designed for concurrent calls for the *same* label from multiple
     * threads at once - this app only ever captures sequentially (one
     * camera at a time, awaited before the next), so that's not an issue
     * here.
     */
    private fun nextSequence(context: Context, label: String, dateKey: String): Int {
        val prefs = context.getSharedPreferences(COUNTER_PREFS, Context.MODE_PRIVATE)
        val lastDate = prefs.getString("${label}_date", null)
        val next = if (lastDate == dateKey) prefs.getInt("${label}_counter", 0) + 1 else 0
        prefs.edit().putString("${label}_date", dateKey).putInt("${label}_counter", next).apply()
        return next
    }

    suspend fun captureAndSave(
        context: Context,
        cameraId: String,
        width: Int,
        height: Int,
        jpegQuality: Int,
        /**
         * Pass this when the caller already resolved the camera list (e.g.
         * via [resolveCameras]) to avoid a redundant camera enumeration just
         * to figure out the label. If null, it's looked up from [cameraId]
         * the more expensive way.
         */
        precomputedLabel: String? = null
    ): PhotoEntity {
        val now = Date()
        // Used for the Pictures/Timelapse/<date>/ folder - kept as a full,
        // unambiguous date independent of the filename's shorter yyMMdd form.
        val folderDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        // Used for the filename and as the day-boundary key for the
        // per-camera sequence counter.
        val dateKeyFormat = SimpleDateFormat("yyMMdd", Locale.US)

        val folderDate = folderDateFormat.format(now)
        val dateKey = dateKeyFormat.format(now)
        val label = precomputedLabel ?: cameraLabel(context, cameraId)
        val sequence = nextSequence(context, label, dateKey)
        val fileName = "${label}_${dateKey}-${"%04d".format(sequence)}.jpg"

        val temp = File.createTempFile("capture-", ".jpg", context.cacheDir)
        // Single-use per capture - must be closed afterwards or its
        // background thread leaks for the rest of the process lifetime.
        val camera = Camera2Capture(context)
        try {
            camera.capture(cameraId, width, height, jpegQuality, temp)

            val uri = if (Build.VERSION.SDK_INT >= 29) {
                saveViaScopedStorage(context, temp, fileName, folderDate)
            } else {
                saveViaLegacyStorage(context, temp, fileName, folderDate)
            }

            val entity = PhotoEntity(
                localPath = uri.toString(),
                fileName = fileName,
                capturedAt = System.currentTimeMillis()
            )
            val id = AppDatabase.getInstance(context).photoDao().insert(entity)
            return entity.copy(id = id)
        } finally {
            camera.close()
            temp.delete()
        }
    }

    /** API 29+: MediaStore scoped storage, no storage permission needed. */
    private suspend fun saveViaScopedStorage(
        context: Context,
        temp: File,
        fileName: String,
        folderDate: String
    ): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Timelapse/" + folderDate)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { input -> input.copyTo(out, 65536) }
            } ?: throw IllegalStateException("Cannot open MediaStore output")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    /**
     * API 26-28 (pre-scoped-storage): writes directly into the public
     * Pictures directory (requires WRITE_EXTERNAL_STORAGE, see class doc),
     * then hands it to the media scanner. The scan's callback provides a
     * content:// URI equivalent to what MediaStore.insert() would give on
     * newer APIs, so every other part of the app can treat both storage
     * paths' results identically without caring which one ran.
     */
    @Suppress("DEPRECATION")
    private suspend fun saveViaLegacyStorage(
        context: Context,
        temp: File,
        fileName: String,
        folderDate: String
    ): Uri = withContext(Dispatchers.IO) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Timelapse/$folderDate"
        )
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw IllegalStateException("Konnte Zielverzeichnis nicht anlegen: $dir")
        }
        val outFile = File(dir, fileName)
        temp.inputStream().use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output, 65536) }
        }
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outFile.absolutePath),
                arrayOf("image/jpeg")
            ) { _, scannedUri ->
                // Falls back to a plain file:// URI if the scan somehow
                // doesn't report one back (rare, but better than crashing
                // the whole capture over a cosmetic gallery-indexing step).
                // ContentResolver can still read a file:// URI directly,
                // just not delete() it - a corner case only relevant to
                // "nach Upload löschen" combined with this fallback.
                if (cont.isActive) cont.resume(scannedUri ?: Uri.fromFile(outFile))
            }
        }
    }

    /**
     * Resolves which camera(s) a scheduled capture should use: the
     * intersection of [SettingsManager.selectedCameraIds] with the cameras
     * actually detected on the device right now (so a camera removed since
     * the selection was made just silently drops out, rather than causing
     * an error). If that intersection is empty - either nothing was ever
     * selected, or every previously selected camera is gone - falls back to
     * the first detected camera so a fresh install / reconfigured device
     * still captures something rather than nothing.
     *
     * Returns full [CameraInfo] (not just IDs) so callers can pass the
     * facing straight into [cameraLabel] without re-enumerating cameras a
     * second time per camera.
     */
    suspend fun resolveCameras(context: Context, settings: SettingsManager): List<CameraInfo> {
        val cameras = CameraRepository(context).list()
        val selected = settings.selectedCameraIds
        val matched = cameras.filter { it.id in selected }
        return matched.ifEmpty { listOfNotNull(cameras.firstOrNull()) }
    }
}
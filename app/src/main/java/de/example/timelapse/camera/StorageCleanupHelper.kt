package de.example.timelapse.camera

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object StorageCleanupHelper {
    private const val TAG = "StorageCleanup"

    /**
     * Deletes empty folders in Pictures/Timelapse that are older than the day before yesterday.
     */
    fun cleanOldEmptyFolders(context: Context) {
        try {
            val root = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Timelapse"
            )
            if (!root.exists() || !root.isDirectory) return

            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -2)
            val thresholdDate = calendar.time

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            root.listFiles()?.forEach { folder ->
                if (folder.isDirectory) {
                    try {
                        val folderDate = dateFormat.parse(folder.name)
                        if (folderDate != null && folderDate.before(thresholdDate)) {
                            // Check if empty
                            val files = folder.listFiles()
                            if (files == null || files.isEmpty()) {
                                if (folder.delete()) {
                                    Log.d(TAG, "Deleted old empty folder: ${folder.name}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Not a date folder or other error
                        Log.v(TAG, "Skipping non-date folder: ${folder.name}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Cleanup failed", t)
        }
    }
}

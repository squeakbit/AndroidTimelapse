package de.example.timelapse.data
import androidx.room.*
@Dao interface PhotoDao {
 @Insert suspend fun insert(photo:PhotoEntity):Long
 @Query("SELECT * FROM photos WHERE uploadedAt IS NULL ORDER BY capturedAt ASC") suspend fun getPendingPhotos():List<PhotoEntity>
 @Query("SELECT COUNT(*) FROM photos WHERE uploadedAt IS NULL") suspend fun getPendingCount():Int
 @Query("SELECT COUNT(*) FROM photos WHERE uploadedAt IS NOT NULL") suspend fun getUploadedCount():Int
 @Query("SELECT * FROM photos ORDER BY capturedAt DESC LIMIT 1") suspend fun getLastPhoto():PhotoEntity?
 /**
  * Last photo taken by a specific camera, identified via the camera-label
  * prefix baked into every filename (see PhotoCaptureHelper's filename
  * scheme "<label>_<yyMMdd>-<seq>.jpg") rather than a dedicated column -
  * this way no schema migration is needed just to filter by camera.
  * The underscore right after [label] is escaped since '_' is itself a
  * SQL LIKE wildcard (matches any single character) and would otherwise
  * also match unrelated filenames that merely start with the same label.
  */
 @Query("SELECT * FROM photos WHERE fileName LIKE :label || '\\_%' ESCAPE '\\' ORDER BY capturedAt DESC LIMIT 1")
 suspend fun getLastPhotoByCameraLabel(label:String):PhotoEntity?
 @Query("SELECT * FROM photos WHERE uploadedAt IS NOT NULL")
 suspend fun getAllUploadedPhotos():List<PhotoEntity>
 @Update suspend fun update(photo:PhotoEntity)
 @Delete suspend fun delete(photo:PhotoEntity)
}
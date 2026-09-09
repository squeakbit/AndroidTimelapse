package de.example.timelapse

import android.content.Context
import android.provider.Settings
import java.util.UUID

class SettingsManager(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun getId(): String {
        val current = p.getString("device_id", null)
        if (current != null) return current
        val id = UUID.randomUUID().toString()
        p.edit().putString("device_id", id).apply()
        return id
    }
    var deviceId: String
        get() = getId()
        set(v) { p.edit().putString("device_id", v).apply() }
    var deviceName: String
        get() = p.getString("device_name", "Android Timelapse") ?: "Android Timelapse"
        set(v) = p.edit().putString("device_name", v).apply()
    var timelapseEnabled: Boolean
        get() = p.getBoolean("timelapse_enabled", false)
        set(v) = p.edit().putBoolean("timelapse_enabled", v).apply()
    var captureIntervalMinutes: Int
        get() = p.getInt("capture_interval_minutes", 5)
        set(v) = p.edit().putInt("capture_interval_minutes", v.coerceIn(1, 1440)).apply()
    var cameraId: String
        get() = p.getString("camera_id", "") ?: ""
        set(v) = p.edit().putString("camera_id", v).apply()
    var cameraWidth: Int
        get() = p.getInt("camera_width", 1920)
        set(v) = p.edit().putInt("camera_width", v).apply()
    var cameraHeight: Int
        get() = p.getInt("camera_height", 1080)
        set(v) = p.edit().putInt("camera_height", v).apply()
    var jpegQuality: Int
        get() = p.getInt("jpeg_quality", 90)
        set(v) = p.edit().putInt("jpeg_quality", v.coerceIn(1, 100)).apply()
    var smbUploadEnabled: Boolean
        get() = p.getBoolean("smb_upload_enabled", true)
        set(v) = p.edit().putBoolean("smb_upload_enabled", v).apply()
    var smbUploadHour: Int
        get() = p.getInt("smb_upload_hour", 3)
        set(v) = p.edit().putInt("smb_upload_hour", v.coerceIn(0, 23)).apply()
    var smbUploadMinute: Int
        get() = p.getInt("smb_upload_minute", 0)
        set(v) = p.edit().putInt("smb_upload_minute", v.coerceIn(0, 59)).apply()
    var deleteAfterUpload: Boolean
        get() = p.getBoolean("delete_after_upload", false)
        set(v) = p.edit().putBoolean("delete_after_upload", v).apply()
    var mqttHost: String
        get() = p.getString("mqtt_host", "") ?: ""
        set(v) = p.edit().putString("mqtt_host", v).apply()
    var mqttPort: Int
        get() = p.getInt("mqtt_port", 1883)
        set(v) = p.edit().putInt("mqtt_port", v.coerceIn(1, 65535)).apply()
    var mqttTls: Boolean
        get() = p.getBoolean("mqtt_tls", false)
        set(v) = p.edit().putBoolean("mqtt_tls", v).apply()
    var mqttUsername: String
        get() = p.getString("mqtt_username", "") ?: ""
        set(v) = p.edit().putString("mqtt_username", v).apply()
    var mqttPassword: String
        get() = p.getString("mqtt_password", "") ?: ""
        set(v) = p.edit().putString("mqtt_password", v).apply()
    var smbHost: String
        get() = p.getString("smb_host", "") ?: ""
        set(v) = p.edit().putString("smb_host", v).apply()
    var smbShare: String
        get() = p.getString("smb_share", "") ?: ""
        set(v) = p.edit().putString("smb_share", v).apply()
    var smbUsername: String
        get() = p.getString("smb_username", "") ?: ""
        set(v) = p.edit().putString("smb_username", v).apply()
    var smbPassword: String
        get() = p.getString("smb_password", "") ?: ""
        set(v) = p.edit().putString("smb_password", v).apply()
    var smbDomain: String
        get() = p.getString("smb_domain", "") ?: ""
        set(v) = p.edit().putString("smb_domain", v).apply()
    var smbRemoteDirectory: String
        get() = p.getString("smb_remote_directory", "Timelapse") ?: "Timelapse"
        set(v) = p.edit().putString("smb_remote_directory", v).apply()
    var timeWindowEnabled: Boolean
        get() = p.getBoolean("time_window_enabled", false)
        set(v) = p.edit().putBoolean("time_window_enabled", v).apply()
    var windowStartHour: Int
        get() = p.getInt("window_start_hour", 18)
        set(v) = p.edit().putInt("window_start_hour", v.coerceIn(0, 23)).apply()
    var windowStartMinute: Int
        get() = p.getInt("window_start_minute", 0)
        set(v) = p.edit().putInt("window_start_minute", v.coerceIn(0, 59)).apply()
    var windowEndHour: Int
        get() = p.getInt("window_end_hour", 6)
        set(v) = p.edit().putInt("window_end_hour", v.coerceIn(0, 23)).apply()
    var windowEndMinute: Int
        get() = p.getInt("window_end_minute", 0)
        set(v) = p.edit().putInt("window_end_minute", v.coerceIn(0, 59)).apply()
    /** Wall-clock time (epoch ms) of the last capture attempt (success or failure), used by
     *  CameraForegroundService's internal loop to know when the next one is due, and also
     *  shown in the UI as a general "still alive" indicator (there is no separate heartbeat). */
    var lastCaptureAt: Long
        get() = p.getLong("last_capture_at", 0L)
        set(v) = p.edit().putLong("last_capture_at", v).apply()
    val mqttClientId: String
        get() = "timelapse-$deviceId"
}
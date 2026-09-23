package de.example.timelapse.service

import android.Manifest
import android.R
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import de.example.timelapse.*
import de.example.timelapse.camera.PhotoCaptureHelper
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.mqtt.MqttDiscovery
import de.example.timelapse.smb.SmbUploader
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import java.util.Calendar

/**
 * Since Android 14, a foreground service of type "camera" cannot be
 * started while the app is in the background. This service is started
 * from the foreground and stays alive to run the capture loop.
 */
class CameraForegroundService : Service() {
    companion object {
        const val ACTION_START = "de.example.timelapse.START"
        const val ACTION_STOP = "de.example.timelapse.STOP"
        
        private const val MAX_SINGLE_SLEEP_MS = 5 * 60_000L

        @Volatile
        private var instance: CameraForegroundService? = null

        fun isServiceRunning(): Boolean = instance != null

        fun nudge() {
            instance?.nudgeChannel?.trySend(Unit)
        }

        /**
         * Standard helper to ensure the service is running, respecting
         * background start restrictions by only attempting it when likely
         * in the foreground.
         */
        fun ensureServiceRunning(context: Context) {
            val s = SettingsManager(context)
            if (!s.timelapseEnabled && s.mqttHost.isBlank()) return
            try {
                val intent = Intent(context, CameraForegroundService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.w("Timelapse", "Failed to start camera service", t)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var hasCameraPermission = false
    private val nudgeChannel = Channel<Unit>(Channel.CONFLATED)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "timelapse_enabled" || key == "capture_interval_minutes" || key == "manual_upload_requested" ||
            key == "time_window_enabled" || key == "window_start_hour" || key == "window_start_minute" ||
            key == "window_end_hour" || key == "window_end_minute") {
            nudgeChannel.trySend(Unit)
            
            // Sync state to MQTT immediately
            if (key == "timelapse_enabled" || key == "time_window_enabled" || key == "window_start_hour" || 
                key == "window_start_minute" || key == "window_end_hour" || key == "window_end_minute" ||
                key == "capture_interval_minutes") {
                scope.launch {
                    try {
                        val s = SettingsManager(this@CameraForegroundService)
                        val mqtt = MqttClientManager(this@CameraForegroundService)
                        MqttDiscovery(mqtt, s, this@CameraForegroundService).publishState()
                        mqtt.close()
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) {
            Log.e("Timelapse", "CAMERA permission not granted - aborting service")
            stopSelf()
            return
        }
        instance = this
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(10, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(10, notification())
        }
        
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasCameraPermission) {
            WakeLockHolder.release()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                WakeLockHolder.release()
                loopJob?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startLoopIfNeeded()
                startMqttListenerIfNeeded()
                nudgeChannel.trySend(Unit)
            }
        }
        return START_STICKY
    }

    private var mqttJob: Job? = null
    private fun startMqttListenerIfNeeded() {
        if (mqttJob?.isActive == true) return
        mqttJob = scope.launch {
            while (isActive) {
                try {
                    MqttClientManager(this@CameraForegroundService).handleMqttCommands()
                } catch (_: Throwable) {}
                delay(30_000) // Keep-alive/reconnect check
            }
        }
    }

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val serviceLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timelapse:ServiceLoop")
            
            try {
                while (isActive) {
                    val s = SettingsManager(this@CameraForegroundService)
                    
                    // Manual upload is now handled in startMqttListenerIfNeeded via callback,
                    // but we still check it here in the loop just in case.
                    if (s.manualUploadRequested) {
                        try {
                            val mqtt = MqttClientManager(this@CameraForegroundService)
                            val result = SmbUploader(this@CameraForegroundService).uploadPendingPhotos()
                            if (result.uploaded > 0) {
                                mqtt.publish("timelapse/${s.deviceId}/last_upload", Instant.now().toString())
                            }
                            s.manualUploadRequested = false
                            mqtt.publish("timelapse/${s.deviceId}/upload/state", "OFF")
                            MqttDiscovery(mqtt, s, this@CameraForegroundService).publishState()
                        } catch (t: Throwable) {
                            Log.e("Timelapse", "Loop manual upload failed", t)
                        }
                    }

                    if (!s.timelapseEnabled) {
                        WakeLockHolder.release()
                        // Wait indefinitely until nudged via PrefListener
                        nudgeChannel.receive()
                        continue
                    }
                    
                    val waitMs = msUntilNextCapture(s)
                    if (waitMs <= 15000L) { // 15s grace period
                        if (waitMs > 0L) {
                            delay(waitMs)
                        }
                        if (!serviceLock.isHeld) serviceLock.acquire(3 * 60_000L)
                        try {
                            capture(s)
                        } finally {
                            WakeLockHolder.release()
                            if (serviceLock.isHeld) try { serviceLock.release() } catch (_: Throwable) {}
                        }
                    } else {
                        // Ensure wake-up alarm is set
                        try { AlarmScheduler(this@CameraForegroundService).scheduleNextCapture() } catch (_: Throwable) {}
                        WakeLockHolder.release()
                        if (serviceLock.isHeld) try { serviceLock.release() } catch (_: Throwable) {}
                        
                        withTimeoutOrNull(waitMs.coerceAtMost(MAX_SINGLE_SLEEP_MS)) {
                            nudgeChannel.receive()
                        }
                    }
                }
            } finally {
                if (serviceLock.isHeld) try { serviceLock.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun msUntilNextCapture(s: SettingsManager): Long {
        val nowCal = Calendar.getInstance()
        if (s.timeWindowEnabled) {
            val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
            val startMinutes = s.windowStartHour * 60 + s.windowStartMinute
            val endMinutes = s.windowEndHour * 60 + s.windowEndMinute
            val inside = if (startMinutes <= endMinutes) {
                nowMinutes in startMinutes until endMinutes
            } else {
                nowMinutes >= startMinutes || nowMinutes < endMinutes
            }
            if (!inside) {
                val targetCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, s.windowStartHour)
                    set(Calendar.MINUTE, s.windowStartMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= nowCal.timeInMillis) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
                return (targetCal.timeInMillis - nowCal.timeInMillis).coerceAtLeast(0L)
            }
        }
        val elapsed = System.currentTimeMillis() - s.lastCaptureAt
        val intervalMs = s.captureIntervalMinutes * 60_000L
        return intervalMs - elapsed
    }

    private suspend fun capture(s: SettingsManager) {
        try {
            s.lastCaptureAt = System.currentTimeMillis()
            AlarmScheduler(this).scheduleNextCapture()
        } catch (t: Throwable) {
            Log.e("Timelapse", "failed to schedule next capture", t)
        }

        if (s.timeWindowEnabled && !isWithinWindow(s)) return
        
        try {
            val cameras = PhotoCaptureHelper.resolveCameras(this, s)
            if (cameras.isEmpty()) {
                reportError("Keine passende Kamera gefunden")
                return
            }
            
            val failures = mutableListOf<String>()
            for ((index, camera) in cameras.withIndex()) {
                try {
                    if (index > 0) delay(2000)
                    val (w, h) = PhotoCaptureHelper.resolveResolution(s, camera.id)
                    PhotoCaptureHelper.captureAndSave(this, camera.id, w, h, s.jpegQuality, PhotoCaptureHelper.cameraLabel(camera))
                    
                    // Notify MQTT immediately after each photo is saved
                    try {
                        val mqtt = MqttClientManager(this)
                        MqttDiscovery(mqtt, s, this).publishState()
                    } catch (_: Throwable) {}
                    
                } catch (t: Throwable) {
                    Log.e("Timelapse", "capture failed for camera ${camera.id}", t)
                    failures.add("${camera.id}: ${t.message ?: t.javaClass.simpleName}")
                }
            }

            // Consolidate MQTT calls
            try {
                val mqtt = MqttClientManager(this)
                if (failures.isNotEmpty()) {
                    mqtt.publish("timelapse/${s.deviceId}/last_error", "Aufnahme fehlgeschlagen: " + failures.joinToString("; "))
                }
                MqttDiscovery(mqtt, s, this).publishState()
            } catch (t: Throwable) {
                Log.w("Timelapse", "mqtt state publish failed", t)
            }
        } catch (t: Throwable) {
            Log.e("Timelapse", "capture failed", t)
            reportError("Aufnahme fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun reportError(message: String) {
        scope.launch {
            try {
                val s = SettingsManager(this@CameraForegroundService)
                val mqtt = MqttClientManager(this@CameraForegroundService)
                mqtt.publish("timelapse/${s.deviceId}/last_error", message)
                mqtt.close()
            } catch (_: Throwable) {}
        }
    }

    private fun isWithinWindow(s: SettingsManager): Boolean {
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = s.windowStartHour * 60 + s.windowStartMinute
        val end = s.windowEndHour * 60 + s.windowEndMinute
        return if (start <= end) now in start until end else now >= start || now < end
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("camera", "Timelapse", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification = Notification.Builder(this, "camera")
        .setContentTitle("Timelapse läuft")
        .setContentText("Wartet auf nächste Aufnahme …")
        .setSmallIcon(R.drawable.ic_menu_camera)
        .build()

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        loopJob?.cancel()
        scope.launch {
            try { MqttClientManager(this@CameraForegroundService).close() } catch (_: Throwable) {}
            scope.cancel()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}

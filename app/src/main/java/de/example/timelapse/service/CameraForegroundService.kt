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

        /**
         * Standard helper to ensure the service is running, respecting
         * background start restrictions by only attempting it when likely
         * in the foreground.
         */
        fun ensureServiceRunning(context: Context) {
            if (!SettingsManager(context).timelapseEnabled) return
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
        if (key == "timelapse_enabled" || key == "capture_interval_minutes" || key == "manual_upload_requested") {
            nudgeChannel.trySend(Unit)
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
                nudgeChannel.trySend(Unit)
            }
        }
        return START_STICKY
    }

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val serviceLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timelapse:ServiceLoop")
            
            try {
                while (isActive) {
                    val s = SettingsManager(this@CameraForegroundService)
                    if (!s.timelapseEnabled) {
                        WakeLockHolder.release()
                        // Wait indefinitely until nudged via PrefListener
                        nudgeChannel.receive()
                        continue
                    }
                    
                    val waitMs = msUntilNextCapture(s)
                    if (waitMs <= 5000L) { // 5s grace period
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
                } catch (t: Throwable) {
                    Log.e("Timelapse", "capture failed for camera ${camera.id}", t)
                    failures.add("${camera.id}: ${t.message ?: t.javaClass.simpleName}")
                }
            }

            // Consolidate MQTT calls
            try {
                val mqtt = MqttClientManager(this)
                mqtt.subscribeAndCheckUpload()
                
                if (failures.isNotEmpty()) {
                    mqtt.publish("timelapse/${s.deviceId}/last_error", "Aufnahme fehlgeschlagen: " + failures.joinToString("; "))
                }
                MqttDiscovery(mqtt, s, this).publishState()
                
                // If MQTT check or previous logic set this to true, run upload now.
                if (s.manualUploadRequested) {
                    try {
                        SmbUploader(this).uploadPendingPhotos()
                        s.manualUploadRequested = false
                        mqtt.publish("timelapse/${s.deviceId}/upload/state", "OFF")
                    } catch (_: Throwable) {}
                }
                mqtt.close()
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
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}

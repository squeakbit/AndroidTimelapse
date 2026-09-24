package de.example.timelapse.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import de.example.timelapse.SettingsManager
import de.example.timelapse.WakeLockHolder
import de.example.timelapse.camera.StorageCleanupHelper
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.smb.SmbUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

class DataSyncService : Service() {
    companion object {
        const val ACTION_UPLOAD = "de.example.timelapse.UPLOAD"
        private const val NOTIFICATION_ID = 11
        private const val CHANNEL_ID = "sync"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Timelapse Sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Timelapse")
            .setContentText("Synchronisiere")
            .setSmallIcon(R.drawable.stat_sys_upload)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            try {
                performSync(intent?.action)
            } finally {
                WakeLockHolder.release()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun performSync(action: String?) {
        val settings = SettingsManager(this)
        val mqtt = MqttClientManager(this)
        try {
            if (action == ACTION_UPLOAD && settings.smbUploadEnabled) {
                val result = SmbUploader(this).uploadPendingPhotos()
                mqtt.publish("timelapse/${settings.deviceId}/last_upload_count", result.uploaded.toString())
                mqtt.publish("timelapse/${settings.deviceId}/last_upload_failed", result.failed.toString())
                if (result.lastError != null) {
                    mqtt.publish("timelapse/${settings.deviceId}/last_error", result.lastError)
                }
                mqtt.publish("timelapse/${settings.deviceId}/last_upload", Instant.now().toString())
            }
            mqtt.connectAndDiscover()
            StorageCleanupHelper.cleanOldEmptyFolders(this)
        } catch (t: Throwable) {
            Log.w("Timelapse", "sync failed", t)
        } finally {
            mqtt.close()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

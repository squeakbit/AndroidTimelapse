package de.example.timelapse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.service.DataSyncService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A BroadcastReceiver only implicitly keeps the CPU awake for the
        // synchronous duration of onReceive() itself. Holding an explicit wake lock
        // across the handoff closes that gap.
        WakeLockHolder.acquire(context, 5 * 60_000L)
        val scheduler = AlarmScheduler(context)
        when (intent.action) {
            AlarmScheduler.UPLOAD -> {
                scheduler.scheduleUpload()
                val uploadIntent = Intent(context, DataSyncService::class.java).setAction(DataSyncService.ACTION_UPLOAD)
                try {
                    ContextCompat.startForegroundService(context, uploadIntent)
                } catch (t: Throwable) {
                    Log.w("Timelapse", "failed to start upload sync service", t)
                    WakeLockHolder.release()
                }
            }
            AlarmScheduler.CAPTURE -> {
                val wakeupIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("EXTRA_ALARM_CAPTURE", true)
                }
                try {
                    context.startActivity(wakeupIntent)
                } catch (t: Throwable) {
                    Log.w("Timelapse", "failed to start activity wakeup", t)
                }

                try {
                    CameraForegroundService.ensureServiceRunning(context)
                } catch (_: Throwable) {
                }

                if (CameraForegroundService.isServiceRunning()) {
                    CameraForegroundService.nudge()
                }
            }
            else -> WakeLockHolder.release()
        }
    }
}

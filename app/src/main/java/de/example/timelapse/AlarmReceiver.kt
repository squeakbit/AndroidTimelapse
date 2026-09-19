    package de.example.timelapse
    import android.content.*
    import android.util.Log
    import androidx.core.content.ContextCompat
    import de.example.timelapse.service.*

    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // A BroadcastReceiver only implicitly keeps the CPU awake for the
            // synchronous duration of onReceive() itself. startForegroundService()
            // is fire-and-forget - once onReceive() returns, the device can go
            // straight back to deep sleep before the newly requested service
            // actually gets scheduled and reaches startForeground(), silently
            // stranding the capture until something else (e.g. the user turning
            // the screen on) wakes the CPU again. Holding an explicit wake lock
            // across the handoff closes that gap. The service releases it as
            // soon as its work is actually done; we use a 5-minute timeout as
            // a safety ceiling to cover camera init and network reporting.
            WakeLockHolder.acquire(c, 5 * 60_000L)
            val s = AlarmScheduler(c)
            when (i.action) {
                AlarmScheduler.UPLOAD -> {
                    s.scheduleUpload()
                    val x = Intent(c, DataSyncService::class.java).setAction(DataSyncService.ACTION_UPLOAD)
                    try { ContextCompat.startForegroundService(c, x) }
                    catch (t: Throwable) { Log.w("Timelapse", "failed to start upload sync service", t); WakeLockHolder.release() }
                }
                AlarmScheduler.CAPTURE -> {
                    // If the service is already running, we can directly nudge its coroutine loop
                    // without calling startForegroundService from the background, avoiding
                    // Android 14+ / 15 / 16 background start restrictions for camera services.
                    if (CameraForegroundService.isServiceRunning()) {
                        CameraForegroundService.nudge()
                    } else {
                        val x = Intent(c, CameraForegroundService::class.java).setAction(CameraForegroundService.ACTION_START)
                        try { ContextCompat.startForegroundService(c, x) }
                        catch (t: Throwable) { Log.w("Timelapse", "failed to nudge camera service", t); WakeLockHolder.release() }
                    }
                }
                else -> WakeLockHolder.release()
            }
        }
    }
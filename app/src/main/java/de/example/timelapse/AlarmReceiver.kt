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
                    // On Android 14/15/16, a background service of type "camera"
                    // cannot regain while-in-use camera access if it was killed
                    // and restarted from the background. Launching the main activity
                    // as a foreground bridge guarantees foreground status and
                    // full camera permissions.
                    val wakeup = Intent(c, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("EXTRA_ALARM_CAPTURE", true)
                    }
                    try { c.startActivity(wakeup) } catch (_: Throwable) {}
                    
                    // Also nudge if already running to wake up from Doze/delay
                    if (CameraForegroundService.isServiceRunning()) {
                        CameraForegroundService.nudge()
                    }
                }
                else -> WakeLockHolder.release()
            }
        }
    }
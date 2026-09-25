package de.example.timelapse
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.*
class AlarmScheduler(private val c:Context){
    companion object {
        const val UPLOAD = "de.example.timelapse.UPLOAD"
        const val CAPTURE = "de.example.timelapse.CAPTURE"
        private const val RU = 1002
        private const val RC = 1003
    }
    private val am = c.getSystemService(AlarmManager::class.java)

    // Daily upload and interval capture nudges.
    fun scheduleAll() {
        val s = SettingsManager(c)
        if (s.smbUploadEnabled) scheduleUpload() else cancel(UPLOAD, RU)
        if (s.timelapseEnabled) scheduleNextCapture() else cancel(CAPTURE, RC)
    }

    // Upload is scheduled daily at a fixed time only.
    fun scheduleUpload() {
        val s = SettingsManager(c)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.smbUploadHour)
            set(Calendar.MINUTE, s.smbUploadMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        schedule(UPLOAD, RU, cal.timeInMillis)
    }

    /**
     * Schedules a "nudge" alarm for the next capture. This is NOT the primary
     * timing source (the service's internal loop is, to avoid Android 14+
     * background-start restrictions), but it ensures the device actually
     * wakes up from deep sleep (Doze) so the loop can continue running.
     */
    fun scheduleNextCapture() {
        val s = SettingsManager(c)
        val waitMs = TimeWindowUtils.msUntilNextCapture(s)
        
        // Add a tiny 500ms offset to ensure we land AFTER the interval
        // boundary, preventing "just missed it" double-fires.
        schedule(CAPTURE, RC, System.currentTimeMillis() + waitMs + 500)
    }

    private fun schedule(action: String, request: Int, at: Long) {
        val pi = pending(action, request)
        // AlarmClock is the most reliable way to wake up from Doze on all
        // API levels, bypasses most OEM-specific throttling, and is not
        // affected by the "9 minute" interval limit of setAndAllowWhileIdle.
        // The only downside is the alarm clock icon in the status bar.
        try {
            val intent = Intent(c, MainActivity::class.java)
            val showIntent = if (Build.VERSION.SDK_INT >= 34) {
                showIntentWithOptIn(intent)
            } else {
                PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            }
            val info = AlarmManager.AlarmClockInfo(at, showIntent)
            am.setAlarmClock(info, pi)
        } catch (e: SecurityException) {
            Log.w("Timelapse", "exact alarm permission missing", e)
        }
    }
    private fun cancel(a: String, r: Int) = am.cancel(pending(a, r))
    private fun pending(a: String, r: Int): PendingIntent {
        val intent = Intent(c, AlarmReceiver::class.java).setAction(a)
        return if (Build.VERSION.SDK_INT >= 34) {
            pendingWithOptIn(a, r)
        } else {
            PendingIntent.getBroadcast(c, r, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @SuppressLint("NewApi")
    private fun pendingWithOptIn(action: String, request: Int): PendingIntent {
        val intent = Intent(c, AlarmReceiver::class.java).setAction(action)
        return try {
            val options = ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
            
            val method = PendingIntent::class.java.getMethod(
                "getBroadcast",
                Context::class.java,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            )
            method.invoke(
                null,
                c,
                request,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                options
            ) as PendingIntent
        } catch (t: Throwable) {
            Log.w("Timelapse", "Failed to invoke getBroadcast with background activity start mode", t)
            PendingIntent.getBroadcast(c, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @SuppressLint("NewApi")
    private fun showIntentWithOptIn(intent: Intent): PendingIntent {
        return try {
            val options = ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
            
            val method = PendingIntent::class.java.getMethod(
                "getActivity",
                Context::class.java,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            )
            method.invoke(
                null,
                c,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
                options
            ) as PendingIntent
        } catch (t: Throwable) {
            Log.w("Timelapse", "Failed to invoke getActivity with background activity start mode", t)
            PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
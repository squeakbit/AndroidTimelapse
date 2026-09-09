package de.example.timelapse
import android.content.*

class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    // This is a protected system broadcast (only the OS can send it), but we
    // still check the action explicitly: a receiver that reacts to *any*
    // intent it's handed, regardless of action, can be triggered by a
    // spoofed/empty-action intent from another app targeting this exported
    // component, potentially causing unwanted alarm rescheduling.
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            AlarmScheduler(c).scheduleAll()
        }
    }

    companion object {
        // Same value as android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        // (API 31+ constant); used as a string literal here so the receiver
        // itself has no API-level requirement.
        private const val ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
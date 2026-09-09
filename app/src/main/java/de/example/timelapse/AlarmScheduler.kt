package de.example.timelapse
import android.app.*;import android.content.*;import android.os.Build;import java.util.*
class AlarmScheduler(private val c:Context){
 companion object{const val UPLOAD="de.example.timelapse.UPLOAD";private const val RU=1002}
 private val am=c.getSystemService(AlarmManager::class.java)
 // Captures are driven entirely by CameraForegroundService's own internal
 // timing loop while it's alive - there is no per-photo alarm and no
 // separate heartbeat alarm. Every scheduled capture already publishes the
 // full MQTT state (see CameraForegroundService.capture()), which serves
 // as the "still alive" signal a dedicated heartbeat used to provide.
 fun scheduleAll(){val s=SettingsManager(c);if(s.smbUploadEnabled)scheduleUpload() else cancel(UPLOAD,RU)}
 // Upload is scheduled daily at a fixed time only. (An interval-based mode
 // was tried and removed again: it never fired reliably in practice and
 // only increased battery use, so it's not worth the added complexity.)
 fun scheduleUpload(){
  val s=SettingsManager(c)
  val cal=Calendar.getInstance().apply{set(Calendar.HOUR_OF_DAY,s.smbUploadHour);set(Calendar.MINUTE,s.smbUploadMinute);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0);if(timeInMillis<=System.currentTimeMillis())add(Calendar.DAY_OF_YEAR,1)}
  schedule(UPLOAD,RU,cal.timeInMillis)
 }
 private fun schedule(action:String,request:Int,at:Long){
  val pi=pending(action,request)
  // canScheduleExactAlarms() only exists on API 31+; below that, holding
  // the SCHEDULE_EXACT_ALARM permission (declared in the manifest) is
  // enough and exact alarms are always allowed.
  val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
  if(canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi)
 }
 private fun cancel(a:String,r:Int)=am.cancel(pending(a,r))
 private fun pending(a:String,r:Int)=PendingIntent.getBroadcast(c,r,Intent(c,AlarmReceiver::class.java).setAction(a),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
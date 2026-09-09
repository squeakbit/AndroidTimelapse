package de.example.timelapse.service
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.content.ContextCompat
import de.example.timelapse.*
import de.example.timelapse.camera.PhotoCaptureHelper
import java.util.*
import kotlinx.coroutines.*

/**
 * Since Android 14, a foreground service of type "camera" cannot be
 * *started* while the app is in the background - regardless of whether the
 * CAMERA permission is granted (confirmed via real-device logcat: "Foreground
 * service started from background can not have location/camera/microphone
 * access"). Repeatedly starting this service fresh from an AlarmManager
 * broadcast (as it used to) therefore crashes it every single time the app
 * isn't already in the foreground.
 *
 * The fix is architectural: this service is started ONCE from a legitimate
 * foreground context (MainActivity, when the user is actively looking at the
 * app) and then stays alive indefinitely, running its own internal timing
 * loop to decide when the next capture is due - instead of relying on being
 * re-started by an external alarm for every single photo. There is no
 * separate alarm nudging this service back alive either: every capture
 * already publishes the full MQTT state, which is itself the "still alive"
 * signal, so the only thing AlarmManager is still used for in this app is
 * the daily SMB upload.
 */
class CameraForegroundService:Service(){
 companion object{
  const val ACTION_START="de.example.timelapse.START"
  const val ACTION_STOP="de.example.timelapse.STOP"
 }
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private var loopJob:Job?=null
 private var hasCameraPermission=false

 override fun onCreate(){
  super.onCreate()
  hasCameraPermission=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
  if(!hasCameraPermission){
   android.util.Log.e("Timelapse","CAMERA permission not granted - aborting without starting foreground service")
   reportError("Kamera-Berechtigung fehlt - bitte App öffnen und Berechtigung neu erteilen")
   stopSelf()
   return
  }
  createChannel()
  startForeground(10,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
 }

 override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
  WakeLockHolder.release()
  if(!hasCameraPermission){stopSelf(startId);return START_NOT_STICKY}
  when(i?.action){
   ACTION_STOP->{loopJob?.cancel();stopSelf();return START_NOT_STICKY}
   else->startLoopIfNeeded()
  }
  return START_STICKY
 }

 private fun startLoopIfNeeded(){
  if(loopJob?.isActive==true)return
  loopJob=scope.launch{
   while(isActive){
    val s=SettingsManager(this@CameraForegroundService)
    if(s.timelapseEnabled && dueForCapture(s)) capture(s)
    // 30s check cadence keeps timing reasonably tight without busy-looping;
    // actual capture cadence is governed by captureIntervalMinutes below.
    delay(30_000L)
   }
  }
 }

 private fun dueForCapture(s:SettingsManager):Boolean{
  val elapsed=System.currentTimeMillis()-s.lastCaptureAt
  return elapsed>=s.captureIntervalMinutes*60_000L
 }

 private suspend fun capture(s:SettingsManager){
  s.lastCaptureAt=System.currentTimeMillis()
  if(s.timeWindowEnabled && !isWithinWindow(s))return
  try{
   val id=PhotoCaptureHelper.resolveCameraId(this,s) ?: return
   PhotoCaptureHelper.captureAndSave(this,id,s.cameraWidth,s.cameraHeight,s.jpegQuality)
   // Publish all sensor states right away (not just on the daily upload)
   // so Home Assistant reflects a real-time proof-of-life for scheduled
   // capture, independent of whether the app's UI process is still alive.
   // This is what replaces the old separate hourly heartbeat.
   try{
    val mqtt=de.example.timelapse.mqtt.MqttClientManager(this)
    de.example.timelapse.mqtt.MqttDiscovery(mqtt,s,this).publishState()
    mqtt.close()
   }catch(t:Throwable){android.util.Log.w("Timelapse","mqtt state publish failed",t)}
  }catch(t:Throwable){
   android.util.Log.e("Timelapse","capture failed",t)
   reportError("Aufnahme fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
  }
 }

 /** Best-effort MQTT report of a failure, visible in Home Assistant without needing logcat. */
 private fun reportError(message:String){
  scope.launch{
   try{
    val s=SettingsManager(this@CameraForegroundService)
    val mqtt=de.example.timelapse.mqtt.MqttClientManager(this@CameraForegroundService)
    mqtt.publish("timelapse/${s.deviceId}/last_error",message)
    mqtt.close()
   }catch(_:Throwable){}
  }
 }

 /**
  * Daily time window (e.g. 18:00–06:00). Handles wraparound past midnight:
  * if the end is earlier than the start, "in window" means at/after start
  * OR before end.
  */
 private fun isWithinWindow(s:SettingsManager):Boolean{
  val cal=Calendar.getInstance()
  val now=cal.get(Calendar.HOUR_OF_DAY)*60+cal.get(Calendar.MINUTE)
  val start=s.windowStartHour*60+s.windowStartMinute
  val end=s.windowEndHour*60+s.windowEndMinute
  return if(start<=end) now in start until end else now>=start || now<end
 }
 private fun createChannel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("camera","Timelapse",NotificationManager.IMPORTANCE_LOW))}
 private fun notification()=Notification.Builder(this,"camera").setContentTitle("Timelapse läuft").setContentText("Wartet auf nächste Aufnahme …").setSmallIcon(android.R.drawable.ic_menu_camera).build()
 override fun onDestroy(){loopJob?.cancel();scope.cancel();super.onDestroy()}
 override fun onBind(i:Intent?)=null
}
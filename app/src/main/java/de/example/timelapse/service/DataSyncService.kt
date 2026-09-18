package de.example.timelapse.service
import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.os.*;import de.example.timelapse.*;import de.example.timelapse.data.*;import de.example.timelapse.mqtt.*;import de.example.timelapse.smb.*
import de.example.timelapse.camera.StorageCleanupHelper
import kotlinx.coroutines.*
import java.time.Instant

class DataSyncService:Service(){
 companion object{const val ACTION_UPLOAD="de.example.timelapse.UPLOAD"}
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 override fun onCreate(){
  super.onCreate()
  val ch=NotificationChannel("sync","Timelapse Sync",NotificationManager.IMPORTANCE_LOW)
  getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
  val n=Notification.Builder(this,"sync").setContentTitle("Timelapse").setContentText("Synchronisiere").setSmallIcon(android.R.drawable.stat_sys_upload).build()
  // FOREGROUND_SERVICE_TYPE_DATA_SYNC and the 3-arg startForeground overload
  // only exist from API 29 onward; this app's minSdk is 26 (Android 8.0),
  // where the 2-arg overload is the only one available.
  if(Build.VERSION.SDK_INT>=29) startForeground(11,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  else startForeground(11,n)
 }
 override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{scope.launch{run(i?.action);WakeLockHolder.release();stopSelf(id)};return START_NOT_STICKY}
 private suspend fun run(a:String?){
  val s=SettingsManager(this);val mqtt=MqttClientManager(this)
  try{
   if(a==ACTION_UPLOAD&&s.smbUploadEnabled){val r=SmbUploader(this).uploadPendingPhotos();mqtt.publish("timelapse/${s.deviceId}/last_upload_count",r.uploaded.toString());mqtt.publish("timelapse/${s.deviceId}/last_upload_failed",r.failed.toString());if(r.lastError!=null)mqtt.publish("timelapse/${s.deviceId}/last_error",r.lastError);mqtt.publish("timelapse/${s.deviceId}/last_upload",
       Instant.now().toString())}
   // Also (re)publishes MQTT discovery + current state, so Home Assistant
   // stays in sync at least once a day even if no photo was captured
   // in between (e.g. timelapse disabled, only manual SMB upload used).
   mqtt.connectAndDiscover()
   
   // Cleanup old empty folders
   StorageCleanupHelper.cleanOldEmptyFolders(this)
  }catch(t:Throwable){android.util.Log.w("Timelapse","sync failed",t)}finally{mqtt.close()}
 }
 override fun onDestroy(){scope.cancel();super.onDestroy()};override fun onBind(i:Intent?)=null
}
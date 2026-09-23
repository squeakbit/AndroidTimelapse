package de.example.timelapse.mqtt
import android.content.Context
import de.example.timelapse.*
import de.example.timelapse.service.CameraForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import java.util.UUID
class MqttClientManager(private val context:Context){
 private val settings=SettingsManager(context)
 
 companion object {
  private var sharedClient: MqttAsyncClient? = null
  private val mutex = Mutex()
 }
 
 suspend fun publish(topic:String,payload:String,retained:Boolean=true)=withContext(Dispatchers.IO){
  val host=settings.mqttHost; if(host.isBlank()) return@withContext
  try{
   val c = getConnectedClient()
   val msg=MqttMessage(payload.toByteArray()).apply{qos=1;isRetained=retained}
   c.publish(topic,msg).waitForCompletion(10_000)
  }catch(_:Throwable){}
 }
 
 suspend fun connectAndDiscover() = withContext(Dispatchers.IO) {
    try {
        getConnectedClient()
        MqttDiscovery(this@MqttClientManager, settings, context).publishAll()
    } catch (_: Exception) {}
 }

 suspend fun handleMqttCommands() = withContext(Dispatchers.IO) {
  val host=settings.mqttHost; if(host.isBlank()) return@withContext
  val c = try { getConnectedClient() } catch (_: Throwable) { return@withContext }
  
  try {
   val base = "timelapse/${settings.deviceId}"
   val topics = arrayOf("$base/upload/set", "$base/enabled/set", "$base/time_window/set", "$base/window_start/set", "$base/window_end/set", "$base/capture_interval/set")
   
   c.setCallback(object : MqttCallback {
    override fun disconnected(dr: MqttDisconnectResponse?) {}
    override fun mqttErrorOccurred(ex: MqttException?) {}
    override fun messageArrived(t: String?, msg: MqttMessage?) {
     val payload = msg?.toString() ?: ""
     if (payload.isBlank()) return
     
     var changed = false
     when (t) {
      "$base/upload/set" -> if (payload == "ON") { settings.manualUploadRequested = true; changed = true }
      "$base/enabled/set" -> {
       val on = (payload == "ON")
       if (on != settings.timelapseEnabled) {
        if (on) settings.lastCaptureAt = 0L
        settings.timelapseEnabled = on
        AlarmScheduler(context).scheduleAll()
        CameraForegroundService.nudge() // Wake up loop immediately
        changed = true
       }
      }
      "$base/time_window/set" -> {
       val on = (payload == "ON")
       if (on != settings.timeWindowEnabled) {
        settings.timeWindowEnabled = on
        AlarmScheduler(context).scheduleAll()
        CameraForegroundService.nudge()
        changed = true
       }
      }
      "$base/window_start/set" -> {
       val parts = payload.split(":")
       if (parts.size == 2) {
        settings.windowStartHour = parts[0].toIntOrNull() ?: settings.windowStartHour
        settings.windowStartMinute = parts[1].toIntOrNull() ?: settings.windowStartMinute
        AlarmScheduler(context).scheduleNextCapture()
        CameraForegroundService.nudge()
        changed = true
       }
      }
      "$base/window_end/set" -> {
       val parts = payload.split(":")
       if (parts.size == 2) {
        settings.windowEndHour = parts[0].toIntOrNull() ?: settings.windowEndHour
        settings.windowEndMinute = parts[1].toIntOrNull() ?: settings.windowEndMinute
        AlarmScheduler(context).scheduleNextCapture()
        CameraForegroundService.nudge()
        changed = true
       }
      }
      "$base/capture_interval/set" -> {
       val minutes = payload.toIntOrNull()
       if (minutes != null && minutes > 0) {
        settings.captureIntervalMinutes = minutes
        AlarmScheduler(context).scheduleAll()
        CameraForegroundService.nudge()
        changed = true
       }
      }
     }
     
     if (changed) {
      // Clear retained command
      val emptyMsg = MqttMessage("".toByteArray()).apply { qos = 1; isRetained = true }
      try { c.publish(t, emptyMsg) } catch (_: Throwable) {}
      // The prefListener in CameraForegroundService will handle the publishState() call
     }
    }
    override fun deliveryComplete(token: IMqttToken?) {}
    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        // Re-subscribe on connect/reconnect
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val base = "timelapse/${settings.deviceId}"
                val ts = arrayOf("$base/upload/set", "$base/enabled/set", "$base/time_window/set", "$base/window_start/set", "$base/window_end/set", "$base/capture_interval/set")
                c.subscribe(ts, IntArray(ts.size) { 1 })
            } catch (_: Throwable) {}
        }
    }
    override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}
   })

   c.subscribe(topics, IntArray(topics.size) { 1 }).waitForCompletion(5000)
  } catch (_: Throwable) {}
 }

 private suspend fun getConnectedClient(): MqttAsyncClient {
  val host=settings.mqttHost; if(host.isBlank()) throw Exception("No MQTT host")
  
  // Fast path: client already connected
  sharedClient?.let { if (it.isConnected) return it }
  
  // Slow path: need to connect, lock the mutex
  return mutex.withLock {
   sharedClient?.let { if (it.isConnected) return it }
   
   try { sharedClient?.close() } catch(_: Throwable) {}
   val uri=(if(settings.mqttTls)"ssl" else "tcp")+"://${host}:${settings.mqttPort}"
   val c=MqttAsyncClient(uri,settings.mqttClientId, null)
   val o=MqttConnectionOptions().apply{
    isCleanStart=false // Keep session to survive brief disconnects
    isAutomaticReconnect=true
    keepAliveInterval = 600
    val secrets = SecureSecrets.getInstance(context)
    userName=secrets.mqttUsername.ifBlank{settings.mqttUsername}
    password=secrets.mqttPassword.ifBlank{settings.mqttPassword}.toByteArray()
   }
   c.connect(o).waitForCompletion(15_000)
   sharedClient = c
   c
  }
 }

 fun close(){
  // We don't really want to close the shared client every time anymore
  // if we want to reuse it. But for cleanup it's fine.
  // Actually, let's keep it open until the app/service is destroyed.
 }
}

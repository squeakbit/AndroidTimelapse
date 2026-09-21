package de.example.timelapse.mqtt
import android.content.Context
import de.example.timelapse.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 private val settings=SettingsManager(context); private var client:MqttAsyncClient?=null
 suspend fun publish(topic:String,payload:String,retained:Boolean=true)=withContext(Dispatchers.IO){
  val host=settings.mqttHost; if(host.isBlank()) return@withContext
  try{
   ensureConnected()
   val msg=MqttMessage(payload.toByteArray()).apply{qos=1;isRetained=retained}
   client?.publish(topic,msg)?.waitForCompletion(10_000)
  }catch(_:Throwable){}
 }
 suspend fun connectAndDiscover() = withContext(Dispatchers.IO) {
    ensureConnected()
    MqttDiscovery(this@MqttClientManager, settings, context).publishAll()
 }
 suspend fun handleMqttCommands() = withContext(Dispatchers.IO) {
  val host=settings.mqttHost; if(host.isBlank()) return@withContext
  try {
   ensureConnected()
   val base = "timelapse/${settings.deviceId}"
   val topics = arrayOf("$base/upload/set", "$base/enabled/set", "$base/time_window/set", "$base/window_start/set", "$base/window_end/set")
   client?.subscribe(topics, IntArray(topics.size) { 1 })?.waitForCompletion(5000)
   
   var anyCommandProcessed = false
   client?.setCallback(object : MqttCallback {
    override fun disconnected(dr: MqttDisconnectResponse?) {}
    override fun mqttErrorOccurred(ex: MqttException?) {}
    override fun messageArrived(t: String?, msg: MqttMessage?) {
     val payload = msg?.toString() ?: ""
     if (payload.isBlank()) return
     
     when (t) {
      "$base/upload/set" -> if (payload == "ON") { settings.manualUploadRequested = true; anyCommandProcessed = true }
      "$base/enabled/set" -> { settings.timelapseEnabled = (payload == "ON"); anyCommandProcessed = true }
      "$base/time_window/set" -> { settings.timeWindowEnabled = (payload == "ON"); anyCommandProcessed = true }
      "$base/window_start/set" -> {
       val parts = payload.split(":")
       if (parts.size == 2) {
        settings.windowStartHour = parts[0].toIntOrNull() ?: settings.windowStartHour
        settings.windowStartMinute = parts[1].toIntOrNull() ?: settings.windowStartMinute
        anyCommandProcessed = true
       }
      }
      "$base/window_end/set" -> {
       val parts = payload.split(":")
       if (parts.size == 2) {
        settings.windowEndHour = parts[0].toIntOrNull() ?: settings.windowEndHour
        settings.windowEndMinute = parts[1].toIntOrNull() ?: settings.windowEndMinute
        anyCommandProcessed = true
       }
      }
     }
    }
    override fun deliveryComplete(token: IMqttToken?) {}
    override fun connectComplete(reconnect: Boolean, serverURI: String?) {}
    override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}
   })
   
   delay(2000) // Wait for retained messages
   
   // Clear retained messages for all processed commands except maybe stateful ones?
   // Actually, HA sends commands. If they are retained, we should clear them so they don't re-trigger.
   if (anyCommandProcessed) {
    val emptyMsg = MqttMessage("".toByteArray()).apply { qos = 1; isRetained = true }
    for (topic in topics) {
     client?.publish(topic, emptyMsg)
    }
   }
   
   client?.unsubscribe(topics)?.waitForCompletion(2000)
  } catch (_: Throwable) {}
 }
 private fun ensureConnected(){
  val host=settings.mqttHost; if(host.isBlank()) return
  if(client?.isConnected==true)return
  client?.close()
  val uri=(if(settings.mqttTls)"ssl" else "tcp")+"://${host}:${settings.mqttPort}"
  val c=MqttAsyncClient(uri,settings.mqttClientId, null)
  val o=MqttConnectionOptions().apply{
   isCleanStart=true; isAutomaticReconnect=false
   val secrets = SecureSecrets.getInstance(context)
   userName=secrets.mqttUsername.ifBlank{settings.mqttUsername}
   password=secrets.mqttPassword.ifBlank{settings.mqttPassword}.toByteArray()
  }
  c.connect(o).waitForCompletion(15_000)
  client = c
 }
 fun close(){
  try{client?.disconnect()?.waitForCompletion(5_000)}catch(_:Throwable){}
  try{client?.close()}catch(_:Throwable){}
 }
}

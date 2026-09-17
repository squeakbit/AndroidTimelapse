package de.example.timelapse.mqtt
import android.content.Context
import android.os.BatteryManager
import de.example.timelapse.SettingsManager
import de.example.timelapse.data.AppDatabase
import org.json.JSONObject
import java.time.Instant

class MqttDiscovery(private val mqtt: MqttClientManager, private val s: SettingsManager, private val context: Context) {
    private val base = "timelapse/${s.deviceId}"

    private fun device() = JSONObject().apply {
        put("identifiers", org.json.JSONArray().put(s.deviceId))
        put("name", s.deviceName)
        put("manufacturer", "Android Timelapse")
        put("model", "Camera")
    }

    suspend fun publishAll() {
        sensor("battery", "Akku", "$base/battery", "mdi:battery", "%")
        sensor("photos_pending", "Fotos ausstehend", "$base/photos_pending", "mdi:image-multiple-outline", null)
        sensor("last_photo", "Letztes Foto", "$base/last_photo", "mdi:camera-clock", "timestamp")
        sensor("last_upload", "Letzter Upload", "$base/last_upload", "mdi:cloud-upload-outline", "timestamp")
        sensor("last_upload_failed", "Letzter Upload Fehler", "$base/last_upload_failed", "mdi:alert-circle-outline", null)
        sensor("last_error", "Letzter Fehler", "$base/last_error", "mdi:alert", null)
        
        // Manual Upload Trigger (Switch is better than button for "fire later" behavior)
        config("switch", "manual_upload", JSONObject().apply {
            put("name", "${s.deviceName} Manueller Upload")
            put("unique_id", "${s.deviceId}_manual_upload")
            put("command_topic", "$base/upload/set")
            put("state_topic", "$base/upload/state")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("retain", true)
            put("icon", "mdi:cloud-upload")
            put("device", device())
        })
        
        publishState()
    }

    /**
     * Publishes the current sensor values (retained) so entities show real
     * data right away instead of "unbekannt" until the next scheduled
     * capture or upload. There is no separate heartbeat: every scheduled
     * capture calls this too (see CameraForegroundService.capture()), which
     * already proves the app is alive via last_photo/battery/etc. updating.
     */
    suspend fun publishState() {
        try {
            val dao = AppDatabase.getInstance(context).photoDao()
            mqtt.publish("$base/battery", getBattery().toString())
            mqtt.publish("$base/photos_pending", dao.getPendingCount().toString())
            mqtt.publish("$base/upload/state", if (s.manualUploadRequested) "ON" else "OFF")
            dao.getLastPhoto()?.let {
                mqtt.publish("$base/last_photo", Instant.ofEpochMilli(it.capturedAt).toString())
            }
        } catch (_: Throwable) {
        }
    }

    private fun getBattery(): Int =
        context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private suspend fun sensor(id: String, name: String, state: String, icon: String, unit: String?) {
        config("sensor", id, JSONObject().apply {
            put("name", "${s.deviceName} $name")
            put("state_topic", state)
            put("icon", icon)
            if (unit == "timestamp") put("device_class", "timestamp")
            else if (unit != null) {
                put("unit_of_measurement", unit)
                put("device_class", "battery")
                put("state_class", "measurement")
            }
        })
    }

    private suspend fun config(type: String, id: String, json: JSONObject) {
        if (!json.has("unique_id")) json.put("unique_id", "${s.deviceId}_$id")
        if (!json.has("device")) json.put("device", device())
        mqtt.publish("homeassistant/$type/${s.deviceId}_$id/config", json.toString(), true)
    }
}

package de.example.timelapse.mqtt
import android.content.Context
import android.os.BatteryManager
import de.example.timelapse.SettingsManager
import de.example.timelapse.data.AppDatabase
import org.json.JSONObject

class MqttDiscovery(private val mqtt: MqttClientManager, private val s: SettingsManager, private val context: Context) {
    private val base = "timelapse/${s.deviceId}"

    private fun device() = JSONObject().apply {
        put("identifiers", org.json.JSONArray().put(s.deviceId))
        put("name", s.deviceName)
        put("manufacturer", "Android Timelapse")
        put("model", "Camera")
    }

    suspend fun publishAll() {
        sensor("status", "Status", "$base/status", "mdi:camera", null, withAvailability = false)
        sensor("battery", "Akku", "$base/battery", "mdi:battery", "%")
        sensor("photos_pending", "Fotos ausstehend", "$base/photos_pending", "mdi:image-multiple-outline", null)
        sensor("photos_uploaded", "Fotos hochgeladen", "$base/photos_uploaded", "mdi:cloud-upload", null)
        sensor("last_photo", "Letztes Foto", "$base/last_photo", "mdi:camera-clock", "timestamp")
        sensor("last_upload", "Letzter Upload", "$base/last_upload", "mdi:cloud-upload-outline", "timestamp")
        sensor("last_upload_count", "Letzter Upload Anzahl", "$base/last_upload_count", "mdi:upload", null)
        sensor("last_upload_failed", "Letzter Upload Fehler", "$base/last_upload_failed", "mdi:alert-circle-outline", null)
        sensor("last_error", "Letzter Fehler", "$base/last_error", "mdi:alert", null)
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
            mqtt.publish("$base/photos_uploaded", dao.getUploadedCount().toString())
            dao.getLastPhoto()?.let {
                mqtt.publish("$base/last_photo", java.time.Instant.ofEpochMilli(it.capturedAt).toString())
            }
        } catch (_: Throwable) {
            // Best-effort: discovery/config publishing should still succeed
            // even if state values (e.g. DB) aren't available yet.
        }
    }

    private fun getBattery(): Int =
        context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private suspend fun sensor(id: String, name: String, state: String, icon: String, unit: String?, withAvailability: Boolean = true) {
        val j = JSONObject().apply {
            put("name", "${s.deviceName} $name")
            put("unique_id", "${s.deviceId}_$id")
            put("state_topic", state)
            put("icon", icon)
            put("device", device())
            if (withAvailability) {
                // Ties every other entity's availability to the status topic
                // so Home Assistant visibly greys them out as "not available"
                // the moment the app/connection genuinely goes offline
                // (LWT), instead of silently showing stale last-known values.
                put("availability_topic", "$base/status")
                put("payload_available", "online")
                put("payload_not_available", "offline")
            }
            if (unit == "timestamp") put("device_class", "timestamp")
            else if (unit != null) {
                put("unit_of_measurement", unit)
                put("device_class", "battery")
                put("state_class", "measurement")
            }
            if (id == "photos_uploaded") put("state_class", "total_increasing")
        }
        mqtt.publish("homeassistant/sensor/${s.deviceId}_$id/config", j.toString(), true)
    }
}
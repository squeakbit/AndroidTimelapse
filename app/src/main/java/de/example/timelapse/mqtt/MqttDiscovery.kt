package de.example.timelapse.mqtt
import android.content.Context
import android.os.BatteryManager
import de.example.timelapse.SettingsManager
import de.example.timelapse.data.AppDatabase
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

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
        sensor("last_photo", "Letztes Foto", "$base/last_photo", "mdi:camera-timer", "timestamp")
        sensor("last_upload", "Letzter Upload", "$base/last_upload", "mdi:cloud-upload-outline", "timestamp")
        sensor("last_upload_failed", "Letzter Upload Fehler", "$base/last_upload_failed", "mdi:alert-circle-outline", null)
        sensor("last_error", "Letzter Fehler", "$base/last_error", "mdi:alert", null)

        // Main Enable Switch
        config("switch", "enabled", JSONObject().apply {
            put("name", "${s.deviceName} Aktiv")
            put("unique_id", "${s.deviceId}_enabled")
            put("command_topic", "$base/enabled/set")
            put("state_topic", "$base/enabled/state")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("retain", true)
            put("optimistic", false)
            put("icon", "mdi:camera-timer")
            put("device", device())
        })

        // Time Window Switch
        config("switch", "time_window", JSONObject().apply {
            put("name", "${s.deviceName} Zeitfenster")
            put("unique_id", "${s.deviceId}_time_window")
            put("command_topic", "$base/time_window/set")
            put("state_topic", "$base/time_window/state")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("retain", true)
            put("optimistic", false)
            put("icon", "mdi:timetable")
            put("device", device())
        })

        // Window Start Time
        config("text", "window_start", JSONObject().apply {
            put("name", "${s.deviceName} Startzeit")
            put("unique_id", "${s.deviceId}_window_start")
            put("command_topic", "$base/window_start/set")
            put("state_topic", "$base/window_start/state")
            put("pattern", "^[0-2][0-9]:[0-5][0-9]$")
            put("mode", "text")
            put("icon", "mdi:clock-start")
            put("device", device())
        })

        // Window End Time
        config("text", "window_end", JSONObject().apply {
            put("name", "${s.deviceName} Endzeit")
            put("unique_id", "${s.deviceId}_window_end")
            put("command_topic", "$base/window_end/set")
            put("state_topic", "$base/window_end/state")
            put("pattern", "^[0-2][0-9]:[0-5][0-9]$")
            put("mode", "text")
            put("icon", "mdi:clock-end")
            put("device", device())
        })

        // Capture Interval (Number / Text input)
        config("text", "capture_interval", JSONObject().apply {
            put("name", "${s.deviceName} Intervall (Minuten)")
            put("unique_id", "${s.deviceId}_capture_interval")
            put("command_topic", "$base/capture_interval/set")
            put("state_topic", "$base/capture_interval/state")
            put("pattern", "^[0-9]+$")
            put("mode", "text")
            put("icon", "mdi:update")
            put("device", device())
        })
        
        // Manual Upload Trigger
        config("switch", "manual_upload", JSONObject().apply {
            put("name", "${s.deviceName} Manueller Upload")
            put("unique_id", "${s.deviceId}_manual_upload")
            put("command_topic", "$base/upload/set")
            put("state_topic", "$base/upload/state")
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("retain", true)
            put("optimistic", true)
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
            mqtt.publish("$base/enabled/state", if (s.timelapseEnabled) "ON" else "OFF")
            mqtt.publish("$base/time_window/state", if (s.timeWindowEnabled) "ON" else "OFF")
            mqtt.publish("$base/window_start/state", String.format(Locale.US, "%02d:%02d", s.windowStartHour, s.windowStartMinute))
            mqtt.publish("$base/window_end/state", String.format(Locale.US, "%02d:%02d", s.windowEndHour, s.windowEndMinute))
            mqtt.publish("$base/capture_interval/state", s.captureIntervalMinutes.toString())
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
                if (id == "battery") put("device_class", "battery")
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

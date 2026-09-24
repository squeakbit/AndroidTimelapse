package de.example.timelapse

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSecrets private constructor(context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: SecureSecrets? = null

        fun getInstance(context: Context): SecureSecrets {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureSecrets(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "secrets",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Throwable) {
        // Fallback if encrypted prefs fail (e.g. after backup restore on a new device where MasterKey differs)
        try {
            context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().clear().apply()
            EncryptedSharedPreferences.create(
                context,
                "secrets",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            context.getSharedPreferences("secrets_fallback", Context.MODE_PRIVATE)
        }
    }
    
    var mqttUsername: String
        get() = prefs.getString("mqtt_user", "") ?: ""
        set(v) = prefs.edit().putString("mqtt_user", v).apply()
    var mqttPassword: String
        get() = prefs.getString("mqtt_pass", "") ?: ""
        set(v) = prefs.edit().putString("mqtt_pass", v).apply()
    var smbUsername: String
        get() = prefs.getString("smb_user", "") ?: ""
        set(v) = prefs.edit().putString("smb_user", v).apply()
    var smbPassword: String
        get() = prefs.getString("smb_pass", "") ?: ""
        set(v) = prefs.edit().putString("smb_pass", v).apply()
}

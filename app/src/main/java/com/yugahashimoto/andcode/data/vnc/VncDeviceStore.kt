package com.yugahashimoto.andcode.data.vnc

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores the saved VNC devices. Credentials (the VNC password) are kept inside
 * [EncryptedSharedPreferences] like the rest of the app's secrets.
 */
class VncDeviceStore(context: Context) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val preferences: SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    @Synchronized
    fun devices(): List<VncDevice> {
        val raw = preferences.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<VncDevice>>(raw) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun device(id: String): VncDevice? = devices().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(device: VncDevice) {
        val updated = devices().filterNot { it.id == device.id } + device
        preferences.edit().putString(KEY_DEVICES, json.encodeToString(updated)).apply()
    }

    @Synchronized
    fun delete(id: String) {
        val updated = devices().filterNot { it.id == id }
        preferences.edit().putString(KEY_DEVICES, json.encodeToString(updated)).apply()
    }

    private companion object {
        const val PREFS_NAME = "vnc_device_store"
        const val KEY_DEVICES = "vnc_devices"
    }
}

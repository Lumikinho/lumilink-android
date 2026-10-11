package com.lumilink.app.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("lumilink")

// DataStore (estado) + EncryptedSharedPreferences/Keystore (segredo).
class PairStore(private val ctx: Context) {
    private val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val secrets = EncryptedSharedPreferences.create(
        ctx, "secrets", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    companion object {
        val PC_ID = stringPreferencesKey("pc_device_id")
        val PC_NAME = stringPreferencesKey("pc_name")
        val PC_FP = stringPreferencesKey("pc_fingerprint")
        val PC_IP = stringPreferencesKey("pc_last_ip")
        val PC_PORT = intPreferencesKey("pc_port")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    }

    suspend fun savePair(deviceId: String, name: String, fp: String, ip: String, port: Int, secret: String) {
        ctx.dataStore.edit {
            it[PC_ID] = deviceId; it[PC_NAME] = name; it[PC_FP] = fp
            it[PC_IP] = ip; it[PC_PORT] = port
        }
        secrets.edit().putString("secret_$deviceId", secret).apply()
    }

    suspend fun getSecret(deviceId: String): String? = secrets.getString("secret_$deviceId", null)
    suspend fun lastKnown(): Triple<String, Int, String>? {
        val d = ctx.dataStore.data.first()
        val ip = d[PC_IP] ?: return null
        return Triple(ip, d[PC_PORT] ?: 52431, d[PC_FP] ?: "")
    }
    suspend fun pcId(): String? = ctx.dataStore.data.map { it[PC_ID] }.first()
    suspend fun forget() {
        val id = pcId()
        ctx.dataStore.edit { it.clear() }
        if (id != null) secrets.edit().remove("secret_$id").apply()
    }
    suspend fun setStartOnBoot(v: Boolean) { ctx.dataStore.edit { it[START_ON_BOOT] = v } }
    suspend fun startOnBoot(): Boolean = ctx.dataStore.data.map { it[START_ON_BOOT] == true }.first()
}

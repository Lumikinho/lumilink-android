package com.lumilink.app.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.cfg by preferencesDataStore("lumilink_cfg")

// deviceId UUID (migra o id legado) + porta aleatória persistida + boot.
class AppSettings(private val ctx: Context) {
    companion object {
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val PORT = intPreferencesKey("port")
        private val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val CLIP_SYNC = booleanPreferencesKey("clip_sync")
    }

    // UUID v4 obrigatório. Id legado (não-UUID) migra uma vez para UUID novo.
    suspend fun deviceId(): String {
        val p = ctx.cfg.data.first()
        val cur = p[DEVICE_ID]
        if (!cur.isNullOrBlank()) {
            try { UUID.fromString(cur); return cur } catch (e: Exception) { }
        }
        val legacy = ctx.getSharedPreferences("dev", Context.MODE_PRIVATE).getString("deviceId", null)
        val id = try {
            if (legacy.isNullOrBlank()) throw IllegalArgumentException()
            UUID.fromString(legacy); legacy
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
        ctx.cfg.edit { it[DEVICE_ID] = id }
        return id
    }

    suspend fun port(): Int {
        val p = ctx.cfg.data.first()
        p[PORT]?.takeIf { it > 0 }?.let { return it }
        val port = (40000..50000).random()
        ctx.cfg.edit { it[PORT] = port }
        return port
    }

    suspend fun setStartOnBoot(v: Boolean) { ctx.cfg.edit { it[START_ON_BOOT] = v } }
    suspend fun startOnBoot(): Boolean = ctx.cfg.data.map { it[START_ON_BOOT] == true }.first()
    suspend fun setClipSync(v: Boolean) { ctx.cfg.edit { it[CLIP_SYNC] = v } }
    suspend fun clipSync(): Boolean = ctx.cfg.data.map { it[CLIP_SYNC] != false }.first()
    fun clipSyncBlocking(): Boolean = kotlinx.coroutines.runBlocking { clipSync() }
}

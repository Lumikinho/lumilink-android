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
    }

    suspend fun deviceId(): String {
        val p = ctx.cfg.data.first()
        p[DEVICE_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        val legacy = ctx.getSharedPreferences("dev", Context.MODE_PRIVATE).getString("deviceId", null)
        val id = if (!legacy.isNullOrBlank()) legacy else UUID.randomUUID().toString()
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
}

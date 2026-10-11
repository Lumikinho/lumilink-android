package com.lumilink.app.storage

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PeerRec(
    val deviceId: String,
    val name: String,
    val certFp: String,
    val lastIp: String,
    val lastPort: Int,
    val photoHash: String,
    val lastSeen: Long,
    val knownIps: String = "",
)

// Vários pares: metadados em peers.json + segredos no Keystore (um por par).
class PeerStore(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val file = File(appCtx.filesDir, "peers.json")
    private val secrets = EncryptedSharedPreferences.create(
        appCtx, "sec_peers",
        MasterKey.Builder(appCtx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val lockObj = Any()

    fun all(): List<PeerRec> = synchronized(lockObj) {
        if (!file.exists()) return emptyList()
        try {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PeerRec(o.getString("deviceId"), o.optString("name", o.getString("deviceId")),
                    o.optString("certFp", ""), o.optString("lastIp", ""), o.optInt("lastPort", 0),
                    o.optString("photoHash", "empty"), o.optLong("lastSeen", 0),
                    o.optString("knownIps", ""))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun get(id: String): PeerRec? = all().firstOrNull { it.deviceId == id }
    fun isPaired(id: String): Boolean = get(id) != null

    fun savePeer(r: PeerRec, secretPlain: String? = null) = synchronized(lockObj) {
        val list = all().filter { it.deviceId != r.deviceId } + r.copy(
            lastSeen = System.currentTimeMillis())
        persist(list)
        if (secretPlain != null)
            secrets.edit().putString("s_${r.deviceId}",
                Base64.encodeToString(secretPlain.toByteArray(), Base64.NO_WRAP)).apply()
    }

    fun touch(id: String, ip: String, port: Int, knownIps: String = "") {
        val r = get(id) ?: return
        savePeer(r.copy(
            lastIp = ip.ifEmpty { r.lastIp },
            lastPort = if (port != 0) port else r.lastPort,
            knownIps = knownIps.ifEmpty { r.knownIps }))
    }

    fun updateProfile(id: String, name: String, photoHash: String) {
        val r = get(id) ?: return
        savePeer(r.copy(name = name, photoHash = photoHash))
    }

    fun getSecret(id: String): String? {
        val b64 = secrets.getString("s_$id", null) ?: return null
        return try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (e: Exception) { null }
    }

    fun forget(id: String) = synchronized(lockObj) {
        persist(all().filter { it.deviceId != id })
        secrets.edit().remove("s_$id").apply()
        File(appCtx.filesDir, "peer_$id.png").delete()
    }

    private fun persist(list: List<PeerRec>) {
        val arr = JSONArray()
        for (r in list) arr.put(JSONObject()
            .put("deviceId", r.deviceId).put("name", r.name).put("certFp", r.certFp)
            .put("lastIp", r.lastIp).put("lastPort", r.lastPort)
            .put("photoHash", r.photoHash).put("lastSeen", r.lastSeen)
            .put("knownIps", r.knownIps))
        file.writeText(arr.toString())
    }
}

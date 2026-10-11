package com.lumilink.app.pairing

import org.json.JSONObject

data class QrPayload(
    val ip: String, val port: Int, val token: String,
    val fingerprint: String, val deviceId: String,
) {
    companion object {
        fun parse(raw: String): QrPayload? = try {
            val o = JSONObject(raw)
            QrPayload(
                o.getString("ip"), o.getInt("port"), o.getString("token"),
                o.getString("fingerprint"), o.getString("deviceId"),
            )
        } catch (e: Exception) { null }
    }
}

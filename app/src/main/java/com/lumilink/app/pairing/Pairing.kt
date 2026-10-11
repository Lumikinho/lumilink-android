package com.lumilink.app.pairing

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.security.SecureRandom

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

        fun toJson(ip: String, port: Int, token: String, fp: String, id: String): String =
            JSONObject().put("ip", ip).put("port", port).put("token", token)
                .put("fingerprint", fp).put("deviceId", id).toString()

        fun toBitmap(json: String, size: Int = 512): Bitmap {
            val m = QRCodeWriter().encode(json, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size)
                bmp.setPixel(x, y, if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            return bmp
        }
    }
}

// Credencial de uso único (2 min): token (QR) + código 6 dígitos (manual).
class Pairing(private val log: (String) -> Unit) {
    data class Cred(val token: String, val code: String, val expiry: Long)

    @Volatile private var pending: Cred? = null

    @Synchronized
    fun newPairing(ttlMs: Long = 120_000): Pair<String, String> {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val code = "%06d".format(SecureRandom().nextInt(1_000_000))
        pending = Cred(token, code, System.currentTimeMillis() + ttlMs)
        log("[pair] credencial gerada (token + código 6 dígitos, 2 min).")
        return token to code
    }

    @Synchronized
    fun tryConsume(v: String?): Boolean {
        if (v.isNullOrEmpty()) return false
        val p = pending ?: return false
        if (System.currentTimeMillis() > p.expiry) {
            pending = null
            log("[pair] credencial expirada.")
            return false
        }
        val ok = v == p.token || v == p.code
        if (ok) pending = null
        return ok
    }

    companion object {
        fun newSecret(): String {
            val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        }
        fun newNonce(): String {
            val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return b.joinToString("") { "%02x".format(it) }
        }
        fun hmac(secretB64: String, nonce: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(
                android.util.Base64.decode(secretB64, android.util.Base64.NO_WRAP), "HmacSHA256"))
            return mac.doFinal(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        // IP LAN preferido (evita VPN/virtual).
        fun localIp(): String {
            var fallback: String? = null
            try {
                val ifs = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                for (ni in ifs) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = (ni.name + " " + (ni.displayName ?: "")).lowercase()
                    if (listOf("tailscale", "docker", "veth", "vmware", "virtualbox", "vpn",
                            "tun", "tap", "wireguard", "rmnet", "ccmni").any { name.contains(it) }) continue
                    for (addr in java.util.Collections.list(ni.inetAddresses)) {
                        if (addr !is java.net.Inet4Address || addr.isLoopbackAddress) continue
                        val b = addr.address.map { it.toInt() and 0xFF }
                        if (b[0] == 169 && b[1] == 254) continue
                        if (b[0] == 100 && b[1] in 64..127) continue
                        val rfc = b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
                        if (rfc) return addr.hostAddress ?: continue
                        if (fallback == null) fallback = addr.hostAddress
                    }
                }
            } catch (e: Exception) { }
            return fallback ?: "127.0.0.1"
        }
    }
}

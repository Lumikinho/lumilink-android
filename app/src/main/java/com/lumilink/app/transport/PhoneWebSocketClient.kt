package com.lumilink.app.transport

import com.lumilink.app.messaging.Message
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

// OkHttp com certificate pinning MANUAL via fingerprint do QR (wss autoassinado).
// Camada Transport: só conecta/envia/recebe; auth e ping ficam no Service.
class PhoneWebSocketClient(
    private val log: (String) -> Unit,
) {
    private val clientBase = OkHttpClient()
    private var ws: WebSocket? = null

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun connect(
        ip: String, port: Int,
        expectedFingerprint: String,
        onMessage: (String) -> Unit,
        onFailure: (String) -> Unit,
        onOpen: () -> Unit,
    ) {
        val trustAllWithPin = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = sha256Hex(chain[0].encoded)
                if (!fp.equals(expectedFingerprint, ignoreCase = true))
                    throw SSLException("fingerprint mismatch: $fp")
                log("[tls] pinning ok (${fp.take(12)}…)")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllWithPin), java.security.SecureRandom())
        }
        val client = clientBase.newBuilder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS) // falha rápido p/ tentar próximo candidato
            .sslSocketFactory(ssl.socketFactory, trustAllWithPin)
            .hostnameVerifier { _, _ -> true } // IP local; identidade = fingerprint, não hostname
            .build()
        val req = Request.Builder().url("wss://$ip:$port/ws").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { log("[net] wss conectado."); onOpen() }
            override fun onMessage(w: WebSocket, text: String) { onMessage(text) }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                log("[net] falha: ${t.message}"); onFailure(t.message ?: "ws-failure")
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                log("[net] fechado: $reason"); onFailure("closed: $reason")
            }
        })
    }

    fun send(msg: Message) {
        ws?.send(msg.toJson())
    }

    fun close() {
        try {
            ws?.close(1000, "bye")
        } catch (e: Exception) {
            log("[net] erro ao fechar: ${e.message}")
        }
        ws = null
    }
}

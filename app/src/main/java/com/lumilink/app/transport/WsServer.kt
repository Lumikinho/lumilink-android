package com.lumilink.app.transport

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

// Servidor wss mínimo sobre SSLServerSocket: handshake HTTP Upgrade + frames texto.
// O pump de leitura NUNCA bloqueia no handshake (hello é tratado em background,
// igual ao PC) — senão a resposta auth nunca seria roteada (deadlock).
class WsConn(val socket: SSLSocket, val remoteIp: String) {
    @Volatile var open = true

    suspend fun send(text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = socket.outputStream
        synchronized(out) {
            out.write(0x81)
            when {
                bytes.size <= 125 -> out.write(bytes.size)
                bytes.size <= 65535 -> {
                    out.write(126)
                    out.write(bytes.size shr 8); out.write(bytes.size and 0xFF)
                }
                else -> {
                    out.write(127)
                    val len = bytes.size.toLong()
                    for (i in 7 downTo 0) out.write((len shr (i * 8)).toInt() and 0xFF)
                }
            }
            out.write(bytes)
            out.flush()
        }
    }

    fun close() {
        open = false
        try {
            val out = socket.outputStream
            synchronized(out) { out.write(byteArrayOf(0x88.toByte(), 0x00)); out.flush() }
        } catch (e: Exception) { }
        try { socket.close() } catch (e: Exception) { }
    }
}

class WsServer(
    private val ssl: SSLContext,
    private val port: Int,
    private val log: (String) -> Unit,
) {
    var onText: (suspend (WsConn, String) -> Unit)? = null
    var onClosed: ((WsConn) -> Unit)? = null
    private var server: javax.net.ssl.SSLServerSocket? = null
    private var acceptJob: Job? = null

    fun start(scope: CoroutineScope) {
        val srv = (ssl.serverSocketFactory.createServerSocket(port) as javax.net.ssl.SSLServerSocket)
            .apply { needClientAuth = false }
        server = srv
        log("[net] wss ouvindo em 0.0.0.0:$port/ws")
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val s = srv.accept() as SSLSocket
                    s.soTimeout = 0
                    launch { handle(s) }
                } catch (e: Exception) {
                    if (isActive) log("[net] accept: ${e.message}")
                    else break
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        try { server?.close() } catch (e: Exception) { }
    }

    private suspend fun handle(s: SSLSocket) {
        val ip = (s.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress ?: ""
        val norm = if (ip.startsWith("::ffff:")) ip.removePrefix("::ffff:") else ip
        val conn: WsConn
        try {
            withContext(Dispatchers.IO) { s.startHandshake() }
            val inp = s.inputStream
            val head = withContext(Dispatchers.IO) { readHead(inp) }
            if (!isWsUpgrade(head)) { try { s.close() } catch (e: Exception) { }; return }
            val key = head.headers["sec-websocket-key"] ?: return s.close()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            val resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
            withContext(Dispatchers.IO) {
                s.outputStream.write(resp.toByteArray()); s.outputStream.flush()
            }
            conn = WsConn(s, norm)
            log("[net] par conectou (tcp/ws).")
        } catch (e: Exception) {
            try { s.close() } catch (e2: Exception) { }
            return
        }
        try {
            var frag = StringBuilder()
            while (conn.open) {
                val (fin, op, payload) = withContext(Dispatchers.IO) { readFrame(s.inputStream) }
                    ?: break
                when (op) {
                    0x8 -> break // close
                    0x9 -> conn.send("") // ping → pong vazio (protocolo usa ping/pong JSON)
                    0xA -> {} // pong
                    0x1, 0x0 -> {
                        frag.append(String(payload, Charsets.UTF_8))
                        if (fin) {
                            val text = frag.toString()
                            frag = StringBuilder()
                            onText?.invoke(conn, text)
                        }
                    }
                    else -> {} // binário ignorado
                }
            }
        } catch (e: Exception) {
            if (e !is EOFException && e !is SocketTimeoutException) log("[net] loop: ${e.message}")
        } finally {
            conn.close()
            onClosed?.invoke(conn)
        }
    }

    private data class Head(val requestLine: String, val headers: Map<String, String>)

    private fun readHead(inp: InputStream): Head {
        val buf = ByteArrayOutputStream()
        val tail = ArrayDeque<Byte>()
        while (true) {
            val b = inp.read()
            if (b < 0) throw EOFException("fim antes do head")
            buf.write(b)
            tail.addLast(b.toByte())
            if (tail.size > 4) tail.removeFirst()
            if (tail.size == 4 && tail[0] == 13.toByte() && tail[1] == 10.toByte() &&
                tail[2] == 13.toByte() && tail[3] == 10.toByte()) break
            if (buf.size() > 16 * 1024) throw IllegalStateException("head grande")
        }
        val lines = String(buf.toByteArray(), Charsets.UTF_8).split("\r\n")
        val headers = mutableMapOf<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return Head(lines.firstOrNull() ?: "", headers)
    }

    private fun isWsUpgrade(h: Head): Boolean {
        if (!h.requestLine.contains("/ws")) return false
        return (h.headers["upgrade"] ?: "").contains("websocket", ignoreCase = true)
    }

    private data class Frame(val fin: Boolean, val op: Int, val payload: ByteArray)

    private fun readFrame(inp: InputStream): Frame? {
        val b0 = inp.read()
        if (b0 < 0) return null
        val b1 = inp.read()
        if (b1 < 0) return null
        val fin = b0 and 0x80 != 0
        val op = b0 and 0x0F
        val masked = b1 and 0x80 != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = (readN(inp, 2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }).toLong()
        else if (len == 127L) {
            val ext = readN(inp, 8)
            len = 0
            for (b in ext) len = (len shl 8) or (b.toLong() and 0xFF)
        }
        if (len > 2 * 1024 * 1024) throw IllegalStateException("frame grande")
        val key = if (masked) readN(inp, 4) else null
        val payload = if (len > 0) readN(inp, len.toInt()) else ByteArray(0)
        if (key != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor key[i % 4].toInt()).toByte()
        return Frame(fin, op, payload)
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(out, off, n - off)
            if (r < 0) throw EOFException("frame incompleto")
            off += r
        }
        return out
    }
}

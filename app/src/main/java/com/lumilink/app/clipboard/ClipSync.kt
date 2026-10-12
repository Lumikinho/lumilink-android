package com.lumilink.app.clipboard

import android.content.Context
import android.util.Base64
import com.lumilink.app.messaging.Message
import com.lumilink.app.peers.PeerManager
import com.lumilink.app.storage.ClipStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Sincroniza clipboard via MessageRouter (tipos clip_*). Espelho do ClipSync do PC.
class ClipSync(
    private val appCtx: Context,
    private val scope: CoroutineScope,
    private val router: com.lumilink.app.messaging.MessageRouter,
    private val manager: PeerManager,
    private val store: ClipStore,
    private val monitor: ClipMonitor,
    private val selfId: String,
    private val isEnabled: () -> Boolean,
    private val peerName: (String) -> String,
    private val notify: (String, String) -> Unit,
    private val log: (String) -> Unit,
) {
    private data class InFile(
        val file: File, val out: java.io.OutputStream, val name: String,
        val size: Long, val sha: String, var expected: Int = 0,
        var last: Long = System.currentTimeMillis(),
    )

    private val accepts = ConcurrentHashMap<String, MutableSet<String>>()
    private val acceptEvents = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val incoming = ConcurrentHashMap<String, InFile>()

    init {
        monitor.onChange = { c -> scope.launch { onLocal(c) } }
        register()
        scope.launch {
            while (isActive) {
                delay(30_000)
                val now = System.currentTimeMillis()
                for ((k, v) in incoming) {
                    if (now - v.last > 60_000 && incoming.remove(k) != null) {
                        try { v.out.close(); v.file.delete() } catch (e: Exception) { }
                        log("[clip] sessão expirada ($k).")
                    }
                }
            }
        }
    }

    private suspend fun onLocal(c: ClipContent) {
        try {
            if (!isEnabled()) return
            if (c.text != null) {
                monitor.suppress(c.hash)
                store.addText(c.text, selfId, "*")
                manager.broadcast(Message.create("clip_text", JSONObject()
                    .put("text", c.text).put("ts", System.currentTimeMillis()).put("source", selfId)))
                log("[clip] texto difundido (${c.text.length} ch).")
            } else if (c.imageBytes != null) {
                monitor.suppress(c.hash)
                val f = File(store.clipDir, "clip-${System.currentTimeMillis()}.jpg")
                f.writeBytes(c.imageBytes)
                store.insert("image", mime = "image/jpeg", path = f.absolutePath,
                    size = c.imageBytes.size.toLong(), sha = c.hash, source = selfId, peer = "*")
                manager.broadcast(Message.create("clip_image", JSONObject()
                    .put("mime", "image/jpeg")
                    .put("dataBase64", Base64.encodeToString(c.imageBytes, Base64.NO_WRAP))
                    .put("hash", c.hash).put("w", 0).put("h", 0)))
                log("[clip] imagem difundida (${c.imageBytes.size / 1024} KB).")
            } else if (c.files.isNotEmpty()) {
                for (f in c.files) scope.launch { sendFile(f) }
            }
        } catch (e: Exception) { log("[clip] envio falhou: ${e.message}") }
    }

    private suspend fun sendFile(f: UriInfo) {
        if (f.size <= 0 || f.size > 200L * 1024 * 1024) {
            log("[clip] arquivo inválido/grande."); return
        }
        // sha sob demanda (stream)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        try {
            monitor.openStream(f.uri)?.use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            } ?: return
        } catch (e: Exception) { log("[clip] leitura falhou."); return }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        val fileId = UUID.randomUUID().toString().replace("-", "").take(16)
        val set = mutableSetOf<String>()
        val ev = CompletableDeferred<Unit>()
        accepts[fileId] = set
        acceptEvents[fileId] = ev
        try {
            manager.broadcast(Message.create("clip_file_offer", JSONObject()
                .put("fileId", fileId).put("name", f.name)
                .put("size", f.size).put("sha256", hex)))
            log("[clip] oferta ${f.name} (${f.size / 1024} KB)…")
            try {
                withTimeout(30_000) { ev.await() }
            } catch (e: TimeoutCancellationException) { }
            delay(2000)
            val who = synchronized(set) { set.toList() }
            if (who.isEmpty()) { log("[clip] ninguém aceitou."); return }
            monitor.openStream(f.uri)?.use { inp ->
                val buf = ByteArray(32 * 1024)
                var seq = 0
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    val b64 = Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)
                    for (id in who)
                        manager.sendTo(id, Message.create("clip_file_chunk", JSONObject()
                            .put("fileId", fileId).put("seq", seq).put("data", b64)))
                    seq++
                }
                for (id in who)
                    manager.sendTo(id, Message.create("clip_file_done", JSONObject()
                        .put("fileId", fileId).put("sha256", hex)))
                log("[clip] ${f.name} enviado ($seq chunks).")
            }
        } catch (e: Exception) { log("[clip] envio falhou: ${e.message}") }
        finally { accepts.remove(fileId); acceptEvents.remove(fileId) }
    }

    private fun register() {
        router.register("clip_text") { c, m ->
            val id = c.deviceId
            if (!isEnabled() || !c.authenticated || id == null) return@register Unit
            try {
                val text = m.payload.optString("text", "")
                if (text.isEmpty() || text.length > 256 * 1024) return@register Unit
                monitor.suppress(ClipMonitor.sha(text.toByteArray()))
                store.addText(text, id, id)
                monitor.setText(text)
                notify("Área de transferência", "Texto de ${peerName(id)}.")
                log("[clip] texto recebido e aplicado.")
            } catch (e: Exception) { log("[clip] texto ignorado: ${e.message}") }
            Unit
        }
        router.register("clip_image") { c, m ->
            val id = c.deviceId
            if (!isEnabled() || !c.authenticated || id == null) return@register Unit
            try {
                val b64 = m.payload.optString("dataBase64", "")
                if (b64.length > 4_500_000) { log("[clip] imagem grande, ignorada."); return@register Unit }
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val hash = m.payload.optString("hash", "")
                if (hash.isNotEmpty() && !hash.equals(ClipMonitor.sha(bytes), ignoreCase = true)) {
                    log("[clip] hash da imagem não confere."); return@register Unit
                }
                if (android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                    log("[clip] imagem inválida."); return@register Unit
                }
                val f = File(store.clipDir, "clip-${System.currentTimeMillis()}.jpg")
                f.writeBytes(bytes)
                monitor.suppress(ClipMonitor.sha(bytes))
                store.insert("image", mime = "image/jpeg", path = f.absolutePath,
                    size = bytes.size.toLong(), sha = hash, source = id, peer = id)
                monitor.copyImage(bytes)
                notify("Área de transferência", "Imagem de ${peerName(id)}.")
                log("[clip] imagem recebida.")
            } catch (e: Exception) { log("[clip] imagem ignorada: ${e.message}") }
            Unit
        }
        router.register("clip_file_offer") { c, m ->
            if (!isEnabled() || !c.authenticated || c.deviceId == null) return@register Unit
            try {
                val fileId = m.payload.optString("fileId", "")
                val name = m.payload.optString("name", fileId).take(120)
                val size = m.payload.optLong("size", 0)
                val sha = m.payload.optString("sha256", "")
                if (fileId.isEmpty() || size <= 0 || size > 200L * 1024 * 1024) return@register Unit
                val f = File(store.clipDir, "$fileId.part")
                incoming[fileId] = InFile(f, f.outputStream(), sanitizeName(name), size, sha)
                c.send(Message.create("clip_file_accept", JSONObject()
                    .put("fileId", fileId).put("accept", true)))
                log("[clip] oferta aceita: $name.")
            } catch (e: Exception) { log("[clip] oferta ignorada: ${e.message}") }
            Unit
        }
        router.register("clip_file_accept") { c, m ->
            try {
                val fileId = m.payload.optString("fileId", "")
                val id = c.deviceId
                if (m.payload.optBoolean("accept", false) && id != null) {
                    accepts[fileId]?.let {
                        synchronized(it) { it.add(id) }
                        acceptEvents[fileId]?.complete(Unit)
                    }
                }
            } catch (e: Exception) { }
            Unit
        }
        router.register("clip_file_chunk") { c, m ->
            if (!isEnabled() || !c.authenticated) return@register Unit
            try {
                val fileId = m.payload.optString("fileId", "")
                val seq = m.payload.optInt("seq", -1)
                val s = incoming[fileId] ?: return@register Unit
                if (seq != s.expected) { log("[clip] chunk fora de ordem."); return@register Unit }
                s.out.write(Base64.decode(m.payload.optString("data", ""), Base64.DEFAULT))
                s.expected++
                s.last = System.currentTimeMillis()
            } catch (e: Exception) { log("[clip] chunk ignorado: ${e.message}") }
            Unit
        }
        router.register("clip_file_done") { c, m ->
            val id = c.deviceId
            if (!c.authenticated || id == null) return@register Unit
            try {
                val fileId = m.payload.optString("fileId", "")
                val s = incoming.remove(fileId) ?: return@register Unit
                try {
                    s.out.close()
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    s.file.inputStream().use { inp ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    val actual = md.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(s.sha, ignoreCase = true) || s.file.length() != s.size) {
                        log("[clip] arquivo corrompido."); s.file.delete(); return@register Unit
                    }
                    val dst = unique(s.name)
                    s.file.renameTo(dst)
                    store.insert("file", path = dst.absolutePath, name = dst.name,
                        size = s.size, sha = s.sha, source = id, peer = id)
                    notify("Arquivo recebido", "${dst.name} de ${peerName(id)}.")
                    log("[clip] arquivo salvo: ${dst.name}.")
                } catch (e: Exception) {
                    try { s.out.close() } catch (e2: Exception) { }
                    s.file.delete()
                    log("[clip] done ignorado: ${e.message}")
                }
            } catch (e: Exception) { log("[clip] done ignorado: ${e.message}") }
            Unit
        }
    }

    private fun sanitizeName(n: String): String {
        val base = n.substringAfterLast('/').substringAfterLast('\\').take(120)
        return base.filter { !it.isISOControl() }.ifEmpty { "arquivo" }
    }

    private fun unique(name: String): File {
        var f = File(store.clipDir, name)
        var i = 1
        while (f.exists() && i < 1000) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            f = File(store.clipDir, "$stem ($i)$ext")
            i++
        }
        return f
    }
}

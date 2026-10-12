package com.lumilink.app.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.lumilink.app.messaging.Message
import com.lumilink.app.peers.PeerManager
import com.lumilink.app.storage.ClipStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Envio EXPLÍCITO de arquivos (botão — não via clipboard).
class FileTransfer(
    private val appCtx: Context,
    private val router: com.lumilink.app.messaging.MessageRouter,
    private val manager: PeerManager,
    private val store: ClipStore,
    private val peerName: (String) -> String,
    private val notify: (String, String) -> Unit,
    private val log: (String) -> Unit,
) {
    private data class InFile(
        val file: File, val out: java.io.OutputStream, val name: String,
        val size: Long, val sha: String, var expected: Int = 0,
        var last: Long = System.currentTimeMillis(),
        val pending: java.util.SortedMap<Int, ByteArray> = java.util.TreeMap(),
    )

    private val sendingGuards = ConcurrentHashMap<String, java.util.concurrent.Semaphore>()

    private val accepts = ConcurrentHashMap<String, MutableSet<String>>()
    private val acceptEvents = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val incoming = ConcurrentHashMap<String, InFile>()

    init {
        router.register("clip_file_offer") { c, m -> onOffer(c, m) }
        router.register("clip_file_accept") { c, m -> onAccept(c, m) }
        router.register("clip_file_chunk") { c, m -> onChunk(c, m) }
        router.register("clip_file_done") { c, m -> onDone(c, m) }
        router.register("clip_file_abort") { c, m -> onAbort(c, m) }
    }

    suspend fun sendFile(peerId: String, uri: Uri) {
        val gate = sendingGuards.getOrPut(peerId) { java.util.concurrent.Semaphore(1) }
        if (!gate.tryAcquire()) {
            log("[file] já enviando para este par.")
            return
        }
        try {
            sendFileInner(peerId, uri)
        } finally {
            gate.release()
        }
    }

    private suspend fun sendFileInner(peerId: String, uri: Uri) {
        val resolver = appCtx.contentResolver
        var name = uri.lastPathSegment ?: "arquivo"
        var size = -1L
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0) size = c.getLong(si)
                }
            }
        } catch (e: Exception) { }
        if (size <= 0 || size > 200L * 1024 * 1024) {
            log("[file] arquivo inválido/grande."); return
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri)?.use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            } ?: return
        } catch (e: Exception) { log("[file] leitura falhou."); return }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        val fileId = UUID.randomUUID().toString().replace("-", "").take(16)
        val set = mutableSetOf<String>()
        val ev = CompletableDeferred<Unit>()
        accepts[fileId] = set
        acceptEvents[fileId] = ev
        try {
            if (!manager.sendTo(peerId, Message.create("clip_file_offer", JSONObject()
                    .put("fileId", fileId).put("name", name)
                    .put("size", size).put("sha256", hex)))) {
                log("[file] sem conexão."); return
            }
            log("[file] oferta $name (${size / 1024} KB)…")
            try {
                withTimeout(30_000) { ev.await() }
            } catch (e: TimeoutCancellationException) { }
            delay(1500)
            val who = synchronized(set) { set.toList() }
            if (who.isEmpty()) { log("[file] ninguém aceitou."); return }
            // Fixa UM link p/ o stream inteiro (trocar no meio perde chunks).
            val link = manager.tryGetLink(peerId)
                ?: throw IOException("link caiu antes do stream.")
            try {
                resolver.openInputStream(uri)?.use { inp ->
                    val buf = ByteArray(32 * 1024)
                    var seq = 0
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        val b64 = Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)
                        try {
                            link.send(Message.create("clip_file_chunk", JSONObject()
                                .put("fileId", fileId).put("seq", seq).put("data", b64)))
                        } catch (e: Exception) {
                            throw IOException("link caiu no chunk $seq.")
                        }
                        seq++
                    }
                    try {
                        link.send(Message.create("clip_file_done", JSONObject()
                            .put("fileId", fileId).put("sha256", hex)))
                    } catch (e: Exception) { }
                    log("[file] $name enviado ($seq chunks).")
                }
            } catch (e: Exception) {
                try {
                    manager.sendTo(peerId, Message.create("clip_file_abort", JSONObject()
                        .put("fileId", fileId).put("reason", e.message ?: "?")))
                } catch (e2: Exception) { }
                throw e
            }
        } catch (e: Exception) { log("[file] envio falhou: ${e.message}") }
        finally { accepts.remove(fileId); acceptEvents.remove(fileId) }
    }

    private suspend fun onOffer(c: com.lumilink.app.messaging.MessageRouter.Ctx, m: Message) {
        if (!c.authenticated || c.deviceId == null) return
        try {
            val fileId = m.payload.optString("fileId", "")
            val name = m.payload.optString("name", fileId).take(120)
            val size = m.payload.optLong("size", 0)
            val sha = m.payload.optString("sha256", "")
            if (fileId.isEmpty() || size <= 0 || size > 200L * 1024 * 1024) return
            val cur = incoming[fileId]
            if (cur != null && cur.size == size && cur.sha == sha) return // oferta repetida
            cur?.let { try { it.out.close(); it.file.delete() } catch (e: Exception) { } }
            val f = File(store.clipDir, "$fileId.part")
            incoming[fileId] = InFile(f, f.outputStream(), sanitize(name), size, sha)
            c.send(Message.create("clip_file_accept", JSONObject()
                .put("fileId", fileId).put("accept", true)))
            log("[file] oferta aceita: $name.")
        } catch (e: Exception) { log("[file] oferta ignorada: ${e.message}") }
    }

    private suspend fun onAccept(c: com.lumilink.app.messaging.MessageRouter.Ctx, m: Message) {
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
    }

    private suspend fun onChunk(c: com.lumilink.app.messaging.MessageRouter.Ctx, m: Message) {
        if (!c.authenticated) return
        try {
            val fileId = m.payload.optString("fileId", "")
            val seq = m.payload.optInt("seq", -1)
            val s = incoming[fileId] ?: return
            val bytes = Base64.decode(m.payload.optString("data", ""), Base64.DEFAULT)
            val ok = synchronized(s) {
                if (seq < s.expected) false
                else if (seq == s.expected) {
                    s.out.write(bytes)
                    s.expected++
                    var nxt = s.pending.remove(s.expected)
                    while (nxt != null) {
                        s.out.write(nxt)
                        s.expected++
                        nxt = s.pending.remove(s.expected)
                    }
                    s.last = System.currentTimeMillis()
                    true
                } else {
                    if (s.pending.size >= 256 || s.pending.containsKey(seq)) false
                    else { s.pending[seq] = bytes; s.last = System.currentTimeMillis(); true }
                }
            }
            if (!ok) log("[clip] chunk $seq recusado (esperava ${s.expected}, buffer ${s.pending.size}).")
        } catch (e: Exception) { log("[clip] chunk ignorado: ${e.message}") }
    }

    private suspend fun onDone(c: com.lumilink.app.messaging.MessageRouter.Ctx, m: Message) {
        val id = c.deviceId
        if (!c.authenticated || id == null) return
        try {
            val fileId = m.payload.optString("fileId", "")
            val s = incoming.remove(fileId) ?: return
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
                        log("[file] corrompido: ${s.file.length()}/${s.size} bytes, " +
                            "hash ${actual.take(8)}… vs ${s.sha.take(8)}…")
                        s.file.delete(); return
                    }
                val dst = unique(s.name)
                s.file.renameTo(dst)
                store.insert("file", path = dst.absolutePath, name = dst.name,
                    size = s.size, sha = s.sha, source = id, peer = id)
                notify("Arquivo recebido", "${dst.name} de ${peerName(id)}.")
                log("[file] salvo: ${dst.name}.")
            } catch (e: Exception) {
                try { s.out.close() } catch (e2: Exception) { }
                s.file.delete()
                log("[file] done ignorado: ${e.message}")
            }
        } catch (e: Exception) { log("[file] done ignorado: ${e.message}") }
    }

    private suspend fun onAbort(c: com.lumilink.app.messaging.MessageRouter.Ctx, m: Message) {
        try {
            val fileId = m.payload.optString("fileId", "")
            val s = incoming.remove(fileId)
            if (s != null) {
                try { s.out.close() } catch (e: Exception) { }
                s.file.delete()
                log("[file] envio abortado pelo remetente ($fileId).")
            }
        } catch (e: Exception) { }
    }

    private fun sanitize(n: String): String {
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

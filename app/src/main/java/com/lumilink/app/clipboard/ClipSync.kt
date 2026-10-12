package com.lumilink.app.clipboard

import android.content.Context
import android.util.Base64
import com.lumilink.app.messaging.Message
import com.lumilink.app.peers.PeerManager
import com.lumilink.app.storage.ClipStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

// Sincroniza texto e imagem via MessageRouter (clip_text, clip_image).
// Arquivos em geral NÃO sincronizam. Espelho do ClipSync do PC.
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
    init {
        monitor.onChange = { c -> scope.launch { onLocal(c) } }
        register()
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
                ClipMonitor.avgHash(c.imageBytes)?.let { monitor.suppressImage(it) }
                val f = File(store.clipDir, "clip-${System.currentTimeMillis()}.jpg")
                f.writeBytes(c.imageBytes)
                store.insert("image", mime = "image/jpeg", path = f.absolutePath,
                    size = c.imageBytes.size.toLong(), sha = c.hash, source = selfId, peer = "*")
                manager.broadcast(Message.create("clip_image", JSONObject()
                    .put("mime", "image/jpeg")
                    .put("dataBase64", Base64.encodeToString(c.imageBytes, Base64.NO_WRAP))
                    .put("hash", c.hash).put("w", 0).put("h", 0)))
                log("[clip] imagem difundida (${c.imageBytes.size / 1024} KB).")
            }
            // URIs de arquivo: ignoradas de propósito (só texto e imagem).
        } catch (e: Exception) { log("[clip] envio falhou: ${e.message}") }
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
                ClipMonitor.avgHash(bytes)?.let { monitor.suppressImage(it) }
                store.insert("image", mime = "image/jpeg", path = f.absolutePath,
                    size = bytes.size.toLong(), sha = hash, source = id, peer = id)
                monitor.copyImage(bytes)
                notify("Área de transferência", "Imagem de ${peerName(id)}.")
                log("[clip] imagem recebida.")
            } catch (e: Exception) { log("[clip] imagem ignorada: ${e.message}") }
            Unit
        }
    }
}

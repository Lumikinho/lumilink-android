package com.lumilink.app.screen

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import com.lumilink.app.messaging.Message
import com.lumilink.app.messaging.MessageRouter
import com.lumilink.app.peers.PeerManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Captura a própria tela (MediaProjection) e transmite H.264 via screen_*.
// Sem root; consome bateria — parar ao terminar (botão Parar ou queda do par).
class ScreenSharer(
    private val appCtx: Context,
    private val scope: CoroutineScope,
    private val router: MessageRouter,
    private val manager: PeerManager,
    private val peerName: (String) -> String,
    private val log: (String) -> Unit,
) {
    private val acceptEvents = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var running = false
    @Volatile private var targetPeer = ""
    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var drainJob: Job? = null
    private val seq = AtomicInteger(0)

    companion object {
        const val MAX_PART = 48 * 1024
    }

    init {
        router.register("screen_accept") { c, m ->
            try {
                if (m.payload.optBoolean("accept", false))
                    acceptEvents[m.payload.optString("forId", "")]?.complete(Unit)
            } catch (e: Exception) { }
            Unit
        }
    }

    fun isRunning(): Boolean = running

    suspend fun start(peerId: String, resultCode: Int, data: Intent) {
        if (running) return
        val mgr = appCtx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            log("[tela] permissão negada."); return
        }
        try {
            // Android 14+: callback obrigatório antes de capturar.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    scope.launch { stop() }
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) {
            log("[tela] callback falhou: ${e.message}"); return
        }
        val metrics = appCtx.resources.displayMetrics
        val w = 720
        val h = (720f * metrics.heightPixels / metrics.widthPixels).toInt().coerceAtMost(1280)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val enc = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            log("[tela] encoder indisponível: ${e.message}"); return
        }
        val surface = try {
            enc.createInputSurface()
        } catch (e: Exception) {
            try { enc.release() } catch (e2: Exception) { }
            log("[tela] surface falhou."); return
        }
        try {
            enc.start()
            proj.createVirtualDisplay("LumiLink", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        } catch (e: Exception) {
            try { enc.release() } catch (e2: Exception) { }
            try { proj.stop() } catch (e2: Exception) { }
            log("[tela] captura falhou: ${e.message}"); return
        }
        projection = proj
        encoder = enc
        targetPeer = peerId
        running = true
        // oferta + espera aceite (30s)
        val ev = CompletableDeferred<Unit>()
        acceptEvents[peerId] = ev
        try {
            if (!manager.sendTo(peerId, Message.create("screen_offer", JSONObject()
                    .put("w", w).put("h", h).put("fps", 30)))) {
                log("[tela] sem conexão."); stop(); return
            }
            log("[tela] oferta enviada p/ ${peerName(peerId)}…")
            try {
                withTimeout(30_000) { ev.await() }
            } catch (e: TimeoutCancellationException) {
                log("[tela] ninguém aceitou."); stop(); return
            }
            log("[tela] transmitindo p/ ${peerName(peerId)}.")
            drainJob = scope.launch(Dispatchers.IO) { drainLoop(peerId) }
        } finally {
            acceptEvents.remove(peerId)
        }
    }

    private suspend fun drainLoop(peerId: String) {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = try {
                    enc.dequeueOutputBuffer(info, 10_000)
                } catch (e: Exception) { break }
                when {
                    idx >= 0 -> {
                        try {
                            val buf = enc.getOutputBuffer(idx)
                            if (buf == null) {
                                enc.releaseOutputBuffer(idx, false)
                            } else {
                                val data = ByteArray(info.size)
                                buf.position(info.offset)
                                buf.get(data)
                                enc.releaseOutputBuffer(idx, false)
                                if (data.isNotEmpty()) {
                                    val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    sendAccessUnit(peerId, data, key)
                                }
                            }
                        } catch (e: Exception) { break }
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try {
                            val fmt = enc.outputFormat
                            val csd0 = fmt.getByteBuffer("csd-0")
                            val csd1 = fmt.getByteBuffer("csd-1")
                            if (csd0 != null && csd1 != null) {
                                sendRaw(peerId, -1, toBytes(csd0), key = true)
                                sendRaw(peerId, -2, toBytes(csd1), key = true)
                            }
                        } catch (e: Exception) { }
                    }
                    else -> {}
                }
            }
        } finally {
            stop()
        }
    }

    private fun toBytes(b: ByteBuffer): ByteArray {
        val out = ByteArray(b.remaining())
        b.get(out)
        return out
    }

    private suspend fun sendRaw(peerId: String, seq: Int, data: ByteArray, key: Boolean) {
        var off = 0
        var part = 0
        val total = maxOf(1, (data.size + MAX_PART - 1) / MAX_PART)
        try {
            do {
                val end = minOf(off + MAX_PART, data.size)
                val chunk = if (data.isEmpty()) ByteArray(0) else data.copyOfRange(off, end)
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val ok = manager.sendTo(peerId, Message.create("screen_data", JSONObject()
                    .put("seq", seq).put("part", part).put("parts", total)
                    .put("key", key).put("data", b64)))
                if (!ok) throw java.io.IOException("link caiu.")
                off = end
                part++
            } while (off < data.size)
        } catch (e: Exception) {
            log("[tela] link caiu; parando.")
            stop()
            throw e
        }
    }

    private suspend fun sendAccessUnit(peerId: String, data: ByteArray, key: Boolean) {
        val s = seq.getAndIncrement()
        sendRaw(peerId, s, data, key)
    }

    fun stop() {
        if (!running) return
        running = false
        try { drainJob?.cancel() } catch (e: Exception) { }
        try { encoder?.stop() } catch (e: Exception) { }
        try { encoder?.release() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        encoder = null
        projection = null
        scope.launch {
            try {
                if (targetPeer.isNotEmpty())
                    manager.sendTo(targetPeer, Message.create("screen_stop"))
            } catch (e: Exception) { }
        }
        log("[tela] transmissão parada.")
    }
}

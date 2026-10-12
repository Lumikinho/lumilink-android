package com.lumilink.app.ui

import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumilink.app.ConnectionService
import com.lumilink.app.screen.ScreenHub
import kotlinx.coroutines.*
import java.nio.ByteBuffer

// Assiste a tela do par: decodifica H.264 e renderiza (somente ver).
class ScreenWatchActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var decoder: MediaCodec? = null
    private var surface: android.view.Surface? = null
    private var configured = false
    private var pump: Job? = null
    private var peerName by mutableStateOf("")
    private var fedCount = 0
    private var renderedCount = 0

    private fun alog(s: String) = android.util.Log.i("LumiLink", "[tela] $s")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peerName = ScreenHub.fromName.ifEmpty { "par" }
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Text("Tela de $peerName", style = MaterialTheme.typography.titleMedium)
                    AndroidView(factory = { ctx ->
                        SurfaceView(ctx).also { sv ->
                            sv.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {
                                    surface = h.surface
                                    startPump()
                                }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                    surface = null
                                }
                            })
                        }
                    }, modifier = Modifier.fillMaxSize().weight(1f))
                    Button(onClick = { finish() }) { Text("Fechar") }
                }
            }
        }
    }

    private fun startPump() {
        if (pump?.isActive == true) return
        pump = scope.launch {
            try {
                var csd: Pair<ByteArray, ByteArray>? = null
                repeat(200) {
                    csd = ScreenHub.takeCsd()
                    if (csd != null) return@repeat
                    delay(100)
                }
                val (a, b) = csd ?: return@launch
                alog("csd ${a.size + b.size}b, surface=${surface != null}.")
                configure(a, b)
                while (isActive) {
                    val f = ScreenHub.frames.poll()
                    if (f == null) {
                        if (!ScreenHub.active) break
                        delay(5)
                        continue
                    }
                    if (!ScreenHub.active) break
                    if (f.seq < 0) continue
                    feed(f)
                }
            } catch (e: CancellationException) { }
        }
    }

    private fun feed(f: ScreenHub.Frame) {
        try {
            if (f.seq < 0) return
            val dec = decoder ?: return
            val idx = dec.dequeueInputBuffer(10_000)
            if (idx < 0) return
            val buf = dec.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(f.data)
            dec.queueInputBuffer(idx, 0, f.data.size, System.nanoTime() / 1000, 0)
            fedCount++
            if (fedCount == 1 || fedCount % 60 == 0) alog("frames alimentados=$fedCount.")
            drain()
        } catch (e: Exception) { }
    }

    private fun configure(csd0: ByteArray, csd1: ByteArray) {
        try {
            val surf = surface ?: return
            if (csd0.isEmpty() || csd1.isEmpty()) return
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, ScreenHub.width, ScreenHub.height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
            decoder?.release()
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            dec.configure(format, surf, null, 0)
            dec.start()
            decoder = dec
            configured = true
            alog("decoder ok ${ScreenHub.width}x${ScreenHub.height}.")
        } catch (e: Exception) { configured = false; alog("decoder falhou: ${e.message}") }
    }

    private fun drain() {
        try {
            val dec = decoder ?: return
            val info = MediaCodec.BufferInfo()
            repeat(4) {
                val idx = dec.dequeueOutputBuffer(info, 0)
                if (idx >= 0) {
                    dec.releaseOutputBuffer(idx, true)
                    renderedCount++
                    if (renderedCount == 1 || renderedCount % 60 == 0)
                        alog("quadros na tela=$renderedCount.")
                } else return
            }
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        try { pump?.cancel() } catch (e: Exception) { }
        try { decoder?.stop() } catch (e: Exception) { }
        try { decoder?.release() } catch (e: Exception) { }
        decoder = null
        scope.cancel()
        super.onDestroy()
    }
}

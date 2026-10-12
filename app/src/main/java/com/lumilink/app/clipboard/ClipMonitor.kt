package com.lumilink.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.security.MessageDigest

data class UriInfo(val name: String, val mime: String, val size: Long, val uri: String)
data class ClipContent(
    val text: String?,
    val imageBytes: ByteArray?,
    val imageMime: String,
    val files: List<UriInfo>,
    val hash: String,
)

// Monitor via listener do sistema (sem polling). Leitura pesada em IO.
class ClipMonitor(
    private val ctx: Context,
    private val scope: CoroutineScope,
) {
    private val appCtx = ctx.applicationContext
    private val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val recent = mutableMapOf<String, Long>()
    private var lastHash = ""

    var onChange: ((ClipContent) -> Unit)? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        scope.launch(Dispatchers.IO) { read() }
    }

    fun start() {
        try { cm.addPrimaryClipChangedListener(listener) } catch (e: Exception) { }
    }
    fun stop() {
        try { cm.removePrimaryClipChangedListener(listener) } catch (e: Exception) { }
    }

    @Synchronized
    fun suppress(hash: String, secs: Long = 10) {
        val now = System.currentTimeMillis()
        recent.entries.removeAll { it.value < now }
        recent[hash] = now + secs * 1000
    }

    @Synchronized
    private fun suppressed(hash: String): Boolean {
        val now = System.currentTimeMillis()
        recent.entries.removeAll { it.value < now }
        return recent.containsKey(hash)
    }

    private suspend fun read() {
        try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val item = clip.getItemAt(0)
            val t = item.text?.toString()
            if (!t.isNullOrEmpty() && t.length <= 256 * 1024) {
                emit(ClipContent(t, null, "", emptyList(), sha(t.toByteArray())))
                return
            }
            val uri = item.uri
            if (uri != null) {
                val info = resolve(uri) ?: return
                if (info.mime.startsWith("image/")) {
                    val bytes = readCapped(uri, 3 * 1024 * 1024) ?: return
                    emit(ClipContent(null, bytes, "image/jpeg", emptyList(), sha(bytes)))
                } else if (info.size in 1..200L * 1024 * 1024) {
                    emit(ClipContent(null, null, "", listOf(info), sha("${info.name}:${info.size}".toByteArray())))
                }
            }
        } catch (e: Exception) { }
    }

    private suspend fun emit(c: ClipContent) {
        if (c.hash == lastHash || suppressed(c.hash)) { lastHash = c.hash; return }
        lastHash = c.hash
        try { onChange?.invoke(c) } catch (e: Exception) { }
    }

    private fun resolve(uri: Uri): UriInfo? = try {
        var name = uri.lastPathSegment ?: "arquivo"
        var size = -1L
        appCtx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
            }
        }
        var mime = appCtx.contentResolver.getType(uri) ?: ""
        if (mime.isEmpty()) mime = when {
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            else -> "application/octet-stream"
        }
        UriInfo(name, mime, size, uri.toString())
    } catch (e: Exception) { null }

    fun readUri(uriStr: String): ByteArray? = try {
        appCtx.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
    } catch (e: Exception) { null }

    fun openStream(uriStr: String) = appCtx.contentResolver.openInputStream(Uri.parse(uriStr))

    private fun readCapped(uri: Uri, cap: Int): ByteArray? {
        val raw = try {
            appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null } ?: return null
        if (raw.size <= cap) {
            // normaliza p/ JPEG (compatível com o PC)
            return try {
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            } catch (e: Exception) { raw }
        }
        // downscale até caber
        var sample = 2
        while (sample <= 8) {
            try {
                val o = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, o) ?: return null
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                val b = out.toByteArray()
                if (b.size <= cap) return b
            } catch (e: Exception) { return null }
            sample *= 2
        }
        return null
    }

    fun setText(t: String) {
        try {
            cm.setPrimaryClip(ClipData.newPlainText("LumiLink", t))
            lastHash = sha(t.toByteArray())
        } catch (e: Exception) { }
    }

    // Copia imagem via MediaStore (Q+). Antigo: só notifica.
    fun copyImage(bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "lumilink_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LumiLink")
            }
            val uri = appCtx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            cm.setPrimaryClip(ClipData.newUri(appCtx.contentResolver, "image", uri))
            lastHash = sha(bytes)
            true
        } catch (e: Exception) { false }
    }

    companion object {
        fun sha(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

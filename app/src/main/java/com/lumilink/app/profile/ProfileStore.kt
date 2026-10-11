package com.lumilink.app.profile

import android.content.Context
import android.graphics.*
import android.provider.Settings
import androidx.datastore.preferences.core.*
import java.io.File
import java.security.MessageDigest

data class DeviceProfile(val deviceId: String, val name: String, val photoHash: String)

// Nome padrão: Settings.Global.device_name -> fallback Build.MODEL. Foto: avatar iniciais.
object ProfileDefaults {
    fun defaultName(ctx: Context): String = try {
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL ?: "Android"
    } catch (e: Exception) { android.os.Build.MODEL ?: "Android" }
}

class ProfileStore(private val ctx: Context, val deviceId: String) {
    private val nameKey = stringPreferencesKey("own_name")
    private val nameCustomKey = booleanPreferencesKey("own_name_custom")
    private val photoCustomKey = booleanPreferencesKey("own_photo_custom")

    fun ownPhotoFile(): File = File(ctx.filesDir, "profile.png")
    fun peerPhotoFile(peerId: String): File = File(ctx.filesDir, "peer_$peerId.png")

    suspend fun currentName(get: suspend () -> androidx.datastore.preferences.core.Preferences): String {
        // Se não customizado, relê padrão do sistema a cada abertura.
        return "todo"
    }

    fun sanitize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val t = name.trim().filter { !it.isISOControl() }
        return if (t.length > 32) t.substring(0, 32) else t
    }

    fun hashOf(f: File): String {
        if (!f.exists()) return "empty"
        val d = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        return d.joinToString("") { "%02x".format(it) }
    }

    // Recorte quadrado central + 256x256 + JPEG ~50KB.
    fun prepareAvatar(src: Bitmap, dst: File): String {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2; val y = (src.height - side) / 2
        val sq = Bitmap.createBitmap(src, x, y, side, side)
        val out = Bitmap.createScaledBitmap(sq, 256, 256, true)
        var q = 80; var bytes: ByteArray
        do {
            val bos = java.io.ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, q, bos)
            bytes = bos.toByteArray(); q -= 10
        } while (bytes.size > 50 * 1024 && q >= 30)
        dst.writeBytes(bytes)
        return hashOf(dst)
    }

    fun initialsAvatar(name: String, dst: File): String {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (name.hashCode() and 0x7fffffff) % 360
        c.drawColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.5f, 0.55f)))
        val paint = Paint().apply { color = Color.WHITE; textSize = 96f; textAlign = Paint.Align.CENTER }
        val initials = name.split(" ").filter { it.isNotEmpty() }.take(2).map { it[0].uppercase() }.joinToString("")
            .ifEmpty { "?" }
        c.drawText(initials, 128f, 150f, paint)
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        dst.writeBytes(bos.toByteArray())
        return hashOf(dst)
    }

    fun validateIncoming(bytes: ByteArray, expectedHash: String, maxBytes: Int = 100 * 1024): Boolean {
        if (bytes.size > maxBytes) return false
        val h = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!h.equals(expectedHash, ignoreCase = true)) return false
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            o.outWidth > 0 && o.outHeight > 0
        } catch (e: Exception) { false }
    }
}

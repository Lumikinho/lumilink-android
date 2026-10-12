package com.lumilink.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

data class TrackInfo(
    val title: String,
    val artist: String,
    val app: String,
    val playing: Boolean,
)

// Lê a mídia ativa via MediaSession (exige "Acesso a notificações" do usuário).
object MediaStatus {
    @Volatile private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null
    @Volatile private var lastQuery = 0L

    var onStatus: ((TrackInfo?) -> Unit)? = null

    fun refresh(ctx: Context, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastQuery < 1500) return // debounce: notificação não é termômetro
        lastQuery = now
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(ctx, MediaListener::class.java)
            val all = try { msm.getActiveSessions(comp) } catch (e: Exception) { emptyList() }
            val sessions = all.filter { it.packageName != ctx.packageName } // nunca a nossa (eco)
            val next = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
            val old = controller
            if (old !== next) {
                try { callback?.let { old?.unregisterCallback(it) } } catch (e: Exception) { }
                controller = next
                callback = next?.let {
                    object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) = push(ctx)
                        override fun onPlaybackStateChanged(state: PlaybackState?) = push(ctx)
                        override fun onSessionDestroyed() = push(ctx)
                    }.also { cb ->
                        try { next.registerCallback(cb) } catch (e: Exception) { }
                    }
                }
            }
            push(ctx)
        } catch (e: Exception) { }
    }

    fun clear() {
        try { callback?.let { controller?.unregisterCallback(it) } } catch (e: Exception) { }
        controller = null
        callback = null
        try { onStatus?.invoke(null) } catch (e: Exception) { }
    }

    private fun push(ctx: Context) {
        try {
            val c = controller
            if (c == null) {
                onStatus?.invoke(null)
                return
            }
            val md = try { c.metadata } catch (e: Exception) { null }
            val st = try { c.playbackState } catch (e: Exception) { null }
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: ""
            if (title == null) {
                onStatus?.invoke(null)
                return
            }
            val app = try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
            } catch (e: Exception) { c.packageName }
            onStatus?.invoke(TrackInfo(
                title = title,
                artist = artist,
                app = app,
                playing = st?.state == PlaybackState.STATE_PLAYING,
            ))
        } catch (e: Exception) { }
    }
}

class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        MediaStatus.refresh(this)
        sendBroadcast(Intent("LUMILINK_MEDIA_REQ"))
    }

    override fun onListenerDisconnected() {
        MediaStatus.clear()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn?.packageName == packageName) return // ignora as nossas (senão vira loop)
        MediaStatus.refresh(this)
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn?.packageName == packageName) return
        MediaStatus.refresh(this)
    }
}

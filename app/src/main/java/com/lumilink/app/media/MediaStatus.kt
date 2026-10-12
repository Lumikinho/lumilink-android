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
    val state: String, // playing | paused | stopped
)

// Lê a mídia ativa via MediaSession (exige "Acesso a notificações" do usuário).
object MediaStatus {
    @Volatile private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null
    @Volatile private var lastQuery = 0L

    var onStatus: ((TrackInfo?) -> Unit)? = null

    private var lastTitle = ""
    private var lastArtist = ""
    private var lastApp = ""

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
        lastTitle = ""; lastArtist = ""; lastApp = ""
        try { onStatus?.invoke(null) } catch (e: Exception) { }
    }

    private fun push(ctx: Context) {
        try {
            val c = controller
            if (c == null) {
                lastTitle = ""; lastArtist = ""; lastApp = ""
                onStatus?.invoke(null) // app fechado/sem sessão: aí sim remove
                return
            }
            val md = try { c.metadata } catch (e: Exception) { null }
            val st = try { c.playbackState } catch (e: Exception) { null }
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: ""
            var app = ""
            try {
                val pm = ctx.packageManager
                val info = try {
                    pm.getApplicationInfo(c.packageName, 0)
                } catch (e: Exception) { null }
                app = if (info != null) pm.getApplicationLabel(info).toString() else c.packageName
            } catch (e: Exception) { app = c.packageName }
            if (title != null) {
                lastTitle = title; lastArtist = artist; lastApp = app
            }
            if (lastTitle.isEmpty()) {
                onStatus?.invoke(null) // nunca houve mídia: nada a mostrar
                return
            }
            // Sessão viva mas parada/sem estado: mostra STOPPED com a última faixa.
            val state = when (st?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                else -> "stopped"
            }
            onStatus?.invoke(TrackInfo(
                title = lastTitle,
                artist = lastArtist,
                app = lastApp,
                state = state,
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

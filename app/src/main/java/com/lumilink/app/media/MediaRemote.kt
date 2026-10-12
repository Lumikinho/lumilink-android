package com.lumilink.app.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.lumilink.app.ConnectionService
import com.lumilink.app.notification.FocusIsland
import kotlinx.coroutines.*

// Sessão de mídia como CONTROLE REMOTO do PC: exibe o que toca lá e
// repassa play/pause/next/prev/seek para lá. Funciona em qualquer fabricante
// (MediaStyle); na HyperOS ganha os extras da ilha por cima.
class MediaRemote(
    private val ctx: Context,
    private val sendCmd: (String, Long?) -> Unit,
    private val log: (String) -> Unit,
) {
    private val session = MediaSessionCompat(ctx, "LumiLinkPC").apply {
        setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = sendCmd("play", null)
            override fun onPause() = sendCmd("pause", null)
            override fun onSkipToNext() = sendCmd("next", null)
            override fun onSkipToPrevious() = sendCmd("prev", null)
            override fun onSeekTo(pos: Long) = sendCmd("seek", pos)
            override fun onStop() = sendCmd("pause", null)
        })
        isActive = false
    }
    private var lastArt: android.graphics.Bitmap? = null

    fun update(title: String, artist: String, art: ByteArray?, dur: Long, pos: Long,
        playing: Boolean, stopped: Boolean = false) {
        try {
            if (art != null) {
                try { lastArt = BitmapFactory.decodeByteArray(art, 0, art.size) } catch (e: Exception) { }
            }
            val mark = when {
                stopped -> "⏹ "
                playing -> "▶ "
                else -> "⏸ "
            }
            session.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mark + title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, lastArt)
                .build())
            session.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(
                    if (stopped) PlaybackStateCompat.STATE_STOPPED
                    else if (playing) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    pos, if (playing) 1f else 0f)
                .build())
            session.isActive = true
            post(mark + title, artist, playing)
        } catch (e: Exception) { log("[media-pc] update falhou: ${e.message}") }
    }

    private fun post(title: String, artist: String, playing: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel("lumilink_media",
                "LumiLink mídia", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val n = NotificationCompat.Builder(ctx, "lumilink_media")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(lastArt)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Anterior",
                    intentFor("prev", 31))
                .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pausar" else "Tocar", intentFor("toggle", 32))
                .addAction(android.R.drawable.ic_media_next, "Próxima",
                    intentFor("next", 33))
                .setStyle(MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2))
                .build()
            nm.notify(2002, n)
        } catch (e: Exception) { log("[media-pc] notificação falhou: ${e.message}") }
    }

    private fun intentFor(cmd: String, req: Int): PendingIntent {
        val i = Intent(ctx, ConnectionService::class.java)
            .putExtra("action", "media_action").putExtra("cmd", cmd)
        return PendingIntent.getService(ctx, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun clear() {
        try { session.isActive = false } catch (e: Exception) { }
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(2002)
        } catch (e: Exception) { }
    }
}

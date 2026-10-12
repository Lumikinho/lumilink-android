package com.lumilink.app.screen

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.lumilink.app.messaging.Message
import com.lumilink.app.messaging.MessageRouter
import com.lumilink.app.peers.PeerManager
import com.lumilink.app.storage.PeerStore
import com.lumilink.app.ui.ScreenWatchActivity
import org.json.JSONObject

// Lado receptor: aceita, monta frames no ScreenHub e avisa p/ assistir.
class ScreenReceiver(
    private val appCtx: Context,
    private val router: MessageRouter,
    private val manager: PeerManager,
    private val peers: PeerStore,
    private val log: (String) -> Unit,
) {
    fun register() {
        router.register("screen_offer") { c, m ->
            val id = c.deviceId
            if (!c.authenticated || id == null) return@register Unit
            try {
                val w = m.payload.optInt("w", 0)
                val h = m.payload.optInt("h", 0)
                if (w <= 0 || h <= 0) return@register Unit
                val name = peers.get(id)?.name ?: id
                ScreenHub.reset(id, name, w, h)
                c.send(Message.create("screen_accept", JSONObject().put("forId", id)))
                notifyWatch(name)
                log("[tela] oferta aceita de $name (${w}x$h).")
            } catch (e: Exception) { log("[tela] oferta ignorada: ${e.message}") }
            Unit
        }
        router.register("screen_data") { c, m ->
            if (!c.authenticated) return@register Unit
            try {
                val seq = m.payload.optInt("seq", 0)
                val b64 = m.payload.optString("data", "")
                if (b64.isEmpty()) return@register Unit
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                if (seq < 0) ScreenHub.pushCsd(seq, bytes)
                else ScreenHub.pushPart(seq,
                    m.payload.optInt("part", 0), m.payload.optInt("parts", 1),
                    m.payload.optBoolean("key", false), bytes)
            } catch (e: Exception) { }
            Unit
        }
        router.register("screen_stop") { c, m ->
            ScreenHub.stop()
            log("[tela] transmissão encerrada pelo par.")
            Unit
        }
    }

    private fun notifyWatch(peerName: String) {
        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel("lumilink_ping",
                "LumiLink ping", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val tap = PendingIntent.getActivity(appCtx, 2003,
                Intent(appCtx, ScreenWatchActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(appCtx, "lumilink_ping")
                .setContentTitle("Tela de $peerName")
                .setContentText("Toque para assistir.")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(tap)
                .setAutoCancel(true).build()
            nm.notify(2003, n)
        } catch (e: Exception) { }
    }
}

package com.lumilink.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lumilink.app.messaging.MessageRouter
import com.lumilink.app.clipboard.ClipMonitor
import com.lumilink.app.clipboard.ClipSync
import com.lumilink.app.storage.ClipStore
import com.lumilink.app.pairing.Pairing
import com.lumilink.app.peers.PeerManager
import com.lumilink.app.profile.ProfileStore
import com.lumilink.app.security.CertManager
import com.lumilink.app.storage.AppSettings
import com.lumilink.app.storage.PeerStore
import kotlinx.coroutines.*

// Dono do grafo P2P: PeerManager (vários pares) + Foreground Service.
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var manager: PeerManager? = null
    private var fileTransfer: com.lumilink.app.transfer.FileTransfer? = null
    private var mediaRemote: com.lumilink.app.media.MediaRemote? = null
    private var lastPcPeer = ""

    companion object {
        const val CH = "lumilink"
        const val NOTIF = 11
    }

    override fun onCreate() {
        super.onCreate()
        startFg("Iniciando…")
        // MediaSession exige Looper: nascer na main thread (onCreate já é main).
        mediaRemote = com.lumilink.app.media.MediaRemote(
            applicationContext,
            sendCmd = { cmd, pos -> onMediaCmd(cmd, pos) },
            log = { log(it) },
        )
        scope.launch {
            try {
                val c = this@ConnectionService
                val logFn: (String) -> Unit = { log(it) }
                val settings = AppSettings(c)
                val certs = CertManager(c, logFn)
                val peers = PeerStore(c)
                val profiles = ProfileStore(c, settings.deviceId())
                val router = MessageRouter(logFn)
                val mgr = PeerManager(c.applicationContext, scope, settings, certs,
                    peers, profiles, Pairing(logFn), router, logFn)
                mgr.onChanged = {
                    updateNotif()
                    sendBroadcast(Intent("LUMILINK_CHANGED"))
                }
                manager = mgr
                router.register("media_pc") { rc, rm ->
                    val peerId = rc.deviceId
                    if (!rc.authenticated || peerId == null) return@register Unit
                    try {
                        lastPcPeer = peerId
                        val title = rm.payload.optString("title", "")
                        if (title.isEmpty()) {
                            mediaRemote?.clear()
                            return@register Unit
                        }
                        val artB64 = rm.payload.optString("art", "")
                        val art = if (artB64.isEmpty()) null else try {
                            android.util.Base64.decode(artB64, android.util.Base64.DEFAULT)
                        } catch (e: Exception) { null }
                        mediaRemote?.update(
                            title,
                            rm.payload.optString("artist", ""),
                            art,
                            rm.payload.optLong("dur", 0),
                            rm.payload.optLong("pos", 0),
                            rm.payload.optBoolean("playing", false),
                            rm.payload.optString("state", "") == "stopped",
                        )
                    } catch (e: Exception) { log("[media-pc] ignorado: ${e.message}") }
                    Unit
                }
                com.lumilink.app.media.MediaStatus.onStatus = { track -> onTrack(track) }
                val mediaReq = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        com.lumilink.app.media.MediaStatus.refresh(this@ConnectionService)
                    }
                }
                registerReceiver(mediaReq, IntentFilter("LUMILINK_MEDIA_REQ"), RECEIVER_NOT_EXPORTED)
                com.lumilink.app.media.MediaStatus.refresh(this@ConnectionService)
                val clipStore = ClipStore(c)
                val clipMonitor = ClipMonitor(c, scope)
                ClipSync(c.applicationContext, scope, router, mgr, clipStore, clipMonitor,
                    settings.deviceId(),
                    isEnabled = { settings.clipSyncBlocking() },
                    peerName = { id -> peers.get(id)?.name ?: id },
                    notify = { t, x -> mgr.notifyUser(t, x) },
                    log = logFn)
                com.lumilink.app.transfer.FileTransfer(c.applicationContext, router, mgr,
                    clipStore,
                    peerName = { id -> peers.get(id)?.name ?: id },
                    notify = { t, x -> mgr.notifyUser(t, x) },
                    log = logFn).also { fileTransfer = it }
                clipMonitor.start()
                mgr.start()
                log("[svc] nó ${settings.deviceId().take(8)} porta ${settings.port()}")
            } catch (e: Exception) {
                log("[svc] FATAL: ${e.message}")
            }
        }
    }

    private fun startFg(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "LumiLink", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CH)
            .setContentTitle("LumiLink").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
        com.lumilink.app.notification.FocusIsland.attach(this, n, "LumiLink", text)
        startForeground(NOTIF, n)
    }

    private fun updateNotif() {
        val peers = try { manager?.getPeers() ?: emptyList() } catch (e: Exception) { emptyList() }
        val up = peers.filter { it.state == "Conectado" }
        val text = when {
            up.isEmpty() -> "Desconectado"
            up.size == 1 -> "Conectado a ${up[0].name}"
            else -> "Conectado a ${up.size} dispositivos"
        }
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CH)
            .setContentTitle("LumiLink").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
        com.lumilink.app.notification.FocusIsland.attach(this, n, "LumiLink", text)
        nm.notify(NOTIF, n)
    }

    private fun onTrack(track: com.lumilink.app.media.TrackInfo?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (track == null) {
            try { nm.cancel(2001) } catch (e: Exception) { }
            sendBroadcast(Intent("LUMILINK_MEDIA"))
            scope.launch { broadcastMedia(null) }
            return
        }
        nm.createNotificationChannel(NotificationChannel("lumilink_media",
            "LumiLink mídia", NotificationManager.IMPORTANCE_DEFAULT))
        val title = track.title
        val text = listOf(track.artist, track.app).filter { it.isNotBlank() }.joinToString(" • ")
        val n = NotificationCompat.Builder(this, "lumilink_media")
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(track.state == "playing").build()
        nm.notify(2001, n)
        sendBroadcast(Intent("LUMILINK_MEDIA")
            .putExtra("title", track.title).putExtra("artist", track.artist)
            .putExtra("app", track.app).putExtra("state", track.state))
        scope.launch { broadcastMedia(track) }
    }

    private var lastMediaSent = ""
    private suspend fun broadcastMedia(track: com.lumilink.app.media.TrackInfo?) {
        try {
            val key = if (track == null) "" else
                "${track.title}|${track.artist}|${track.app}|${track.state}"
            if (key == lastMediaSent) return
            lastMediaSent = key
            val mgr = manager ?: return
            mgr.broadcast(org.json.JSONObject().let {
                com.lumilink.app.messaging.Message.create("media_status",
                    it.put("title", track?.title ?: "").put("artist", track?.artist ?: "")
                        .put("app", track?.app ?: "")
                        .put("playing", track?.state == "playing")
                        .put("state", track?.state ?: "")
                        .put("ts", System.currentTimeMillis()))
            })
        } catch (e: Exception) { }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.hasExtra("qr_ip") == true) {
            val ip = i.getStringExtra("qr_ip")!!
            val port = i.getIntExtra("qr_port", 0)
            val token = i.getStringExtra("qr_token")!!
            val fp = i.getStringExtra("qr_fp")!!
            val id = i.getStringExtra("qr_deviceId")!!
            val ips = i.getStringArrayListExtra("qr_ips") ?: emptyList<String>()
            scope.launch {
                try { manager?.pairTo(ip, port, token, fp, id, ips) }
                catch (e: Exception) { log("[pair] falhou: ${e.message}") }
            }
        }
        if (i?.hasExtra("m_ip") == true) {
            val ip = i.getStringExtra("m_ip")!!
            val port = i.getIntExtra("m_port", 0)
            val code = i.getStringExtra("m_code")!!
            scope.launch {
                try { manager?.pairTo(ip, port, code, null, null) }
                catch (e: Exception) { log("[pair] manual falhou: ${e.message}") }
            }
        }
        when (i?.getStringExtra("action")) {
            "new_pairing" -> scope.launch {
                try { manager?.createPairingDisplay() } catch (e: Exception) { log("[pair] ${e.message}") }
            }
            "profile_update" -> scope.launch { manager?.sendProfileUpdateIfConnected() }
            "ping_now" -> scope.launch { manager?.pingNow() }
            "ping_peer" -> scope.launch {
                val id = i.getStringExtra("peerId")
                if (id == null) log("[ping] sem par.")
                else try { manager?.pingPeer(id) }
                catch (e: Exception) { log("[ping] sem resposta (${e.message}).") }
            }
            "send_file" -> scope.launch {
                val id = i.getStringExtra("peerId")
                val uri = i.getStringExtra("uri")
                if (id == null || uri == null) {
                    log("[file] faltando par ou arquivo.")
                } else {
                    try {
                        fileTransfer?.sendFile(id, android.net.Uri.parse(uri))
                    } catch (e: Exception) { log("[file] envio falhou: ${e.message}") }
                }
            }
            "media_action" -> {
                // toggle/play/pause/next/prev: o PC resolve (toggle nativo).
                val cmd = i.getStringExtra("cmd") ?: return START_STICKY
                onMediaCmd(cmd, null)
            }
            "reconnect_now" -> scope.launch {
                i.getStringExtra("peerId")?.let { manager?.reconnectNow(it) }
            }
            "forget" -> scope.launch {
                i.getStringExtra("peerId")?.let { manager?.forget(it) }
            }
        }
        return START_STICKY
    }

    private fun onMediaCmd(cmd: String, pos: Long?) {
        scope.launch {
            try {
                val peer = lastPcPeer
                if (peer.isEmpty()) return@launch
                manager?.sendTo(peer, com.lumilink.app.messaging.Message.create("media_cmd",
                    org.json.JSONObject().put("cmd", cmd).put("pos", pos ?: -1)))
            } catch (e: Exception) { log("[media-pc] comando falhou: ${e.message}") }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        try { manager?.stop() } catch (e: Exception) { }
        scope.cancel()
        super.onDestroy()
    }

    private fun log(s: String) {
        Log.i("LumiLink", s)
        sendBroadcast(Intent("LUMILINK_LOG").putExtra("line", s))
    }
}

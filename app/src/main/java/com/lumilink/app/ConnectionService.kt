package com.lumilink.app

import android.app.*
import android.content.Context
import android.content.Intent
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

    companion object {
        const val CH = "lumilink"
        const val NOTIF = 11
    }

    override fun onCreate() {
        super.onCreate()
        startFg("Iniciando…")
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
                val clipStore = ClipStore(c)
                val clipMonitor = ClipMonitor(c, scope)
                ClipSync(c.applicationContext, scope, router, mgr, clipStore, clipMonitor,
                    settings.deviceId(),
                    isEnabled = { settings.clipSyncBlocking() },
                    peerName = { id -> peers.get(id)?.name ?: id },
                    notify = { t, x -> mgr.notifyUser(t, x) },
                    log = logFn)
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
            "reconnect_now" -> scope.launch {
                i.getStringExtra("peerId")?.let { manager?.reconnectNow(it) }
            }
            "forget" -> scope.launch {
                i.getStringExtra("peerId")?.let { manager?.forget(it) }
            }
        }
        return START_STICKY
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

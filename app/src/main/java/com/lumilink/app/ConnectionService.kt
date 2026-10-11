package com.lumilink.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lumilink.app.messaging.Message
import com.lumilink.app.messaging.MessageRouter
import com.lumilink.app.messaging.registerEchoExample
import com.lumilink.app.profile.ProfileDefaults
import com.lumilink.app.profile.ProfileStore
import com.lumilink.app.storage.PairStore
import com.lumilink.app.transport.PhoneWebSocketClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Foreground Service: mantém wss, reconecta com backoff 1s→30s, ping/pong 15s (3 faltas = caída).
// Descoberta via NsdManager (_lumilink._tcp); fallback = último IP.
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pairs: PairStore
    private lateinit var profiles: ProfileStore
    private lateinit var router: MessageRouter
    private var ctx: MessageRouter.Ctx? = null
    private var client = PhoneWebSocketClient { log(it) }
    private var nsd: NsdManager? = null
    private var deviceId: String = ""
    private var pendingSecret: String? = null
    private var missedPongs = 0
    private var pingJob: Job? = null
    private var connectJob: Job? = null
    private var state = "Desconectado"
    private var pcName = "PC"

    companion object {
        const val CH = "lumilink"
        const val NOTIF = 11
        var ACTION_CONNECT_QR = "CONNECT_QR"
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ConnectionService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        pairs = PairStore(this)
        deviceId = runBlocking {
            val sp = getSharedPreferences("dev", MODE_PRIVATE)
            sp.getString("deviceId", null) ?: ("android-" + UUID.randomUUID().toString().take(8)).also {
                sp.edit().putString("deviceId", it).apply()
            }
        }
        profiles = ProfileStore(this, deviceId)
        router = MessageRouter { log(it) }.also { registerHandlers(it); it.registerEchoExample { m -> log(m) } }
        startFg("Desconectado")
        scope.launch { reconnectLoop() }
        log("[svc] Foreground Service criado. deviceId=$deviceId")
    }

    private fun startFg(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "LumiLink", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CH)
            .setContentTitle("LumiLink").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
        startForeground(NOTIF, n)
    }

    private fun setState(s: String) {
        state = s
        // Persiste p/ a Activity ler ao abrir (broadcasts não são sticky).
        getSharedPreferences("svc", MODE_PRIVATE).edit()
            .putString("state", s).putString("pc", pcName).apply()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF, NotificationCompat.Builder(this, CH)
            .setContentTitle("LumiLink").setContentText(if (s == "Desconectado") s else "Conectado a $pcName")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build())
        sendBroadcast(Intent("LUMILINK_STATE").putExtra("state", s).putExtra("pc", pcName))
    }

    // Chamado pela ScannerActivity após ler o QR.
    fun pairWith(ip: String, port: Int, token: String, fp: String, pcId: String) {
        scope.launch { doConnect(ip, port, fp, token, pcId, isPairing = true) }
    }

    private suspend fun reconnectLoop() {
        var backoff = 1000L
        while (true) {
            val last = pairs.lastKnown()
            val pcId = pairs.pcId()
            if (last != null && pcId != null) {
                // Tenta mDNS primeiro; se falhar, usa último IP.
                val target = discoverPc() ?: last.first
                log("[discovery] alvo=$target (mdns ou último IP)")
                doConnect(target, last.second, last.third, null, pcId, isPairing = false)
                // doConnect só retorna ao cair; aplica backoff exponencial 1s→30s.
                log("[conn] reconectando em ${backoff}ms…")
                delay(backoff); backoff = minOf(backoff * 2, 30_000)
            } else {
                delay(3000) // sem par: espera QR
            }
        }
    }

    private suspend fun doConnect(ip: String, port: Int, fp: String, token: String?, pcId: String, isPairing: Boolean) =
        suspendCancellableCoroutine<Unit> { cont ->
            client.close()
            missedPongs = 0
            val c = MessageRouter.Ctx(send = { m -> client.send(m) })
            ctx = c
            client.connect(ip, port, fp,
                onOpen = {
                    scope.launch {
                        val name = ProfileDefaults.defaultName(this@ConnectionService)
                        val hash = profiles.hashOf(profiles.ownPhotoFile())
                        val hello = Message.create("hello", JSONObject()
                            .put("token", token) // null na reconexão (vira JSONObject.NULL? tratado abaixo)
                            .put("deviceId", deviceId)
                            .put("deviceName", name)
                            .put("profile", JSONObject().put("name", name).put("photoHash", hash)))
                        // org.json não aceita null: remove se reconexão.
                        if (token == null) hello.payload.remove("token")
                        client.send(hello)
                        log(if (isPairing) "[pair] hello com token enviado." else "[conn] hello de reconexão enviado.")
                    }
                },
                onMessage = { raw ->
                    scope.launch {
                        val msg = Message.fromJson(raw) ?: return@launch
                        if (msg.type == "pong") { missedPongs = 0; return@launch }
                        router.route(c, msg)
                    }
                },
                onFailure = { why ->
                    log("[conn] caiu: $why")
                    setState("Desconectado")
                    pingJob?.cancel()
                    if (cont.isActive) cont.resume(Unit) {}
                })
            // Callbacks de auth_ok encerram pareamento; loop de ping inicia lá.
            // Mantém referência p/ pareamento manual:
            pendingTarget = Triple(ip, port, fp)
        }

    private var pendingTarget: Triple<String, Int, String>? = null

    private fun registerHandlers(r: MessageRouter) {
        r.register("auth") { c, msg -> // desafio nonce do PC
            val nonce = msg.payload.optString("nonce")
            val pcId = pairs.pcId() ?: return@register
            val secret = pairs.getSecret(pcId) ?: return@register
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(Base64.decode(secret, Base64.DEFAULT), "HmacSHA256"))
            val hmac = mac.doFinal(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
            client.send(Message.create("auth", JSONObject().put("deviceId", deviceId).put("hmac", hmac)))
            log("[auth] desafio respondido (HMAC).")
        }
        r.register("auth_ok") { c, msg ->
            c.authenticated = true
            val secret = msg.payload.optString("secret", "")
            if (secret.isNotEmpty()) {
                // Pareamento: PC gerou segredo de 32 bytes.
                val t = pendingTarget
                val pcId = intentPcId ?: pairs.pcId() ?: "pc"
                pairs.savePair(pcId, pcName, intentFp ?: "", t?.first ?: "", t?.second ?: 52431, secret)
                log("[pair] PAREADO! segredo salvo (Keystore).")
            } else log("[auth] autenticado (HMAC ok).")
            setState("Conectado a $pcName")
            startPing()
            // Envia profile logo após auth_ok.
            sendOwnProfile()
        }
        r.register("ping") { _, msg ->
            client.send(Message.create("pong", JSONObject().put("echo", msg.id)))
        }
        r.register("profile") { _, msg ->
            val name = profiles.sanitize(msg.payload.optString("name"))
            val hash = msg.payload.optString("photoHash", "empty")
            if (name.isEmpty()) { log("[profile] nome inválido, ignorado."); return@register }
            pcName = name
            setState("Conectado a $pcName")
            log("[profile] PC é '$name'.")
            // Pede foto se hash difere do cache.
            val cached = profiles.peerPhotoFile(pairs.pcId() ?: "").let { if (it.exists()) profiles.hashOf(it) else "" }
            if (cached != hash && hash != "empty")
                client.send(Message.create("profile_photo_request"))
            else if (hash == "empty") log("[profile] PC sem foto (avatar local).")
        }
        r.register("profile_photo_request") { _, _ ->
            val f = profiles.ownPhotoFile()
            if (!f.exists()) return@register
            val b = f.readBytes()
            if (b.size > 100 * 1024) { log("[profile] foto >100KB, não enviada."); return@register }
            client.send(Message.create("profile_photo", JSONObject()
                .put("photoHash", profiles.hashOf(f)).put("mime", "image/jpeg")
                .put("dataBase64", Base64.encodeToString(b, Base64.NO_WRAP))))
        }
        r.register("profile_photo") { _, msg ->
            try {
                val hash = msg.payload.optString("photoHash")
                val bytes = Base64.decode(msg.payload.optString("dataBase64"), Base64.DEFAULT)
                if (!profiles.validateIncoming(bytes, hash)) { log("[profile] foto inválida, ignorada."); return@register }
                profiles.peerPhotoFile(pairs.pcId() ?: "pc").writeBytes(bytes)
                log("[profile] foto do PC salva.")
            } catch (e: Exception) { log("[profile] foto ignorada: ${e.message}") }
        }
        r.register("bye") { _, _ -> log("[conn] bye do PC."); setState("Desconectado") }
    }

    private var intentPcId: String? = null
    private var intentFp: String? = null

    private fun sendOwnProfile() {
        val name = ProfileDefaults.defaultName(this)
        client.send(Message.create("profile", JSONObject()
            .put("name", profiles.sanitize(name).ifEmpty { name })
            .put("photoHash", profiles.hashOf(profiles.ownPhotoFile()))))
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(15_000)
                client.send(Message.create("ping"))
                if (++missedPongs >= 3) {
                    log("[conn] 3 pongs perdidos — caída.")
                    setState("Desconectado"); break
                }
            }
        }
    }

    private suspend fun discoverPc(): String? = suspendCancellableCoroutine { cont ->
        try {
            nsd = getSystemService(NSD_SERVICE) as NsdManager
            var listenerRef: NsdManager.DiscoveryListener? = null
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(s: NsdServiceInfo) {
                    if (s.serviceName.contains("lumilink", true))
                        nsd?.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(r: NsdServiceInfo) {
                                cont.resume(r.host.hostAddress ?: "") {}
                                try { listenerRef?.let { nsd?.stopServiceDiscovery(it) } } catch (e: Exception) {}
                            }
                            override fun onResolveFailed(s: NsdServiceInfo, c: Int) { cont.resume(null) {} }
                        })
                }
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, c: Int) { cont.resume(null) {} }
                override fun onStopDiscoveryFailed(t: String, c: Int) {}
                override fun onServiceLost(s: NsdServiceInfo) {}
            }
            listenerRef = listener
            nsd?.discoverServices("_lumilink._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            // Timeout 3s -> fallback último IP.
            scope.launch { delay(3000); if (cont.isActive) { cont.resume(null) {}; try { nsd?.stopServiceDiscovery(listener) } catch (e: Exception) {} } }
        } catch (e: Exception) { if (cont.isActive) cont.resume(null) {} }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.hasExtra("qr_ip") == true) {
            intentPcId = i.getStringExtra("qr_deviceId"); intentFp = i.getStringExtra("qr_fp")
            pairWith(i.getStringExtra("qr_ip")!!, i.getIntExtra("qr_port", 52431),
                i.getStringExtra("qr_token")!!, intentFp!!, intentPcId!!)
        }
        // Pedir isenção de bateria (uma vez): o MainActivity chama; aqui só log.
        return START_STICKY // sobrevive; BOOT_COMPLETED reinicia se toggle ativo.
    }

    override fun onBind(i: Intent?) = null
    private fun log(s: String) { Log.i("LumiLink", s); sendBroadcast(Intent("LUMILINK_LOG").putExtra("line", s)) }
}

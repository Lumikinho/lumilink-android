package com.lumilink.app.peers

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.lumilink.app.discovery.NsdHelper
import com.lumilink.app.messaging.Message
import com.lumilink.app.messaging.MessageRouter
import com.lumilink.app.pairing.Pairing
import com.lumilink.app.pairing.QrPayload
import com.lumilink.app.profile.ProfileDefaults
import com.lumilink.app.profile.ProfileStore
import com.lumilink.app.security.CertManager
import com.lumilink.app.storage.AppSettings
import com.lumilink.app.storage.PeerRec
import com.lumilink.app.storage.PeerStore
import com.lumilink.app.transport.PhoneWebSocketClient
import com.lumilink.app.transport.RateLimiter
import com.lumilink.app.transport.WsConn
import com.lumilink.app.transport.WsServer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// Gerente P2P multi-par: espelha o PeerManager do PC (PROTOCOL.md).
// Pump de leitura NUNCA bloqueia no handshake: hello é tratado em background.
class PeerManager(
    private val appCtx: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val certs: CertManager,
    private val peers: PeerStore,
    private val profiles: ProfileStore,
    private val pairing: Pairing,
    private val router: MessageRouter,
    private val log: (String) -> Unit,
) {
    data class PeerView(
        val deviceId: String, val name: String, val state: String,
        val lastSeen: Long, val lastIp: String, val photoHash: String,
        val media: String = "",
    )

    private val mediaOf = ConcurrentHashMap<String, String>()

    private inner class Inbox {
        private val waiters = ConcurrentHashMap<String, CompletableDeferred<Message>>()
        suspend fun wait(type: String, timeoutMs: Long): Message {
            val d = CompletableDeferred<Message>()
            waiters[type] = d
            try {
                return withTimeout(timeoutMs) { d.await() }
            } finally {
                waiters.remove(type, d)
            }
        }
        fun deliver(m: Message) { waiters[m.type]?.complete(m) }
        fun failAll() { for ((_, d) in waiters) d.completeExceptionally(IOException("fechado")); waiters.clear() }
    }

    private inner class Link(
        val outgoing: Boolean,
        val send: suspend (Message) -> Unit,
        val close: suspend () -> Unit,
        val isOpen: () -> Boolean,
        val ctx: MessageRouter.Ctx,
        val inbox: Inbox,
        var pingJob: Job? = null,
        var tag: Any? = null,
    )

    private inner class Session(val id: String) {
        @Volatile var state = "Desconectado"
        @Volatile var link: Link? = null
        @Volatile var busy = false
        @Volatile var dialJob: Job? = null
        @Volatile var backoff = 1000L
        @Volatile var linkLostAt = 0L
        @Volatile var lastSeen = 0L
        @Volatile var discIp = ""
        @Volatile var discPort = 0
        @Volatile var discAt = 0L
        @Volatile var missed = 0
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val inboxes = ConcurrentHashMap<MessageRouter.Ctx, Inbox>()
    private val connCtx = ConcurrentHashMap<WsConn, MessageRouter.Ctx>()
    private val limiter = RateLimiter()
    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Long>>()
    private var server: WsServer? = null
    private var nsd: NsdHelper? = null
    private lateinit var myId: String
    private var myPort: Int = 0

    var onChanged: (() -> Unit)? = null

    init {
        router.register("hello") { c, m ->
            scope.launch { handleHello(c, m) } // fora do pump!
            Unit
        }
        router.register("auth") { c, m ->
            inboxes[c]?.deliver(m); Unit
        }
        router.register("auth_ok") { c, m ->
            inboxes[c]?.deliver(m); Unit
        }
        router.register("ping") { c, m ->
            c.send(Message.create("pong", JSONObject().put("echo", m.id)))
            if (m.payload.optBoolean("manual", false)) {
                val id = c.deviceId
                val name = if (id != null) peers.get(id)?.name ?: id else "?"
                notifyPing("Ping recebido", "De $name.")
            }
            Unit
        }
        router.register("pong") { c, m ->
            sessions[c.deviceId]?.missed = 0
            val echo = m.payload.optString("echo", "")
            if (echo.isNotEmpty()) pendingPings.remove(echo)?.complete(SystemClock.elapsedRealtime())
            Unit
        }
        router.register("profile") { c, m -> onProfile(c, m, false) }
        router.register("profile_update") { c, m -> onProfile(c, m, true) }
        router.register("profile_photo") { c, m -> onProfilePhoto(c, m) }
        router.register("profile_photo_request") { c, _ ->
            val f = profiles.ownPhotoFile()
            if (c.authenticated && f.exists()) {
                val b = f.readBytes()
                if (b.size <= 100 * 1024)
                    c.send(Message.create("profile_photo", JSONObject()
                        .put("photoHash", profiles.hashOf(f)).put("mime", "image/jpeg")
                        .put("dataBase64", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))))
                else log("[profile] foto >100KB, não enviada.")
            }
            Unit
        }
        router.register("bye") { c, m ->
            log("[conn] bye de ${c.deviceId ?: "?"} (${m.payload.optString("reason")}).")
            sessions.values.firstOrNull { it.link?.ctx === c }?.link?.let { detach(it) }
            Unit
        }
        router.register("echo_response") { _, m -> log("[echo] eco: ${m.payload.optString("text")}"); Unit }
        router.register("media_status") { c, m ->
            val id = c.deviceId
            if (!c.authenticated || id == null) return@register Unit
            val t = m.payload.optString("title", "").take(64)
            val a = m.payload.optString("artist", "").take(64)
            val mark = when (m.payload.optString("state", "")) {
                "playing" -> "▶ "
                "paused" -> "⏸ "
                "stopped" -> "⏹ "
                else -> if (m.payload.optBoolean("playing", false)) "▶ " else "⏸ "
            }
            mediaOf[id] = if (t.isEmpty()) "" else mark + t +
                (if (a.isNotEmpty()) " — $a" else "")
            changed()
            Unit
        }
    }

    suspend fun start() {
        myId = settings.deviceId()
        myPort = settings.port()
        server = WsServer(certs.serverSslContext(), myPort, log).also { srv ->
            srv.onText = { conn, raw ->
                val m = Message.fromJson(raw)
                if (m != null) {
                    val c = connCtx.getOrPut(conn) {
                        MessageRouter.Ctx(send = { msg -> conn.send(msg.toJson()) })
                    }
                    router.route(c, m)
                }
            }
            srv.onClosed = { conn ->
                val c = connCtx.remove(conn)
                if (c?.deviceId != null) detachIfServerDead(c)
                // garante firewall: sem par = sem log
            }
            srv.start(scope)
        }
        val helper = NsdHelper(appCtx, log).also { nsd = it }
        helper.onPeerSeen = { id, ip, port -> onPeerSeen(id, ip, port) }
        helper.startAnnounce(myId, myPort)
        helper.startDiscovery()
        for (p in peers.all()) ensureSession(p.deviceId)
        changed()
    }

    fun stop() {
        try { nsd?.stop() } catch (e: Exception) { }
        try { server?.stop() } catch (e: Exception) { }
    }

    // ---- API ----
    fun getPeers(): List<PeerView> = peers.all().map { r ->
        val s = sessions[r.deviceId]
        PeerView(r.deviceId, r.name, s?.state ?: "Desconectado",
            r.lastSeen, r.lastIp.ifEmpty { s?.discIp ?: "" }, r.photoHash,
            mediaOf[r.deviceId] ?: "")
    }

    suspend fun createPairingDisplay(): JSONObject {
        val (token, code) = pairing.newPairing()
        val ip = Pairing.localIp()
        val qr = QrPayload.toJson(ip, myPort, token, certs.fingerprint, myId,
            Pairing.orderedIps().ifEmpty { listOf(ip) })
        val o = JSONObject().put("ip", ip).put("port", myPort).put("code", code)
            .put("fp", certs.fingerprint).put("id", myId).put("qr", qr)
        appCtx.getSharedPreferences("pairing_ui", Context.MODE_PRIVATE).edit()
            .putString("json", o.toString()).apply()
        return o
    }

    suspend fun pairTo(ip: String, port: Int, credential: String, expectedFp: String?,
        knownPeerId: String?, knownIps: List<String> = emptyList()) {
        val targets = mutableListOf(ip to port)
        for (k in knownIps.map { it.trim() }.filter { it.isNotEmpty() }.take(6))
            if (targets.none { it.first == k }) targets += k to port
        var lastErr: String? = null
        for ((tip, tport) in targets) {
            try {
                log("[pair] tentando $tip:$tport …")
                if (attemptPair(tip, tport, credential, expectedFp, knownPeerId, knownIps)) return
            } catch (e: Exception) {
                lastErr = e.message
                log("[pair] $tip:$tport falhou: ${e.message}")
            }
        }
        throw IOException(lastErr ?: "pareamento falhou.")
    }

    private suspend fun attemptPair(tip: String, tport: Int, credential: String,
        expectedFp: String?, knownPeerId: String?, knownIps: List<String>): Boolean {
        val client = PhoneWebSocketClient(log)
        val ctx = MessageRouter.Ctx(send = { m -> client.send(m) })
        val inbox = Inbox()
        inboxes[ctx] = inbox
        try {
            withTimeout(30_000) {
                val done = CompletableDeferred<Unit>()
                var failMsg: String? = null
                client.connect(tip, tport, expectedFp ?: "",
                    onMessage = { raw ->
                        scope.launch {
                            val m = Message.fromJson(raw)
                            if (m != null) router.route(ctx, m)
                        }
                        Unit
                    },
                    onFailure = { why ->
                        inbox.failAll()
                        if (failMsg == null) failMsg = why
                        done.complete(Unit)
                    },
                    onOpen = {
                        scope.launch {
                            try {
                                val me = profiles.getOwnName()
                                client.send(Message.create("hello", JSONObject()
                                    .put("token", credential).put("code", credential)
                                    .put("deviceId", myId).put("deviceName", me)
                                    .put("profile", JSONObject().put("name", me)
                                        .put("photoHash", profiles.hashOf(profiles.ownPhotoFile())))
                                    .put("fingerprint", certs.fingerprint).put("port", myPort)
                                    .put("ips", JSONArray(Pairing.orderedIps()))))
                                val ok = inbox.wait("auth_ok", 20_000)
                                val secret = ok.payload.optString("secret", "")
                                if (secret.isEmpty()) throw IOException("pareamento recusado.")
                                val peerId = ok.payload.optString("deviceId", "")
                                if (peerId.isEmpty()) throw IOException("resposta sem deviceId.")
                                if (knownPeerId != null && peerId != knownPeerId)
                                    throw IOException("ID divergente ($peerId).")
                                val prof = ok.payload.optJSONObject("profile")
                                val pname = profiles.sanitize(prof?.optString("name")).ifEmpty { peerId }
                                val fp = expectedFp?.ifBlank { client.capturedFp ?: "" }
                                    ?: (client.capturedFp ?: "")
                                peers.savePeer(PeerRec(peerId, pname, fp,
                                    tip, tport, prof?.optString("photoHash", "empty") ?: "empty",
                                    0, knownIps.joinToString(",")), secret)
                                val s = ensureSessionBusy(peerId)
                                try {
                                    s.link?.let { detach(it) }
                                    attachOutgoing(s, ctx, inbox, client, tip, tport)
                                } finally {
                                    s.busy = false
                                }
                                log("[pair] PAREADO com $peerId via $tip:$tport.")
                                changed()
                            } catch (e: CancellationException) {
                                failMsg = e.message ?: "cancelado"
                                try { client.close() } catch (e2: Exception) { }
                            } catch (e: Exception) {
                                failMsg = e.message ?: "falha"
                                log("[pair] falhou: ${e.message}")
                                try { client.close() } catch (e2: Exception) { }
                            } finally {
                                done.complete(Unit)
                            }
                        }
                        Unit
                    })
                done.await()
                val err = failMsg
                if (err != null) throw IOException(err)
            }
            return true
        } finally {
            inboxes.remove(ctx)
        }
    }

    suspend fun reconnectNow(id: String) {
        val s = sessions[id] ?: return
        s.backoff = 200
        s.link?.let {
            try { it.send(Message.create("bye", JSONObject().put("reason", "going-away"))) } catch (e: Exception) { }
            try { it.close() } catch (e: Exception) { }
        }
        log("[conn] reconexão forçada de $id.")
    }

    suspend fun forget(id: String) {
        val s = sessions.remove(id)
        mediaOf.remove(id)
        s?.link?.let {
            try { it.send(Message.create("bye", JSONObject().put("reason", "going-away"))) } catch (e: Exception) { }
            try { it.close() } catch (e: Exception) { }
        }
        peers.forget(id)
        log("[pair] esquecido $id.")
        changed()
    }

    suspend fun sendProfileUpdateIfConnected() {
        var sent = false
        for ((_, s) in sessions) {
            val l = s.link
            if (l != null && l.isOpen()) {
                sendOwnProfile(l.ctx); sent = true
            }
        }
        log(if (sent) "[profile] profile_update enviado." else "[profile] sem conexão — update irá ao (re)conectar.")
    }

    // Ping manual com medida: notifica o RTT aqui e avisa o outro lado.
    suspend fun pingPeer(id: String) {
        val s = sessions[id] ?: throw IOException("par desconhecido.")
        val l = s.link ?: throw IOException("sem conexão ativa.")
        if (!l.isOpen()) throw IOException("sem conexão ativa.")
        val ping = Message.create("ping", JSONObject().put("manual", true))
        val d = CompletableDeferred<Long>()
        pendingPings[ping.id] = d
        val t0 = SystemClock.elapsedRealtime()
        try {
            l.send(ping)
            withTimeout(10_000) { d.await() }
            val name = peers.get(id)?.name ?: id
            val ms = SystemClock.elapsedRealtime() - t0
            log("[ping] $name: pong em ${ms}ms.")
            notifyPing("Pong recebido", "$name em ${ms}ms.")
        } finally {
            pendingPings.remove(ping.id)
        }
    }

    suspend fun pingNow() {
        var sent = false
        for ((_, s) in sessions) {
            val l = s.link
            if (l != null && l.isOpen()) {
                try { l.send(Message.create("ping")); sent = true } catch (e: Exception) { }
            }
        }
        log(if (sent) "[conn] ping manual enviado." else "[conn] sem conexão ativa.")
    }

    suspend fun broadcast(msg: Message) {
        for ((_, s) in sessions) {
            val l = s.link
            if (l != null) try { if (l.isOpen()) l.send(msg) } catch (e: Exception) { }
        }
    }

    suspend fun sendTo(id: String, msg: Message): Boolean {
        val l = sessions[id]?.link ?: return false
        return try {
            if (!l.isOpen()) false else { l.send(msg); true }
        } catch (e: Exception) { false }
    }

    fun notifyUser(title: String, text: String) = notifyPing(title, text)

    // ---- descoberta ----
    private fun onPeerSeen(id: String, ip: String, port: Int) {
        if (id == myId) {
            // Próprio anúncio (loopback mDNS) — ignora; colisão real seria
            // meu id vindo de outro endpoint.
            if (port == myPort && Pairing.localIps().contains(ip)) return
            log("[mdns] AVISO: outro nó anuncia MEU deviceId — colisão de UUID!")
            return
        }
        if (!peers.isPaired(id)) return
        val s = ensureSession(id)
        s.discIp = ip; s.discPort = port; s.discAt = System.currentTimeMillis()
        changed()
    }

    private fun ensureSession(id: String): Session =
        sessions.getOrPut(id) { Session(id).also { s -> scope.launch { sessionLoop(s) } } }

    private fun ensureSessionBusy(id: String): Session {
        val s = ensureSession(id)
        s.busy = true
        return s
    }

    // ---- loop por par ----
    private suspend fun sessionLoop(s: Session) {
        while (scope.isActive) {
            if (!sessions.containsKey(s.id)) return
            if (peers.get(s.id) == null) { delay(5000); continue }
            if (s.link != null) { delay(2000); continue }
            if (s.busy) { delay(2000); continue }
            s.state = "Conectando"; changed()
            val rec = peers.get(s.id)
            val freshDisc = s.discIp.isNotEmpty() && System.currentTimeMillis() - s.discAt < 60_000
            val lastIp = rec?.lastIp ?: ""
            val lastPort = rec?.lastPort ?: 0
            val targets = mutableListOf<Pair<String, Int>>()
            if (freshDisc) targets += s.discIp to s.discPort
            if (lastIp.isNotEmpty() && lastPort != 0 &&
                targets.none { it.first == lastIp }) targets += lastIp to lastPort
            if (lastPort != 0)
                for (k in (rec?.knownIps ?: "").split(',').map { it.trim() }
                    .filter { it.isNotEmpty() }.take(6))
                    if (targets.none { it.first == k }) targets += k to lastPort
            if (myId > s.id) {
                val quiet = System.currentTimeMillis() - maxOf(s.linkLostAt, s.discAt)
                if (quiet < 10_000) { delay(1000); continue }
            }
            if (targets.isEmpty()) { delay(5000); continue }
            var ok = false
            for ((ip, port) in targets) {
                log("[loop] ${s.id} discando $ip:$port (smaller=${myId < s.id})…")
                val job = scope.launch { dialAndHandshake(s, ip, port) }
                s.dialJob?.cancel(CancellationException("substituída"))
                s.dialJob = job
                try {
                    withTimeout(35_000) { job.join() }
                    s.backoff = 1000
                    ok = true
                    break
                } catch (e: CancellationException) {
                    if (job.isCancelled && e.message == "yield") {
                        log("[conn] ${s.id}: cedendo vez à entrada (sou maior).")
                        ok = true // cedeu: não é falha, não dobra backoff
                        break
                    } else {
                        log("[conn] ${s.id} falhou em $ip:$port: ${e.message}")
                    }
                } catch (e: Exception) {
                    log("[conn] ${s.id} falhou em $ip:$port: ${e.message}")
                } finally {
                    if (s.dialJob === job) s.dialJob = null
                }
            }
            if (!ok) { s.state = "Desconectado"; changed() }
            delay(s.backoff)
            s.backoff = minOf(s.backoff * 2, 30_000)
        }
    }

    private suspend fun dialAndHandshake(s: Session, ip: String, port: Int) {
        val rec = peers.get(s.id) ?: throw IOException("par desconhecido")
        val secret = peers.getSecret(s.id) ?: throw IOException("sem segredo")
        val client = PhoneWebSocketClient(log)
        val ctx = MessageRouter.Ctx(send = { m -> client.send(m) })
        val inbox = Inbox()
        inboxes[ctx] = inbox
        try {
            val opened = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<String>()
            client.connect(ip, port, rec.certFp,
                onMessage = { raw ->
                    scope.launch {
                        val m = Message.fromJson(raw)
                        if (m != null) router.route(ctx, m)
                    }
                    Unit
                },
                onFailure = { why ->
                    inbox.failAll()
                    detachIfTag(s, client)
                    if (!opened.isCompleted) failed.complete(why)
                },
                onOpen = { opened.complete(Unit) })
            withTimeout(15_000) { opened.await() }
            if (failed.isCompleted) throw IOException(failed.getCompleted())
            val me = profiles.getOwnName()
            client.send(Message.create("hello", JSONObject()
                .put("deviceId", myId).put("deviceName", me)
                .put("profile", JSONObject().put("name", me)
                    .put("photoHash", profiles.hashOf(profiles.ownPhotoFile())))
                .put("fingerprint", certs.fingerprint).put("port", myPort)
                .put("ips", JSONArray(Pairing.orderedIps()))))
            val ch = inbox.wait("auth", 20_000)
            val nonce = ch.payload.optString("nonce", "")
            if (nonce.isEmpty()) throw IOException("desafio inválido")
            val nd = Pairing.newNonce()
            client.send(Message.create("auth", JSONObject()
                .put("deviceId", myId).put("hmac", Pairing.hmac(secret, nonce)).put("nonce", nd)))
            val ok = inbox.wait("auth_ok", 20_000)
            val back = ok.payload.optString("hmac", "")
            if (!back.equals(Pairing.hmac(secret, nd), ignoreCase = true))
                throw IOException("HMAC do par inválido (mútuo).")
            attachOutgoing(s, ctx, inbox, client, ip, port)
            log("[auth] ${s.id} autenticado (mútuo ok).")
        } finally {
            inboxes.remove(ctx)
        }
    }

    private suspend fun attachOutgoing(s: Session, ctx: MessageRouter.Ctx, inbox: Inbox, client: PhoneWebSocketClient, ip: String, port: Int) {
        if (s.link != null) throw IllegalStateException("já conectado")
        val link = Link(true,
            send = { m -> client.send(m) },
            close = { try { client.close() } catch (e: Exception) { } },
            isOpen = { true },
            ctx = ctx, inbox = inbox, tag = client)
        s.link = link; s.state = "Conectado"
        s.lastSeen = System.currentTimeMillis(); s.missed = 0
        ctx.deviceId = s.id; ctx.authenticated = true
        peers.touch(s.id, ip, port)
        log("[conn] ${s.id} via $ip:$port.")
        sendOwnProfile(ctx)
        startPing(s, link)
        changed()
    }

    private suspend fun attachIncoming(s: Session, ctx: MessageRouter.Ctx, inbox: Inbox, conn: WsConn) {
        s.link?.let { detach(it) }
        val link = Link(false,
            send = { m -> conn.send(m.toJson()) },
            close = { conn.close() },
            isOpen = { conn.open },
            ctx = ctx, inbox = inbox, tag = conn)
        s.link = link; s.state = "Conectado"
        s.lastSeen = System.currentTimeMillis(); s.missed = 0
        ctx.deviceId = s.id; ctx.authenticated = true
        sendOwnProfile(ctx)
        startPing(s, link)
    }

    // ---- ouvinte (hello em background!) ----
    private suspend fun handleHello(ctx: MessageRouter.Ctx, msg: Message) {
        val inbox = Inbox()
        inboxes[ctx] = inbox
        try {
            val p = msg.payload
            val id = p.optString("deviceId", "")
            if (id.isEmpty()) {
                ctx.send(Message.create("bye", JSONObject().put("reason", "bad-hello")))
                return
            }
            val conn = connOf(ctx) ?: return
            val ip = conn.remoteIp
            if (limiter.isBlocked(ip)) {
                ctx.send(Message.create("bye", JSONObject().put("reason", "rate-limited")))
                conn.close()
                return
            }
            val cred = p.optString("token", "").ifEmpty { p.optString("code", "") }
            if (cred.isNotEmpty() && pairing.tryConsume(cred)) {
                val secret = Pairing.newSecret()
                val prof = p.optJSONObject("profile")
                var name = profiles.sanitize(prof?.optString("name")).ifEmpty {
                    profiles.sanitize(p.optString("deviceName")).ifEmpty { id } }
                val photoHash = prof?.optString("photoHash", "empty") ?: "empty"
                peers.savePeer(PeerRec(id, name, p.optString("fingerprint", ""),
                    ip, p.optInt("port", 0), photoHash, 0, helloIps(p)), secret)
                val s = ensureSessionBusy(id)
                try {
                    s.link?.let { detach(it) }
                    val me = profiles.getOwnName()
                    ctx.send(Message.create("auth_ok", JSONObject()
                        .put("secret", secret).put("deviceId", myId)
                        .put("profile", JSONObject().put("name", me)
                            .put("photoHash", profiles.hashOf(profiles.ownPhotoFile())))))
                    limiter.success(ip)
                    attachIncoming(s, ctx, inbox, conn)
                    log("[pair] pareado com $id.")
                    changed()
                } finally {
                    s.busy = false
                }
                return
            }
            val rec = peers.get(id)
            if (rec == null) {
                limiter.fail(ip)
                ctx.send(Message.create("bye", JSONObject().put("reason", "not-paired")))
                conn.close()
                return
            }
            log("[auth] $id: par conhecido, conferindo fingerprint…")
            val fpClaim = p.optString("fingerprint", "")
            if (rec.certFp.isNotEmpty() && fpClaim.isNotEmpty() &&
                !rec.certFp.equals(fpClaim, ignoreCase = true)) {
                limiter.fail(ip)
                log("[auth] fingerprint de $id não confere.")
                ctx.send(Message.create("bye", JSONObject().put("reason", "bad-hmac")))
                conn.close()
                return
            }
            if (rec.certFp.isEmpty() && fpClaim.isNotEmpty()) {
                peers.getSecret(id)?.let { sec ->
                    peers.savePeer(rec.copy(certFp = fpClaim), sec)
                    log("[auth] fingerprint de $id aprendido (upgrade de par antigo).")
                }
            }
            val s = ensureSession(id)
            if (myId > id) {
                s.dialJob?.cancel(CancellationException("yield"))
                log("[conn] $id: abortei minha discagem (entrada do menor vence).")
            }
            s.link?.let { cur ->
                if (myId < id) {
                    ctx.send(Message.create("bye", JSONObject().put("reason", "duplicate")))
                    conn.close()
                    log("[conn] duplicada de $id recusada (eu disco).")
                    return
                } else {
                    detach(cur)
                    log("[conn] $id rediscou certo; assumindo entrada.")
                }
            }
            val secret2 = peers.getSecret(id)
            if (secret2 == null) { conn.close(); return }
            val nonce = Pairing.newNonce()
            log("[auth] $id: enviando desafio…")
            ctx.send(Message.create("auth", JSONObject().put("nonce", nonce)))
            log("[auth] $id: desafio enviado, aguardando resposta…")
            val resp = try {
                inbox.wait("auth", 30_000)
            } catch (e: Exception) {
                conn.close(); return
            }
            val hmac = resp.payload.optString("hmac", "")
            val nd = resp.payload.optString("nonce", "")
            if (hmac.isEmpty() || nd.isEmpty() || !hmac.equals(Pairing.hmac(secret2, nonce), ignoreCase = true)) {
                limiter.fail(ip)
                ctx.send(Message.create("bye", JSONObject().put("reason", "bad-hmac")))
                conn.close()
                return
            }
            ctx.send(Message.create("auth_ok", JSONObject().put("hmac", Pairing.hmac(secret2, nd))))
            limiter.success(ip)
            attachIncoming(s, ctx, inbox, conn)
            peers.touch(id, ip, p.optInt("port", 0), helloIps(p))
            log("[auth] $id autenticado (mútuo ok).")
            changed()
        } finally {
            inboxes.remove(ctx)
        }
    }

    private fun connOf(ctx: MessageRouter.Ctx): WsConn? =
        connCtx.entries.firstOrNull { it.value === ctx }?.key

    private fun helloIps(p: JSONObject): String {
        val out = mutableListOf<String>()
        try {
            val arr = p.optJSONArray("ips") ?: return ""
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty() && s.length < 64) out += s
            }
        } catch (e: Exception) { }
        return out.take(8).joinToString(",")
    }

    // ---- perfil ----
    private suspend fun sendOwnProfile(ctx: MessageRouter.Ctx) {
        val name = profiles.getOwnName()
        ctx.send(Message.create("profile", JSONObject()
            .put("name", name).put("photoHash", profiles.hashOf(profiles.ownPhotoFile()))))
    }

    private suspend fun onProfile(ctx: MessageRouter.Ctx, msg: Message, update: Boolean) {
        val id = ctx.deviceId
        if (!ctx.authenticated || id == null) return
        val name = profiles.sanitize(msg.payload.optString("name"))
        if (name.isEmpty()) { log("[profile] nome inválido, ignorado."); return }
        val hash = msg.payload.optString("photoHash", "empty")
        val cached = peers.get(id)
        peers.updateProfile(id, name, hash)
        sessions[id]?.lastSeen = System.currentTimeMillis()
        log("[profile] ${if (update) "atualizado" else "recebido"}: $name.")
        changed()
        if (cached?.photoHash != hash && hash != "empty")
            ctx.send(Message.create("profile_photo_request"))
    }

    private suspend fun onProfilePhoto(ctx: MessageRouter.Ctx, msg: Message) {
        val id = ctx.deviceId
        if (!ctx.authenticated || id == null) return
        try {
            val hash = msg.payload.optString("photoHash", "")
            val b64 = msg.payload.optString("dataBase64", "")
            if (b64.length > 140_000) { log("[profile] foto grande, ignorada."); return }
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            if (!profiles.validateIncoming(bytes, hash)) { log("[profile] foto inválida, ignorada."); return }
            profiles.peerPhotoFile(id).writeBytes(bytes)
            log("[profile] foto do par salva.")
            changed()
        } catch (e: Exception) { log("[profile] foto ignorada: ${e.message}") }
    }

    // ---- ping / detach ----
    private fun startPing(s: Session, link: Link) {
        link.pingJob?.cancel()
        link.pingJob = scope.launch {
            try {
                while (isActive) {
                    if (!link.isOpen()) { detach(link); break } // socket mudou: derruba na hora
                    delay(15_000)
                    try {
                        link.send(Message.create("ping"))
                    } catch (e: Exception) {
                        detach(link); break
                    }
                    s.missed++
                    if (s.missed >= 3) {
                        log("[conn] ${s.id}: 3 pongs perdidos.")
                        try { link.close() } catch (e: Exception) { }
                        detach(link)
                        break
                    }
                }
            } catch (e: CancellationException) { }
        }
    }

    private fun detach(link: Link) {
        var cleared = false
        for ((_, s) in sessions) {
            if (s.link === link) {
                s.link = null; s.linkLostAt = System.currentTimeMillis()
                s.state = "Desconectado"; cleared = true
            }
        }
        try { link.pingJob?.cancel() } catch (e: Exception) { }
        inboxes.remove(link.ctx)
        if (cleared) { log("[conn] caída. Backoff por par."); changed() }
    }

    private fun detachIfTag(s: Session, tag: Any) {
        val link = s.link
        if (link != null && link.tag === tag) detach(link)
    }

    private fun detachIfServerDead(ctx: MessageRouter.Ctx) {
        for ((_, s) in sessions) {
            val link = s.link
            if (link != null && !link.outgoing && link.ctx === ctx && !link.isOpen()) detach(link)
        }
    }

    private fun notifyPing(title: String, text: String) {
        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(
                "lumilink_ping", "LumiLink ping", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val n = androidx.core.app.NotificationCompat.Builder(appCtx, "lumilink_ping")
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setAutoCancel(true).setTimeoutAfter(8000).build()
            com.lumilink.app.notification.FocusIsland.attach(appCtx, n, title, text)
            nm.notify((1000 + Math.random() * 8000).toInt(), n)
        } catch (e: Exception) {
            log("[ping] notificação falhou: ${e.message}")
        }
    }

    // ---- snapshot p/ UI (sem bind) ----
    private fun changed() {
        try {
            val arr = JSONArray()
            for (v in getPeers()) arr.put(JSONObject()
                .put("deviceId", v.deviceId).put("name", v.name).put("state", v.state)
                .put("lastSeen", v.lastSeen).put("lastIp", v.lastIp).put("photoHash", v.photoHash)
                .put("media", v.media))
            appCtx.getSharedPreferences("peers_ui", Context.MODE_PRIVATE).edit()
                .putString("peers_json", arr.toString()).apply()
        } catch (e: Exception) { }
        try {
            // Bateria: só procura na rede quando há par desconectado.
            val all = peers.all()
            if (all.isEmpty() || all.all { sessions[it.deviceId]?.state == "Conectado" }) nsd?.stopDiscovery()
            else nsd?.startDiscovery()
        } catch (e: Exception) { }
        try { onChanged?.invoke() } catch (e: Exception) { }
    }
}

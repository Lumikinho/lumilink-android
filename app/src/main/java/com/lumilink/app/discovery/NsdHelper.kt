package com.lumilink.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

// Anuncia LumiLink-<deviceId> (id na instância = identidade canônica) e descobre pares.
class NsdHelper(private val ctx: Context, private val log: (String) -> Unit) {
    private val nsd: NsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    var onPeerSeen: ((id: String, ip: String, port: Int) -> Unit)? = null

    fun startAnnounce(deviceId: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "LumiLink-$deviceId"
            serviceType = "_lumilink._tcp"
            setPort(port)
        }
        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                log("[mdns] anunciando _lumilink._tcp porta $port.")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, c: Int) {
                log("[mdns] anunciar falhou ($c).")
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, c: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener) }
        catch (e: Exception) { log("[mdns] anunciar falhou: ${e.message}") }
    }

    fun startDiscovery() {
        discListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                try {
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            val id = parseId(r.serviceName) ?: return
                            val ip = r.host?.hostAddress ?: return
                            if (ip.contains(':')) return // só IPv4
                            onPeerSeen?.invoke(id, ip, r.port)
                        }
                        override fun onResolveFailed(s: NsdServiceInfo, c: Int) {}
                    })
                } catch (e: Exception) { Log.w("LumiLink", "resolve: ${e.message}") }
            }
            override fun onDiscoveryStarted(t: String) { log("[mdns] procurando pares…") }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, c: Int) {
                log("[mdns] descoberta falhou ($c) — usa último IP.")
            }
            override fun onStopDiscoveryFailed(t: String, c: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        try { nsd.discoverServices("_lumilink._tcp", NsdManager.PROTOCOL_DNS_SD, discListener) }
        catch (e: Exception) { log("[mdns] descoberta falhou: ${e.message}") }
    }

    fun stop() {
        try { regListener?.let { nsd.unregisterService(it) } } catch (e: Exception) { }
        try { discListener?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }
    }

    companion object {
        fun parseId(instance: String): String? {
            if (!instance.startsWith("LumiLink-")) return null
            return instance.removePrefix("LumiLink-").substringBefore(" (").trim()
                .takeIf { it.isNotEmpty() }
        }
    }
}

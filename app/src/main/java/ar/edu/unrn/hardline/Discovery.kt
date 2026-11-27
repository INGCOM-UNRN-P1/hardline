package ar.edu.unrn.hardline

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** DNS-SD común; filtra el TXT service antes de mostrar sesiones de hardboiled. */
@Suppress("DEPRECATION")
class Discovery(context: Context, private val found: (String, Int) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var epoch = 0
    fun start() {
        if (listener != null) return
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { stop() }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(this@Discovery) { pending.add(info); resolveNext(epoch) }
            }
        }
        nsd.discoverServices("_bridge-remote._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }
    @Synchronized private fun resolveNext(current: Int) {
        if (resolving || pending.isEmpty() || listener == null) return
        resolving = true
        nsd.resolveService(pending.removeFirst(), object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { finish(current) }
            override fun onServiceResolved(info: NsdServiceInfo) {
                if (current == epoch && info.attributes["service"]?.toString(Charsets.UTF_8) == "hardboiled") {
                    info.host?.hostAddress?.let { found(it, info.port) }
                }
                finish(current)
            }
        })
    }
    @Synchronized private fun finish(current: Int) {
        if (current != epoch) return
        resolving = false; resolveNext(current)
    }
    @Synchronized fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null; pending.clear(); resolving = false; epoch++
    }
}

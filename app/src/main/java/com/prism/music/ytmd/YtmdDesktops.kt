package com.prism.music.ytmd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.prism.music.BuildConfig
import com.prism.music.connect.Renderer
import com.prism.music.connect.RendererKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.net.URI

/**
 * YouTube Music Desktop apps on the network, as Prism Connect outputs: found with mDNS (YTMD 2.1.0+ announces its
 * companion server as `_ytmdesktop._tcp`), or added by address for a PC mDNS doesn't reach (another subnet, a VPN).
 * Keeps each PC's companion token, so pairing happens once.
 */
class YtmdDesktops(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("connect_ytmd", Context.MODE_PRIVATE)
    private val nsd: NsdManager? by lazy { app.getSystemService(NsdManager::class.java) }

    /** Whether this build has the YTMD output at all (`-PnoYtmd` builds stock Prism). */
    val enabled: Boolean get() = BuildConfig.YTMD

    // ------------------------------------------------------------ Tokens

    fun token(r: Renderer): String? = prefs.getString(tokenKey(r), null)
    fun saveToken(r: Renderer, token: String) = prefs.edit().putString(tokenKey(r), token).apply()
    fun forgetToken(r: Renderer) = prefs.edit().remove(tokenKey(r)).apply()
    private fun tokenKey(r: Renderer) = "token:" + r.id

    // ------------------------------------------------------------ PCs added by address

    /** Addresses ("192.168.1.20" or "192.168.1.20:9863") added by hand, as outputs. */
    fun manual(): List<Renderer> = prefs.getStringSet("manual", emptySet()).orEmpty().mapNotNull { manualRenderer(it) }.sortedBy { it.name }

    /** Adds [address] (an IP, optionally with its port); null when it isn't one. */
    fun addManual(address: String): Renderer? {
        val r = manualRenderer(address.trim()) ?: return null
        prefs.edit().putStringSet("manual", prefs.getStringSet("manual", emptySet()).orEmpty() + "${hostOf(r)}:${portOf(r)}").apply()
        return r
    }

    fun removeManual(r: Renderer) {
        prefs.edit().putStringSet("manual", prefs.getStringSet("manual", emptySet()).orEmpty() - "${hostOf(r)}:${portOf(r)}").apply()
        forgetToken(r)
    }

    private fun manualRenderer(address: String): Renderer? {
        val host = address.substringBeforeLast(':').takeIf { address.count { it == ':' } == 1 } ?: address
        val port = if (host != address) address.substringAfterLast(':').toIntOrNull() ?: return null else YtmdApi.DEFAULT_PORT
        val ipv4 = host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }
        if (!ipv4) return null
        return renderer("$host:$port", host, port, name = "YouTube Music Desktop ($host)", manual = true)
    }

    // ------------------------------------------------------------ mDNS

    /**
     * Starts listening for YTMD on the network (the output picker's open): [found] gets each one resolved, [lost] its
     * id when it goes. [resolveLock] is shared with the other mDNS lookups, as older Androids take one resolve at a time.
     * Returns what [stopDiscovery] takes, or null when there's no mDNS.
     */
    fun startDiscovery(scope: CoroutineScope, http: OkHttpClient, resolveLock: Mutex, found: (Renderer) -> Unit, lost: (String) -> Unit): NsdManager.DiscoveryListener? {
        if (!enabled) return null
        val manager = nsd ?: return null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("ytmdesktop", ignoreCase = true)) scope.launch(Dispatchers.IO) { resolve(manager, http, info, resolveLock)?.let(found) }
            }
            override fun onServiceLost(info: NsdServiceInfo) = lost(idFor(info.serviceName))
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "YTMD discovery didn't start ($error)") }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        return runCatching { manager.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener); listener }.getOrNull()
    }

    fun stopDiscovery(listener: NsdManager.DiscoveryListener?) {
        listener ?: return
        runCatching { nsd?.stopServiceDiscovery(listener) }
    }

    private suspend fun resolve(manager: NsdManager, http: OkHttpClient, info: NsdServiceInfo, lock: Mutex): Renderer? = lock.withLock {
        val r = withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, error: Int) { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                        override fun onServiceResolved(i: NsdServiceInfo) { if (cont.isActive) cont.resumeWith(Result.success(i)) }
                    })
                }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
            }
        } ?: return@withLock null
        @Suppress("DEPRECATION")
        val all = if (android.os.Build.VERSION.SDK_INT >= 34) r.hostAddresses else listOfNotNull(r.host)
        // IPv4: YTMD doesn't listen on IPv6. It lists every address the PC has (a VPN's, unplugged adapters' 169.254
        // ones…), so the one this phone can reach is picked: by network first, then by which one answers.
        val networks = phoneNetworks()
        var candidates = rankHosts(all.filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }.distinct(), networks)
        // Before Android 14 that's one address of the PC's, whichever came first (a VPN's, say): when it isn't on this
        // phone's network, the PC's name is asked for its others (YTMD's instance name is the PC's host name).
        if (candidates.none { isOnNetworks(it, networks) }) {
            val more = MdnsAddresses.lookup(r.serviceName)
            Log.i(TAG, "${r.serviceName}: resolver gave $candidates, the PC's name has $more")
            candidates = rankHosts((candidates + more).distinct(), networks)
        }
        if (candidates.isEmpty()) return@withLock null
        val port = r.port.takeIf { it > 0 } ?: YtmdApi.DEFAULT_PORT
        val host = candidates.take(MAX_PROBES).firstOrNull { h ->
            withTimeoutOrNull(PROBE_MS) { runCatching { YtmdApi(http, h, port).metadata() }.isSuccess } == true
        } ?: candidates.first()
        com.prism.music.playback.AudioDiagnostics.log("ytmd", "found ${r.serviceName}: $candidates, using $host")
        Log.i(TAG, "found ${r.serviceName} at $candidates, using $host:$port")
        renderer(r.serviceName, host, port, name = r.serviceName.ifBlank { host }, manual = false)
    }

    /** This phone's IPv4 networks (address and prefix length), for [rankHosts]. */
    private fun phoneNetworks(): List<Pair<String, Int>> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { i -> i.interfaceAddresses.filter { it.address is java.net.Inet4Address }.mapNotNull { a -> a.address.hostAddress?.let { it to a.networkPrefixLength.toInt() } } }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "PrismYtmd"
        const val SERVICE = "_ytmdesktop._tcp"
        const val PREFIX = "ytmd:"

        fun idFor(key: String) = PREFIX + key

        private const val MAX_PROBES = 4
        private const val PROBE_MS = 2_500L

        /**
         * A PC's IPv4 [candidates], most likely to reach first: on one of the phone's own [networks] (address, prefix
         * length), then private LAN addresses (10/8, 172.16/12, 192.168/16), then the rest (a VPN's 100.64/10), and
         * link-local (169.254/16, an adapter with no network) and loopback last.
         */
        fun rankHosts(candidates: List<String>, networks: List<Pair<String, Int>>): List<String> {
            fun rank(a: String): Int {
                val n = ipv4Bits(a) ?: return 9
                val o1 = (n ushr 24).toInt(); val o2 = ((n ushr 16) and 0xFF).toInt()
                return when {
                    o1 == 127 -> 5
                    o1 == 169 && o2 == 254 -> 4
                    isOnNetworks(a, networks) -> 0
                    o1 == 192 && o2 == 168 -> 1
                    o1 == 10 || (o1 == 172 && o2 in 16..31) -> 2
                    else -> 3
                }
            }
            return candidates.filter { ipv4Bits(it) != null }.sortedBy(::rank)
        }

        /** Whether IPv4 [address] is on one of [networks] (address, prefix length). */
        fun isOnNetworks(address: String, networks: List<Pair<String, Int>>): Boolean {
            val a = ipv4Bits(address) ?: return false
            return networks.any { (net, prefix) ->
                val b = ipv4Bits(net) ?: return@any false
                val p = prefix.coerceIn(0, 32)
                if (p == 0 || p == 32) return@any false
                val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
                a and mask == b and mask
            }
        }

        private fun ipv4Bits(a: String): Long? = a.split('.').takeIf { it.size == 4 }
            ?.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
            ?.fold(0L) { acc, n -> (acc shl 8) or n.toLong() }

        fun renderer(key: String, host: String, port: Int, name: String, manual: Boolean) = Renderer(
            id = idFor(key),
            name = name,
            manufacturer = "YouTube Music Desktop",
            model = if (manual) "manual" else "",
            location = "ytmd://$host:$port",
            avTransport = null,
            kind = RendererKind.YTMD,
        )

        fun hostOf(r: Renderer): String = runCatching { URI(r.location.replace("ytmd://", "http://")).host }.getOrNull().orEmpty()
        fun portOf(r: Renderer): Int = runCatching { URI(r.location.replace("ytmd://", "http://")).port }.getOrNull()?.takeIf { it > 0 } ?: YtmdApi.DEFAULT_PORT
        fun isManual(r: Renderer) = r.kind == RendererKind.YTMD && r.model == "manual"
    }
}

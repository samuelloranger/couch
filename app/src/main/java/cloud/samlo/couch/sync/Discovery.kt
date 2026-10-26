package cloud.samlo.couch.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Finds the other TVs on the network (mDNS through Android's own NsdManager, nothing added to the
 * APK) and announces this one. A TV is announced by its id, with its name and whether it is open
 * for pairing riding along, so the Add a TV screen only offers TVs that are waiting.
 */
class Discovery(context: Context) {
    class Found(val id: String, val name: String, val host: String, val port: Int, val pairing: Boolean)

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val seen = HashMap<String, Found>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onChange: (() -> Unit)? = null
    private var browsing: NsdManager.DiscoveryListener? = null
    private var announced: NsdManager.RegistrationListener? = null
    private var announcedWhat = ""

    @Synchronized fun found(): List<Found> = seen.values.toList()

    @Synchronized fun start(onChange: () -> Unit) {
        if (browsing != null || nsd == null) return
        this.onChange = onChange
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) = lose(info.serviceName)
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = clearBrowsing()
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        browsing = listener
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            browsing = null
        }
    }

    @Synchronized fun stop() {
        browsing?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        browsing = null
        seen.clear()
        queue.clear()
        resolving = false
        unannounce()
    }

    /** Replaces any earlier announcement: the pairing flag can only change by announcing again. */
    @Synchronized fun announce(id: String, name: String, port: Int, pairing: Boolean) {
        val what = "$id|$name|$port|$pairing"
        if (announced != null && what == announcedWhat) return
        unannounce()
        nsd ?: return
        announcedWhat = what
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            // Forgotten, so the next announce tries again instead of thinking it is already out.
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = failed(this)
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        announced = listener
        try {
            val info = NsdServiceInfo().apply {
                serviceName = NAME_PREFIX + id
                serviceType = TYPE
                this.port = port
                setAttribute("id", id)
                // A DNS text record holds 255 bytes a pair, and a name can be all multi-byte characters.
                setAttribute("name", fit(name, 100))
                setAttribute("pair", if (pairing) "1" else "0")
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            announced = null
        }
    }

    @Synchronized private fun failed(listener: NsdManager.RegistrationListener) {
        if (announced === listener) announced = null
    }

    /** The longest start of [text] that fits [bytes] as UTF-8, never cutting a character in half. */
    private fun fit(text: String, bytes: Int): String {
        var end = text.length
        while (end > 0 && text.substring(0, end).toByteArray().size > bytes) end = text.offsetByCodePoints(end, -1)
        return text.substring(0, end)
    }

    @Synchronized private fun unannounce() {
        announced?.let { runCatching { nsd?.unregisterService(it) } }
        announced = null
    }

    @Synchronized private fun clearBrowsing() {
        browsing = null
    }

    @Synchronized private fun enqueue(info: NsdServiceInfo) {
        if (!info.serviceName.startsWith(NAME_PREFIX)) return
        queue.addLast(info)
        next()
    }

    /** Android resolves one service at a time; the rest wait their turn. */
    private fun next() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        val done = {
            synchronized(this) {
                resolving = false
                next()
            }
        }
        try {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = done()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    add(info)
                    done()
                }
            })
        } catch (e: RuntimeException) {
            resolving = false
        }
    }

    private fun add(info: NsdServiceInfo) {
        val host = info.host?.hostAddress ?: return
        fun attr(key: String) = info.attributes[key]?.let { String(it) }
        val id = attr("id")?.take(Snapshot.MAX_NAME) ?: return
        val found = Found(id, attr("name")?.take(Snapshot.MAX_NAME) ?: id, host, info.port, attr("pair") == "1")
        synchronized(this) { seen[NAME_PREFIX + id] = found }
        onChange?.invoke()
    }

    private fun lose(serviceName: String) {
        val removed = synchronized(this) { seen.remove(serviceName) != null }
        if (removed) onChange?.invoke()
    }

    companion object {
        private const val TYPE = "_couch._tcp."
        private const val NAME_PREFIX = "couch-"
    }
}

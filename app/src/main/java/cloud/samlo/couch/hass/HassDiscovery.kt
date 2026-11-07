package cloud.samlo.couch.hass

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds Home Assistant on the home network: it announces `_home-assistant._tcp` over mDNS with its
 * address in the TXT record. Only a suggestion for the phone page, which the user can change.
 */
object HassDiscovery {
    private const val TYPE = "_home-assistant._tcp"

    /** Blocking, up to [timeoutMs]: call from a worker thread. Null when nothing answers. */
    fun find(context: Context, timeoutMs: Long): String? {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return null
        val found = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = latch.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (latch.count == 0L) return
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            found.compareAndSet(null, urlOf(info))
                            latch.countDown()
                        }
                    })
                } catch (e: RuntimeException) {
                    // Android resolves one service at a time; the first answer is enough.
                }
            }
        }
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            return null
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return found.get()
    }

    private fun urlOf(info: NsdServiceInfo): String? {
        fun attr(key: String) = info.attributes[key]?.let { String(it) }?.takeIf { it.isNotBlank() }
        attr("internal_url")?.let { return HassJson.baseUrl(it) }
        attr("base_url")?.let { return HassJson.baseUrl(it) }
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress ?: return null
        return "http://$host:${info.port}"
    }
}

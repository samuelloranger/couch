package cloud.samlo.couch.hass

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Plain HTTP stays on the home network. Android's network policy can't name the user's own
 * addresses, so it allows cleartext everywhere; Couch checks here, before a request leaves, that
 * an `http://` Home Assistant resolves to a private, link-local or loopback address.
 */
object LocalNetwork {
    /** True for https, or for http to an address on the home network. Blocking: may resolve a name. */
    fun allowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return when (uri.scheme) {
            "https" -> true
            "http" -> {
                val host = uri.host ?: return false
                val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return false
                addresses.isNotEmpty() && addresses.all { isPrivate(it) }
            }
            else -> false
        }
    }

    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        // IPv6 unique local addresses (fc00::/7) are the home network's too.
        return address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
    }
}

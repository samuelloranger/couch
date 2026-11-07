package cloud.samlo.couch

import cloud.samlo.couch.hass.LocalNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalNetworkTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun homeAddressesArePrivate() {
        listOf("192.168.1.10", "10.0.0.2", "172.16.5.4", "127.0.0.1", "169.254.1.1", "fd12:3456::1", "fe80::1").forEach {
            assertTrue(it, LocalNetwork.isPrivate(ip(it)))
        }
        listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888").forEach { assertFalse(it, LocalNetwork.isPrivate(ip(it))) }
    }

    @Test
    fun plainHttpOnlyToTheHomeNetwork() {
        assertTrue(LocalNetwork.allowed("http://192.168.1.10:8123"))
        assertFalse(LocalNetwork.allowed("http://8.8.8.8:8123"))
        assertTrue(LocalNetwork.allowed("https://example.com"))
        assertFalse(LocalNetwork.allowed("ftp://192.168.1.10"))
        assertFalse(LocalNetwork.allowed("not a url"))
    }
}

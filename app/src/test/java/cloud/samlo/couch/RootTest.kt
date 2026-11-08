package cloud.samlo.couch

import cloud.samlo.couch.adb.Root
import cloud.samlo.couch.remote.RelayProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTest {
    @Test
    fun onlyUidZeroCountsAsRoot() {
        assertTrue(Root.parse("uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0"))
        assertFalse(Root.parse("uid=2000(shell) gid=2000(shell)"))
        assertFalse(Root.parse("uid=10(app) gid=0(root)"))
        assertFalse(Root.parse("su: permission denied"))
        assertFalse(Root.parse(""))
    }

    @Test
    fun theCommandIsOneArgumentToSu() {
        val relay = RelayProtocol.start("/lib", 1234, "tok")
        assertEquals(listOf("su", "-c", relay), Root.command(relay))
        assertEquals(listOf("su", "-c", "input text 'a b'"), Root.command("input text 'a b'"))
    }
}

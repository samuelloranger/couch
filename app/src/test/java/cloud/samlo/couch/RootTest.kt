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

class PidLineTest {
    private fun run(vararg chunks: ByteArray): Pair<Int?, ByteArray> {
        val line = cloud.samlo.couch.adb.PidLine()
        val out = java.io.ByteArrayOutputStream()
        chunks.forEach { out.write(line.feed(it)) }
        return line.pid to out.toByteArray()
    }

    @Test
    fun theMarkerLineIsStrippedAndTheRestPassesThrough() {
        val video = byteArrayOf(0, 0, 0, 1, 0x67, 10, 0x0A, 3)
        val (pid, out) = run("PID:4242\n".toByteArray() + video)
        assertEquals(4242, pid)
        org.junit.Assert.assertArrayEquals(video, out)
    }

    @Test
    fun aMarkerSplitAcrossReadsStillParses() {
        val bytes = "PID:77\n".toByteArray() + byteArrayOf(1, 2, 3)
        for (cut in 1 until 7) {
            val (pid, out) = run(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size))
            assertEquals(77, pid)
            org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), out)
        }
    }

    @Test
    fun outputWithoutAMarkerIsKeptWhole() {
        val data = byteArrayOf(0, 0, 0, 1, 0x65, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)
        val (pid, out) = run(data.copyOfRange(0, 3), data.copyOfRange(3, data.size))
        assertEquals(null, pid)
        org.junit.Assert.assertArrayEquals(data, out)
        assertEquals(null, run("PID:x\nmore".toByteArray()).first)
    }

    @Test
    fun theWrapperReportsItsPidThenBecomesTheCommand() {
        assertEquals("echo PID:\$\$; exec screenrecord --size 1x1 -", cloud.samlo.couch.adb.PidLine.wrap("screenrecord --size 1x1 -"))
        assertTrue(cloud.samlo.couch.adb.PidLine.kill(9).startsWith("kill 9;"))
        assertTrue(cloud.samlo.couch.adb.PidLine.kill(9).endsWith("kill -9 9 2>/dev/null"))
    }
}

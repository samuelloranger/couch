package cloud.samlo.couch

import cloud.samlo.couch.capture.CaptureMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMathTest {
    @Test fun parsesArguments() {
        val a = CaptureMath.parse(arrayOf("960", "540", "1500000", "60"))!!
        assertEquals(960, a.width); assertEquals(540, a.height); assertEquals(1_500_000, a.bitRate); assertEquals(60, a.maxFps)
        assertNull(CaptureMath.parse(arrayOf("960", "540", "1500000"))!!.maxFps)
    }

    @Test fun refusesBadArguments() {
        assertNull(CaptureMath.parse(arrayOf("960", "540")))
        assertNull(CaptureMath.parse(arrayOf("a", "540", "1")))
        assertNull(CaptureMath.parse(arrayOf("960", "0", "1")))
        assertNull(CaptureMath.parse(arrayOf("1", "2", "3", "4", "5")))
    }

    @Test fun roundsToSixteen() {
        assertEquals(544, CaptureMath.round16(540))
        assertEquals(960, CaptureMath.round16(960))
        assertEquals(16, CaptureMath.round16(3))
        assertEquals(528, CaptureMath.round16(535))
    }

    @Test fun fitKeepsAspectAndNeverScalesUp() {
        assertEquals(960 to 544, CaptureMath.fit(1920, 1080, 960, 540))
        assertEquals(1280 to 720, CaptureMath.fit(3840, 2160, 1280, 720))
        assertEquals(1280 to 720, CaptureMath.fit(1280, 720, 1920, 1080))
        assertEquals(304 to 544, CaptureMath.fit(1080, 1920, 960, 540))
    }

    @Test fun buildsTheCommand() {
        assertEquals(
            "env CLASSPATH='/data/app/x/base.apk' app_process / cloud.samlo.couch.capture.Capture 960 540 1500000 60",
            CaptureMath.command("/data/app/x/base.apk", 960, 540, 1_500_000, 60),
        )
        assertTrue(CaptureMath.command("/a'b", 1, 2, 3, null).startsWith("env CLASSPATH='/a'\\''b' "))
        assertTrue(CaptureMath.command("/a", 1, 2, 3, null).endsWith(" 1 2 3"))
    }

    @Test fun decidesTheFallback() {
        assertTrue(CaptureMath.fallBack(500, ended = true, gotConfig = false))
        assertTrue(CaptureMath.fallBack(60_000, ended = true, gotConfig = false))
        assertTrue(CaptureMath.fallBack(1_500, ended = true, gotConfig = true))
        assertFalse(CaptureMath.fallBack(60_000, ended = true, gotConfig = true))
        assertFalse(CaptureMath.fallBack(2_900, ended = false, gotConfig = false))
        assertTrue(CaptureMath.fallBack(3_000, ended = false, gotConfig = false))
        assertFalse(CaptureMath.fallBack(10_000, ended = false, gotConfig = true))
    }
}

package cloud.samlo.couch

import cloud.samlo.couch.adb.ShellOutput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellOutputTest {
    @Test
    fun silenceAndSuccessAreNotAFailure() {
        assertFalse(ShellOutput.refused(""))
        assertFalse(ShellOutput.refused("Success\n"))
        assertFalse(ShellOutput.refused("package:com.example.app\n"))
    }

    @Test
    fun anUnknownOptionIsAFailure() {
        assertTrue(ShellOutput.refused("Error: Unknown option: --cache-only\n"))
        assertTrue(ShellOutput.refused("unknown option --cache-only\n"))
    }

    @Test
    fun anErrorOrAnExceptionOnAnyLineIsAFailure() {
        assertTrue(ShellOutput.refused("java.lang.SecurityException: not allowed\n"))
        assertTrue(ShellOutput.refused("10 apps kept us awake\nError: Activity not started\n"))
        assertTrue(ShellOutput.refused("Failure [com.example.app]\n"))
    }
}

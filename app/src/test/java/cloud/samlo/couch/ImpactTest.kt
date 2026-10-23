package cloud.samlo.couch

import cloud.samlo.couch.debloat.Impact
import cloud.samlo.couch.debloat.Impacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a removal buys: memory read from dumpsys, and the line between worth it and not. */
class ImpactTest {
    private val dumpsys = """
        ** MEMINFO in pid 2811 [com.example.tvutil] **
                           Pss(KB)
           TOTAL PSS:     5215            TOTAL RSS:    65900       TOTAL SWAP PSS:       86
        ** MEMINFO in pid 3000 [com.example.media] **
           TOTAL PSS:      800            TOTAL RSS:    30000
        ** MEMINFO in pid 3001 [com.example.media:player] **
           TOTAL PSS:     1200            TOTAL RSS:    41000
        No process found for: com.example.idle
    """.trimIndent()

    @Test fun memoryAddsUpAnAppsProcesses() {
        val memory = Impacts.parseMemory(dumpsys)
        assertEquals(5215, memory["com.example.tvutil"])
        assertEquals(2000, memory["com.example.media"])
        assertEquals(null, memory["com.example.idle"])
    }

    @Test fun noSectionMeansNoNumbers() {
        assertTrue(Impacts.parseMemory("dumpsys: permission denied").isEmpty())
    }

    @Test fun worthMeansRunningAllTheTimeOrUsingAMegabyte() {
        assertTrue(Impact(alwaysOn = true, atBoot = false, memoryKb = null, lastUsed = null).worth)
        assertTrue(Impact(alwaysOn = false, atBoot = false, memoryKb = 2048, lastUsed = null).worth)
        assertFalse(Impact(alwaysOn = false, atBoot = true, memoryKb = 0, lastUsed = null).worth)
    }
}

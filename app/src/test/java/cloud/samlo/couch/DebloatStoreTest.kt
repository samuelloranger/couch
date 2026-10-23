package cloud.samlo.couch

import cloud.samlo.couch.debloat.DebloatStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The ledger: originals recorded once, removals remembered, undos held, all across restarts. */
class DebloatStoreTest {
    private fun dir(): File = createTempDir("debloat")

    private fun original(pkg: String, d: File): String =
        JSONObject(File(d, "debloat.json").readText()).getJSONObject("originals").getString(pkg)

    @Test fun originalsAreRecordedOnce() {
        val d = dir()
        val store = DebloatStore.load(d)
        store.record("com.t.app", present = true)
        store.record("com.t.app", present = false) // a later scan changes nothing
        assertEquals("present", original("com.t.app", d))
    }

    @Test fun entriesListWhatisStillRemoved() {
        val d = dir()
        val store = DebloatStore.load(d)
        store.record("com.t.a", true)
        store.removed("com.t.a", viaShell = true)
        store.record("com.t.b", true)
        store.removed("com.t.b", viaShell = false)
        assertEquals(DebloatStore.Tally(fromShell = 1, active = 2, ever = 2), store.tally())
        store.undone("com.t.a")
        assertEquals(DebloatStore.Tally(fromShell = 0, active = 1, ever = 2), store.tally())
        assertEquals(listOf("com.t.b"), store.entries())
    }

    @Test fun undosSurviveARestart() {
        val d = dir()
        DebloatStore.load(d).apply {
            record("com.t.a", true)
            removed("com.t.a", viaShell = true)
        }
        val back = DebloatStore.load(d)
        assertEquals(listOf("com.t.a"), back.entries())
        back.undone("com.t.a")
        assertFalse(DebloatStore.load(d).entries().contains("com.t.a"))
    }

    @Test fun theBeforeSnapshotIsNeverOverwritten() {
        val d = dir()
        DebloatStore.load(d).apply { saw(512, 3100) }
        DebloatStore.load(d).apply { saw(999, 4400) }
        val kept = DebloatStore.load(d)
        assertEquals(512, kept.before.ramFreeMb)
        assertEquals(3100, kept.before.storageFreeMb)
    }

    @Test fun theBatchFlowSurvivesARestart() {
        val d = dir()
        DebloatStore.load(d).apply {
            startFlow(listOf(listOf("com.t.a", "com.t.b"), listOf("com.t.c")))
            ran(listOf("com.t.a"), boot = 7)
        }
        val back = DebloatStore.load(d).flow!!
        assertEquals(0, back.index)
        assertEquals(listOf("com.t.a"), back.check)
        assertEquals(7, back.boot)
        assertEquals(listOf("com.t.c"), back.batches[1])
    }

    @Test fun nextWalksTheBatchesThenEndsTheFlow() {
        val d = dir()
        val store = DebloatStore.load(d)
        store.startFlow(listOf(listOf("com.t.a"), listOf("com.t.b")))
        store.ran(listOf("com.t.a"), boot = 1)
        assertEquals(listOf("com.t.b"), store.next())
        assertTrue(store.flow!!.check.isEmpty())
        assertEquals(null, store.next())
        assertEquals(null, DebloatStore.load(d).flow)
    }

    @Test fun aBrokenFileGivesAnEmptyLedger() {
        val d = dir()
        File(d, "debloat.json").writeText("{not json at all")
        val store = DebloatStore.load(d)
        assertTrue(store.entries().isEmpty())
        assertEquals(-1, store.before.ramFreeMb)
    }
}

package cloud.samlo.couch

import cloud.samlo.couch.debloat.DebloatStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The ledger: removals remembered, undos held, all across restarts. */
class DebloatStoreTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("debloat").toFile()

    @Test fun entriesListWhatisStillRemoved() {
        val d = dir()
        val store = DebloatStore.load(d)
        store.removed("com.t.a")
        store.removed("com.t.b")
        assertEquals(DebloatStore.Tally(active = 2, ever = 2), store.tally())
        store.undone("com.t.a")
        assertEquals(DebloatStore.Tally(active = 1, ever = 2), store.tally())
        assertEquals(listOf("com.t.b"), store.entries())
    }

    @Test fun undosSurviveARestart() {
        val d = dir()
        DebloatStore.load(d).apply {
            removed("com.t.a")
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
        assertEquals("{not json at all", File(d, "debloat.json.bad").readText())
    }
}

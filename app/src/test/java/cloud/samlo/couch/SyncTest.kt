package cloud.samlo.couch

import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.sync.Merge
import cloud.samlo.couch.sync.Snapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SyncTest {
    private val today = LocalDate.of(2026, 10, 25)

    private fun week(vararg ms: Long) = LongArray(Usage.DAYS).also { ms.forEachIndexed { i, v -> it[Usage.DAYS - ms.size + i] = v } }

    private fun snapshot(lastDay: LocalDate, apps: Map<String, AppUsage>, id: String = "bedroom", labels: Map<String, String> = emptyMap()) =
        Snapshot(id, "Bedroom", 1_000L, lastDay, apps, labels)

    @Test
    fun peersAddToThisTvsOwnDays() {
        val local = mapOf("tv.app" to AppUsage(week(10, 20), 2, 500L))
        val peer = snapshot(today, mapOf("tv.app" to AppUsage(week(1, 2), 3, 900L), "tv.other" to AppUsage(week(5), 1, 100L)))
        val merged = Merge.all(local, emptyMap(), today, listOf(peer))
        assertArrayEquals(week(11, 22), merged.getValue("tv.app").perDayMs)
        assertEquals(5, merged.getValue("tv.app").opens)
        assertEquals(900L, merged.getValue("tv.app").lastUsed)
        assertArrayEquals(week(5), merged.getValue("tv.other").perDayMs)
    }

    @Test
    fun aSnapshotFromYesterdayLandsOnYesterdaysBar() {
        val peer = snapshot(today.minusDays(1), mapOf("tv.app" to AppUsage(week(0, 7, 9), 1, 1L)))
        val merged = Merge.all(emptyMap(), emptyMap(), today, listOf(peer))
        // 9 ms was the peer's today, which is this TV's yesterday; the 7 before it moves back too.
        assertArrayEquals(week(7, 9, 0), merged.getValue("tv.app").perDayMs)
    }

    @Test
    fun daysThatFellOutOfTheWindowAreDropped() {
        val peer = snapshot(today.minusDays(3), mapOf("tv.app" to AppUsage(LongArray(Usage.DAYS) { (it + 1) * 10L }, 1, 1L)))
        val merged = Merge.all(emptyMap(), emptyMap(), today, listOf(peer)).getValue("tv.app")
        assertArrayEquals(longArrayOf(40, 50, 60, 70, 0, 0, 0), merged.perDayMs)
    }

    @Test
    fun aSnapshotOlderThanTheWindowIsIgnoredWhole() {
        val peer = snapshot(today.minusDays(7), mapOf("tv.app" to AppUsage(week(9), 4, 9_000L)))
        assertEquals(emptyMap<String, AppUsage>(), Merge.all(emptyMap(), emptyMap(), today, listOf(peer)))
    }

    @Test
    fun aPeerClockAheadOfThisTvShiftsTheOtherWay() {
        val peer = snapshot(today.plusDays(1), mapOf("tv.app" to AppUsage(week(0, 3, 4), 1, 1L)))
        // The peer's today is tomorrow here, so its yesterday (3) is this TV's today and its 4 is past the window.
        assertArrayEquals(week(0, 0, 3), Merge.all(emptyMap(), emptyMap(), today, listOf(peer)).getValue("tv.app").perDayMs)
    }

    @Test
    fun mergingTheSameSnapshotAgainGivesTheSameAnswer() {
        val local = mapOf("tv.app" to AppUsage(week(10), 1, 5L))
        val peer = snapshot(today, mapOf("tv.app" to AppUsage(week(4), 1, 6L)))
        val once = Merge.all(local, emptyMap(), today, listOf(peer))
        val again = Merge.all(local, emptyMap(), today, listOf(peer))
        assertArrayEquals(once.getValue("tv.app").perDayMs, again.getValue("tv.app").perDayMs)
    }

    @Test
    fun aSnapshotSurvivesItsOwnJson() {
        val original = snapshot(today, mapOf("tv.app" to AppUsage(week(1, 2, 3), 4, 5L)))
        val back = Snapshot.fromJson(original.toJson())
        assertNotNull(back)
        assertEquals("bedroom", back!!.deviceId)
        assertEquals(today, back.lastDay)
        assertArrayEquals(week(1, 2, 3), back.apps.getValue("tv.app").perDayMs)
        assertEquals(4, back.apps.getValue("tv.app").opens)
    }

    @Test
    fun junkIsNotASnapshot() {
        assertNull(Snapshot.fromJson("not json"))
        assertNull(Snapshot.fromJson("""{"version":2,"deviceId":"a","name":"b","generatedAt":1,"lastDay":"2026-10-25","apps":{}}"""))
        assertNull(Snapshot.fromJson("""{"version":1,"deviceId":"a","name":"b","generatedAt":1,"lastDay":"someday","apps":{}}"""))
        assertNull(Snapshot.fromJson("""{"version":1,"deviceId":"a","name":"b","generatedAt":1,"lastDay":"2026-10-25","apps":{"x":{"perDayMs":[1,2],"opens":1,"lastUsed":1}}}"""))
    }

    @Test
    fun sameServiceUnderAnotherPackageMergesByLabel() {
        val local = mapOf("ca.bell.android" to AppUsage(week(10), 1, 5L))
        val peer = snapshot(today, mapOf("ca.bell.fire" to AppUsage(week(4), 2, 9L)), labels = mapOf("ca.bell.fire" to "Bell  Fibé TV"))
        val merged = Merge.all(local, mapOf("ca.bell.android" to "bell fibe tv"), today, listOf(peer))
        assertEquals(setOf("ca.bell.android"), merged.keys)
        assertArrayEquals(week(14), merged.getValue("ca.bell.android").perDayMs)
        assertEquals(3, merged.getValue("ca.bell.android").opens)
    }

    @Test
    fun aPackageMatchWinsOverALabelMatch() {
        val local = mapOf("a" to AppUsage(week(1), 1, 1L), "b" to AppUsage(week(100), 1, 1L))
        val peer = snapshot(today, mapOf("a" to AppUsage(week(2), 1, 1L)), labels = mapOf("a" to "Beta"))
        val merged = Merge.all(local, mapOf("a" to "Alpha", "b" to "Beta"), today, listOf(peer))
        assertArrayEquals(week(3), merged.getValue("a").perDayMs)
        assertArrayEquals(week(100), merged.getValue("b").perDayMs)
    }

    @Test
    fun differentNamesStaySeparate() {
        val local = mapOf("a" to AppUsage(week(1), 1, 1L))
        val peer = snapshot(today, mapOf("x" to AppUsage(week(2), 1, 1L)), labels = mapOf("x" to "Other"))
        assertEquals(setOf("a", "x"), Merge.all(local, mapOf("a" to "Alpha"), today, listOf(peer)).keys)
    }

    @Test
    fun twoAppsOnOnePeerNeverMergeIntoEachOther() {
        val local = mapOf("a" to AppUsage(week(1), 1, 1L))
        val peer = snapshot(today, mapOf("x" to AppUsage(week(2), 1, 1L), "y" to AppUsage(week(3), 1, 1L)), labels = mapOf("x" to "Alpha", "y" to "Alpha"))
        val merged = Merge.all(local, mapOf("a" to "Alpha"), today, listOf(peer))
        assertEquals(setOf("a", "y"), merged.keys)
        assertArrayEquals(week(3), merged.getValue("a").perDayMs)
        assertArrayEquals(week(3), merged.getValue("y").perDayMs)
    }

    @Test
    fun peerOnlyAppsMatchEachOtherByLabel() {
        val one = snapshot(today, mapOf("x" to AppUsage(week(2), 1, 1L)), id = "one", labels = mapOf("x" to "Show"))
        val two = snapshot(today, mapOf("y" to AppUsage(week(5), 1, 1L)), id = "two", labels = mapOf("y" to "SHOW"))
        val merged = Merge.all(emptyMap(), emptyMap(), today, listOf(one, two))
        assertEquals(setOf("x"), merged.keys)
        assertArrayEquals(week(7), merged.getValue("x").perDayMs)
    }

    @Test
    fun labelsSurviveJsonAndOldSnapshotsHaveNone() {
        val back = Snapshot.fromJson(snapshot(today, mapOf("a" to AppUsage(week(1), 1, 1L)), labels = mapOf("a" to "Alpha")).toJson())
        assertEquals("Alpha", back!!.labels["a"])
        val old = Snapshot.fromJson("""{"version":1,"deviceId":"a","name":"b","generatedAt":1,"lastDay":"2026-10-25","apps":{"x":{"perDayMs":[0,0,0,0,0,0,1],"opens":1,"lastUsed":1}}}""")
        assertEquals(emptyMap<String, String>(), old!!.labels)
    }
}

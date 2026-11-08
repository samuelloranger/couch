package cloud.samlo.couch

import cloud.samlo.couch.phone.AccessUnit
import cloud.samlo.couch.phone.AccessUnits
import cloud.samlo.couch.phone.AnnexB
import cloud.samlo.couch.phone.GopCache
import cloud.samlo.couch.phone.Wire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVideoTest {
    private val sps = byteArrayOf(0x67, 1, 2, 3)
    private val pps = byteArrayOf(0x68, 9)
    private fun idr(tag: Int) = byteArrayOf(0x65, 0x88.toByte(), tag.toByte(), 7)
    private fun slice(tag: Int) = byteArrayOf(0x41, 0x9A.toByte(), tag.toByte())
    private fun sliceTwo(tag: Int) = byteArrayOf(0x41, 0x1A, tag.toByte())

    private fun annexB(vararg nals: ByteArray, long: Boolean = false): ByteArray =
        nals.fold(ByteArray(0)) { acc, nal -> acc + (if (long) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)) + nal }

    private fun split(data: ByteArray, chunk: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val splitter = AnnexB { out.add(it) }
        var at = 0
        while (at < data.size) {
            val n = minOf(chunk, data.size - at)
            splitter.feed(data, at, n)
            at += n
        }
        splitter.flush()
        return out
    }

    @Test
    fun splitsThreeAndFourByteStartCodesWhateverTheChunking() {
        val nals = listOf(sps, pps, idr(1), slice(2))
        for (long in listOf(false, true)) {
            val data = annexB(*nals.toTypedArray(), long = long)
            for (chunk in listOf(1, 2, 3, 5, 100)) {
                val got = split(data, chunk)
                assertEquals("long=$long chunk=$chunk", nals.size, got.size)
                nals.indices.forEach { assertArrayEquals(nals[it], got[it]) }
            }
        }
    }

    @Test
    fun theLastNalWaitsForTheNextStartCodeOrAFlush() {
        val out = ArrayList<ByteArray>()
        val splitter = AnnexB { out.add(it) }
        splitter.feed(annexB(sps, pps))
        assertEquals(1, out.size)
        splitter.flush()
        assertEquals(2, out.size)
        assertArrayEquals(pps, out[1])
        // Nothing is pending after a flush.
        splitter.flush()
        assertEquals(2, out.size)
    }

    @Test
    fun zeroBytesBeforeAStartCodeBelongToItNotToTheNal() {
        val data = byteArrayOf(0, 0, 0, 1, 0x65, 5, 0, 0, 0, 0, 0, 1, 0x41, 6)
        val got = split(data, 4)
        assertArrayEquals(byteArrayOf(0x65, 5), got[0])
        assertArrayEquals(byteArrayOf(0x41, 6), got[1])
    }

    @Test
    fun aNalThatNeverEndsIsDroppedNotBuffered() {
        val out = ArrayList<ByteArray>()
        val splitter = AnnexB { out.add(it) }
        splitter.feed(byteArrayOf(0, 0, 1, 0x65))
        val block = ByteArray(1 shl 20) { 0x55 }
        repeat(6) { splitter.feed(block) }
        splitter.flush()
        assertTrue(out.isEmpty())
    }

    private class Collected {
        val units = ArrayList<AccessUnit>()
        val configs = ArrayList<Pair<ByteArray, ByteArray>>()
        val groups = AccessUnits({ units.add(it) }, { s, p -> configs.add(s to p) })
    }

    @Test
    fun groupsSlicesIntoFramesAndKeepsParameterSetsOutOfThem() {
        val c = Collected()
        listOf(sps, pps, idr(1), slice(2), slice(3)).forEach(c.groups::add)
        c.groups.flush()
        assertEquals(3, c.units.size)
        assertTrue(c.units[0].key)
        assertFalse(c.units[1].key)
        assertEquals(1, c.units[0].nals.size)
        assertEquals(1, c.configs.size)
        assertArrayEquals(sps, c.configs[0].first)
        assertArrayEquals(pps, c.configs[0].second)
    }

    @Test
    fun aSecondSliceOfTheSameFrameJoinsIt() {
        val c = Collected()
        listOf(slice(1), sliceTwo(2), slice(3)).forEach(c.groups::add)
        c.groups.flush()
        assertEquals(2, c.units.size)
        assertEquals(2, c.units[0].nals.size)
    }

    @Test
    fun aRepeatedSpsPpsIsNotANewConfigButAChangedOneIs() {
        val c = Collected()
        listOf(sps, pps, idr(1), sps, pps, idr(2)).forEach(c.groups::add)
        assertEquals(1, c.configs.size)
        listOf(byteArrayOf(0x67, 5, 5), pps, idr(3)).forEach(c.groups::add)
        c.groups.flush()
        assertEquals(2, c.configs.size)
    }

    @Test
    fun framesAreEncodedAsTheSpecSays() {
        val unit = AccessUnit(true, listOf(byteArrayOf(0x65, 1, 2), byteArrayOf(0x41)))
        assertArrayEquals(byteArrayOf(3, 1, 0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 1, 0x41), unit.frame())
        assertArrayEquals(byteArrayOf(3, 0, 0, 0, 0, 1, 0x41), AccessUnit(false, listOf(byteArrayOf(0x41))).frame())
        assertArrayEquals(byteArrayOf(2, 0, 4, 0x67, 1, 2, 3, 0, 2, 0x68, 9), Wire.config(sps, pps))
    }

    @Test
    fun aJoinerGetsTheFramesSinceTheLastKeyframe() {
        val cache = GopCache()
        cache.add(AccessUnit(false, listOf(slice(0))))
        assertTrue("nothing before the first keyframe", cache.replay().isEmpty())
        cache.add(AccessUnit(true, listOf(idr(1))))
        cache.add(AccessUnit(false, listOf(slice(2))))
        cache.add(AccessUnit(false, listOf(slice(3))))
        assertEquals(3, cache.replay().size)
        cache.add(AccessUnit(true, listOf(idr(4))))
        assertEquals(1, cache.replay().size)
        assertTrue(cache.replay()[0].key)
    }

    @Test
    fun aRunPastTheCapIsDroppedUntilTheNextKeyframe() {
        val cache = GopCache(limit = 100)
        cache.add(AccessUnit(true, listOf(ByteArray(40))))
        cache.add(AccessUnit(false, listOf(ByteArray(40))))
        cache.add(AccessUnit(false, listOf(ByteArray(40))))
        assertTrue(cache.replay().isEmpty())
        cache.add(AccessUnit(false, listOf(ByteArray(10))))
        assertTrue(cache.replay().isEmpty())
        cache.add(AccessUnit(true, listOf(ByteArray(10))))
        assertEquals(1, cache.replay().size)
    }
}

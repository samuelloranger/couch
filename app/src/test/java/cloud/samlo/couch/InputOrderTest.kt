package cloud.samlo.couch

import android.media.tv.TvInputInfo
import cloud.samlo.couch.data.inputOrder
import cloud.samlo.couch.data.inputRank
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Inputs row's order: HDMI ports, then other ports, then the tuner, alphabetical within each group. */
class InputOrderTest {
    @Test
    fun hdmiPortsRankBeforeOtherPortsAndTheTuner() {
        assertEquals(0, inputRank(TvInputInfo.TYPE_HDMI, true))
        assertEquals(1, inputRank(TvInputInfo.TYPE_COMPOSITE, true))
        assertEquals(2, inputRank(TvInputInfo.TYPE_TUNER, false))
    }

    @Test
    fun sortsByGroupThenByName() {
        val inputs = listOf(
            Triple("Live TV", TvInputInfo.TYPE_TUNER, false),
            Triple("HDMI 2", TvInputInfo.TYPE_HDMI, true),
            Triple("AV", TvInputInfo.TYPE_COMPOSITE, true),
            Triple("HDMI 1", TvInputInfo.TYPE_HDMI, true),
        )
        val sorted = inputs.sortedWith(inputOrder({ inputRank(it.second, it.third) }, { it.first })).map { it.first }
        assertEquals(listOf("HDMI 1", "HDMI 2", "AV", "Live TV"), sorted)
    }
}

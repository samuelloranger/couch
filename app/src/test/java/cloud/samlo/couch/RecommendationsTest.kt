package cloud.samlo.couch

import cloud.samlo.couch.data.Recommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {
    @Test
    fun channelIdsAreNegativeSoTheyNeverMeetPreviewChannelIds() {
        for (pkg in listOf("com.netflix.ninja", "com.amazon.tahoe", "a", "")) {
            assertTrue(Recommendations.channelId(pkg) < 0)
            assertTrue(Recommendations.channelId(pkg, "trendingNow") < 0)
        }
    }

    @Test
    fun channelIdsAreStablePerAppAndGroup() {
        assertEquals(Recommendations.channelId("com.netflix.ninja"), Recommendations.channelId("com.netflix.ninja"))
        assertNotEquals(Recommendations.channelId("com.netflix.ninja"), Recommendations.channelId("com.amazon.tahoe"))
        assertNotEquals(
            Recommendations.channelId("com.netflix.ninja", "trendingNow"),
            Recommendations.channelId("com.netflix.ninja", "continueWatching"),
        )
    }

    @Test
    fun eachGroupIsAChannelAndAnAppsBestRankedGroupComesFirst() {
        val groups = Recommendations.group(
            listOf(
                item("com.netflix.ninja", "trendingNow", 2),
                item("com.netflix.ninja", "continueWatching", 0),
                item("com.netflix.ninja", "trendingNow", 1),
                item("com.amazon.tahoe", null, 5),
            ),
        )
        assertEquals(
            listOf("com.amazon.tahoe" to null, "com.netflix.ninja" to "continueWatching", "com.netflix.ninja" to "trendingNow"),
            groups.map { it.packageName to it.key },
        )
        assertEquals(listOf(1, 2), groups[2].items.map { it.rank })
        // An app that doesn't group keeps the id its single channel always had.
        assertEquals(Recommendations.channelId("com.amazon.tahoe"), groups[0].id)
    }

    @Test
    fun groupKeysReadAsWords() {
        assertEquals("Trending now", Recommendations.words("trendingNow"))
        assertEquals("Top picks for you", Recommendations.words("top_picks-for_you"))
        assertEquals("Top 10", Recommendations.words("top10"))
        assertEquals("Popular", Recommendations.words("POPULAR"))
    }

    private fun item(pkg: String, group: String?, rank: Int) =
        Recommendations.Item("$pkg|$rank", pkg, group, "Title $rank", null, null, -1f, rank, null)
}

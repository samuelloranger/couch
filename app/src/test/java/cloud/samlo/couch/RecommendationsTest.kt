package cloud.samlo.couch

import cloud.samlo.couch.data.Recommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {
    @Test
    fun channelIdsAreNegativeSoTheyNeverMeetPreviewChannelIds() {
        for (pkg in listOf("com.netflix.ninja", "com.amazon.tahoe", "a", "")) assertTrue(Recommendations.channelId(pkg) < 0)
    }

    @Test
    fun channelIdsAreStablePerApp() {
        assertEquals(Recommendations.channelId("com.netflix.ninja"), Recommendations.channelId("com.netflix.ninja"))
        assertNotEquals(Recommendations.channelId("com.netflix.ninja"), Recommendations.channelId("com.amazon.tahoe"))
    }
}

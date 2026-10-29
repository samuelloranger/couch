package cloud.samlo.couch

import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Programs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramsTest {
    @Test
    fun channelsDefaultToShownRegardlessOfStockLauncherVisibility() {
        for (browsable in listOf(false, true)) {
            val channel = Programs.ChannelInfo(7, "app.example", "Videos", browsable)
            assertTrue(channel.shown(Config()))
        }
    }

    @Test
    fun savedChannelChoicesOverrideTheDefault() {
        val config = Config.fromJson("""{"channels":{"7":false,"8":true}}""")
        assertFalse(Programs.ChannelInfo(7, "app.example", "Hidden", true).shown(config))
        assertTrue(Programs.ChannelInfo(8, "app.example", "Shown", false).shown(config))
        assertTrue(Programs.ChannelInfo(9, "app.example", "New", false).shown(config))
    }
}

package cloud.samlo.couch

import cloud.samlo.couch.data.AppOrder
import cloud.samlo.couch.data.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {
    private val apps = listOf("a", "b", "c", "d")
    private val usage = mapOf("a" to 10L, "b" to 100L, "c" to 30L)

    @Test
    fun favoritesStayInTheirOrderAheadOfUsageInEveryMode() {
        val config = Config().apply { pinnedApps = listOf("c", "a") }
        for (sort in AppOrder.sorts) {
            config.appSort = sort
            assertEquals(listOf("c", "a", "b", "d"), AppOrder.sorted(apps, config, usage))
        }
    }

    @Test
    fun usageAndAlphabeticalModesKeepTheirMeaning() {
        val config = Config()
        assertEquals(listOf("b", "c", "a", "d"), AppOrder.sorted(apps, config, usage))
        assertEquals(apps, AppOrder.sorted(apps, config, null))
        config.appSort = "alphabetical"
        assertEquals(apps, AppOrder.sorted(apps, config, usage))
    }

    @Test
    fun movingAnAppEnablesManualOrderAndNewAppsFollowAlphabetically() {
        val config = Config()
        AppOrder.move(config, listOf("b", "c", "a"), "a", 0)
        assertEquals("manual", config.appSort)
        assertEquals(listOf("a", "b", "c", "d"), AppOrder.sorted(apps, config, usage))
        AppOrder.move(config, apps, "b", 3)
        assertEquals(listOf("a", "c", "d", "b"), AppOrder.sorted(apps, config, usage))
    }

    @Test
    fun movingFavoritesDoesNotChangeTheOtherAppsOrSortingMode() {
        val config = Config().apply { pinnedApps = listOf("b", "c", "not-installed") }
        AppOrder.move(config, listOf("b", "c", "a", "d"), "c", 0)
        assertEquals(listOf("c", "b", "not-installed"), config.pinnedApps)
        assertEquals("usage", config.appSort)
        assertEquals(listOf("c", "b", "a", "d"), AppOrder.sorted(apps, config, usage))
    }

    @Test
    fun pinAndUnpinPreserveManualOrderAndIgnoreMissingApps() {
        val config = Config().apply { appSort = "manual"; manualAppOrder = listOf("d", "b", "a", "c") }
        AppOrder.pin(config, "a")
        assertEquals(listOf("a", "d", "b", "c"), AppOrder.sorted(apps, config, usage))
        AppOrder.pin(config, "a")
        assertEquals(listOf("d", "b", "a", "c"), AppOrder.sorted(apps, config, usage))
        AppOrder.move(config, apps, "missing", 0)
        AppOrder.move(config, apps, "a", 9)
        assertEquals(listOf("d", "b", "a", "c"), config.manualAppOrder)
    }

    @Test
    fun savedSettingsRoundTripAndUnknownSortsFallBack() {
        val original = Config().apply {
            appSort = "manual"
            manualAppOrder = listOf("b", "a")
            pinnedApps = listOf("c")
            preferredChannels = mapOf("a" to "12", "b" to "-15")
        }
        val copy = Config.fromJson(original.toJson())
        assertEquals(original.toJson(), copy.toJson())
        assertEquals(listOf("c", "b", "a", "d"), AppOrder.sorted(apps, copy, usage))
        assertEquals("usage", Config.fromJson("""{"appSort":"future"}""").appSort)
        assertEquals("usage", Config.fromJson("{}").appSort)
    }
}

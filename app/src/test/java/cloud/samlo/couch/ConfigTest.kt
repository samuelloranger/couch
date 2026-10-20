package cloud.samlo.couch

import cloud.samlo.couch.data.Accents
import cloud.samlo.couch.data.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun defaultsAreGeneric() {
        val config = Config()
        assertTrue(config.pinnedApps.isEmpty())
        assertTrue("com.sony.dtv.promos" in config.hiddenApps)
        assertEquals("medium", config.cardSize)
        assertEquals("rows", config.layout)
        assertEquals(5, config.ambientMinutes)
        assertEquals(3, config.wallpaperHours)
        assertEquals(Config.DEFAULT_SUBREDDIT, config.subreddit)
    }

    @Test
    fun roundTripKeepsEveryField() {
        val config = Config().apply {
            pinnedApps = listOf("a.b", "c.d")
            hiddenApps = setOf("x.y")
            hiddenInputs = setOf("hdmi3")
            names = mapOf("hdmi4" to "Console")
            inputIcons = mapOf("hdmi4" to "ps5")
            sectionOrder = listOf("inputs", "apps", "ch:7")
            hiddenSections = setOf("ch:7")
            channels = mapOf("7" to true, "8" to false)
            layout = "index"
            cardSize = "large"
            accent = "#34D399"
            wallpaper = "reddit"
            dim = "strong"
            showLabels = true
            showDescriptions = false
            clock24h = false
            showDate = false
            subreddit = "EarthPorn"
            wallpaperHours = 6
            weather = false
            weatherCity = "Montréal"
            fahrenheit = true
            ambientMinutes = 0
        }
        val copy = Config.fromJson(config.toJson())
        assertEquals(config.toJson(), copy.toJson())
        assertEquals(listOf("a.b", "c.d"), copy.pinnedApps)
        assertEquals(mapOf("7" to true, "8" to false), copy.channels)
        assertEquals("ps5", copy.inputIcons["hdmi4"])
        assertEquals("Montréal", copy.weatherCity)
        assertFalse(copy.showDescriptions)
        assertEquals("index", copy.layout)
    }

    @Test
    fun missingKeysKeepDefaults() {
        val config = Config.fromJson("""{"cardSize":"small","pinnedApps":["a.b"]}""")
        assertEquals("small", config.cardSize)
        assertEquals(listOf("a.b"), config.pinnedApps)
        assertEquals(Config.DEFAULT_HIDDEN, config.hiddenApps)
        assertTrue(config.clock24h)
    }

    @Test
    fun defaultAccentIsMint() {
        assertEquals("#34D399", Config().accent)
        assertEquals("#34D399", Accents.default)
    }

    @Test
    fun theOldIndigoDefaultMigratesToMint() {
        assertEquals("#34D399", Config.fromJson("""{"accent":"#8B93FF"}""").accent)
        assertEquals("#34D399", Config.fromJson("""{"accent":"#8b93ff"}""").accent)
    }

    @Test
    fun otherRetiredAccentsMigrateToTheirSuccessor() {
        assertEquals("#34D399", Config.fromJson("""{"accent":"#4ADE80"}""").accent)
        assertEquals("#FF6B81", Config.fromJson("""{"accent":"#F472B6"}""").accent)
    }

    @Test
    fun currentAndCustomAccentsAreKept() {
        assertEquals("#FBBF24", Config.fromJson("""{"accent":"#FBBF24"}""").accent)
        assertEquals("#123456", Config.fromJson("""{"accent":"#123456"}""").accent)
    }

    @Test
    fun noAccentOfferedIsPurple() {
        assertTrue(Accents.all.none { it.hex.equals("#8B93FF", ignoreCase = true) })
        assertEquals(6, Accents.all.size)
    }

    @Test
    fun malformedFilesGiveDefaults() {
        assertEquals(Config().toJson(), Config.fromJson("not json").toJson())
        assertEquals(Config().toJson(), Config.fromJson("").toJson())
    }

    @Test
    fun badChannelValueDoesNotCrash() {
        val config = Config.fromJson("""{"channels":{"1":"maybe","2":true}}""")
        assertEquals(true, config.channels["2"])
        assertEquals(false, config.channels["1"])
    }

    @Test
    fun saveThenLoadThroughAFile() {
        val dir = folder.newFolder()
        val config = Config().apply { cardSize = "large"; hiddenInputs = setOf("hdmi1") }
        config.save(dir)
        val loaded = Config.load(dir)
        assertEquals("large", loaded.cardSize)
        assertEquals(setOf("hdmi1"), loaded.hiddenInputs)
        assertFalse(dir.resolve("config.json.tmp").exists())
    }

    @Test
    fun loadWithoutAFileGivesDefaults() {
        assertEquals(Config().toJson(), Config.load(folder.newFolder()).toJson())
        assertEquals(Config().toJson(), Config.load(null).toJson())
    }

    @Test
    fun cardScaleFollowsSize() {
        assertEquals(0.85f, Config().apply { cardSize = "small" }.cardScale, 0f)
        assertEquals(1f, Config().cardScale, 0f)
        assertEquals(1.18f, Config().apply { cardSize = "large" }.cardScale, 0f)
    }
}

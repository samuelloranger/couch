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
            channels = mapOf("7" to true, "8" to false)
            accent = "#34D399"
            wallpaper = "reddit"
            clock24h = false
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
        assertFalse(copy.clock24h)
    }

    @Test
    fun aFileFromTheRowsLayoutStillLoadsAndForgetsIt() {
        val old = """{"layout":"rows","cardSize":"large","sectionOrder":["inputs","apps"],"hiddenSections":["ch:7"],""" +
            """"dim":"strong","showLabels":true,"showDescriptions":false,"showDate":false,"weatherCity":"Montréal"}"""
        val config = Config.fromJson(old)
        assertEquals("Montréal", config.weatherCity)
        listOf("layout", "cardSize", "sectionOrder", "hiddenSections", "dim", "showLabels", "showDescriptions", "showDate").forEach {
            assertFalse(it, config.toJson().contains("\"$it\""))
        }
    }

    @Test
    fun syncIsOffByDefaultAndKeepsItsChoices() {
        assertFalse(Config().syncEnabled)
        assertTrue(Config().syncAllTvs)
        val back = Config.fromJson(Config().apply { syncEnabled = true; syncAllTvs = false }.toJson())
        assertTrue(back.syncEnabled)
        assertFalse(back.syncAllTvs)
    }

    @Test
    fun missingKeysKeepDefaults() {
        val config = Config.fromJson("""{"wallpaper":"gradient","pinnedApps":["a.b"]}""")
        assertEquals("gradient", config.wallpaper)
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
        val config = Config().apply { wallpaper = "custom"; hiddenInputs = setOf("hdmi1") }
        config.save(dir)
        val loaded = Config.load(dir)
        assertEquals("custom", loaded.wallpaper)
        assertEquals(setOf("hdmi1"), loaded.hiddenInputs)
        assertFalse(dir.resolve("config.json.tmp").exists())
    }

    @Test
    fun loadWithoutAFileGivesDefaults() {
        assertEquals(Config().toJson(), Config.load(folder.newFolder()).toJson())
        assertEquals(Config().toJson(), Config.load(null).toJson())
    }
}

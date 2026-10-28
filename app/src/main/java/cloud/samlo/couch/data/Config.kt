package cloud.samlo.couch.data

import java.io.File

/**
 * Everything the user can customise, stored in `Android/data/cloud.samlo.couch/files/config.json`
 * so it can also be edited or backed up over ADB. Custom images go next to it in
 * `icons/<key>.png` (an app's package name, or an input's slug such as `hdmi4`) and
 * `wallpaper.jpg`.
 */
class Config {
    // Apps and inputs
    var pinnedApps: List<String> = DEFAULT_PINNED
    var hiddenApps: Set<String> = DEFAULT_HIDDEN
    var hiddenInputs: Set<String> = emptySet()
    /** Custom names, keyed like icons. */
    var names: Map<String, String> = emptyMap()
    /** Input key to an [InputIcon] id. */
    var inputIcons: Map<String, String> = emptyMap()

    // Rows
    var sectionOrder: List<String> = emptyList()
    var hiddenSections: Set<String> = emptySet()
    /** Channel id to shown or not, overriding what the stock launcher had enabled. */
    var channels: Map<String, Boolean> = emptyMap()

    // Look
    /** "index" (a list of apps beside what the focused one offers, the default) or "rows" (cards in rows). */
    var layout: String = LAYOUT_INDEX
    var cardSize: String = "medium"
    var accent: String = Accents.default
    var wallpaper: String = "daily"
    var dim: String = "medium"
    var showLabels = false
    var showDescriptions = true
    var clock24h = true
    var showDate = true

    // Wallpaper: "daily" (Bing photo of the day), "reddit", "gradient" or "custom" (wallpaper.jpg)
    var subreddit = DEFAULT_SUBREDDIT
    var wallpaperHours = 3

    // Weather
    var weather = true
    /** Empty means locate by IP address. */
    var weatherCity = ""
    var fahrenheit = false

    /** Minutes idle on the home screen before ambient mode; 0 turns it off. */
    var ambientMinutes = 5

    /** Once a day, look for a newer release on GitHub. */
    var checkUpdates = true

    /** Seven OK presses on About unlocked it; the hidden Godmode page is then in Customize. */
    var godmode = false

    /** Share screen time with the TVs this one is paired with, over the home network only. */
    var syncEnabled = false

    /** With sync on: Index adds the other TVs' screen time to this one's. */
    var syncAllTvs = true

    /** Godmode's update channel: pre-releases too, never older than what's installed. */
    var preReleases = false

    /**
     * The remote's Input and Settings buttons open Couch's panels instead of the TV's menus, through
     * a relay started over the TV's own ADB. Off by default: it needs debugging and changes how the
     * remote's keys reach Android.
     */
    var remoteButtons = false

    /** Linux key code of the remote's Input button, as the relay sees it; 0 when not set up. */
    var remoteInputKey = 0

    /** Linux key code of the remote's Settings button; 0 when not set up. */
    var remoteSettingsKey = 0

    /** Godmode's frame HUD on the home screen: [HUD_OFF], [HUD_MS] or [HUD_FPS]. */
    var hud = HUD_OFF

    val cardScale: Float
        get() = when (cardSize) {
            "small" -> 0.85f
            "large" -> 1.18f
            else -> 1f
        }

    /** Changes whenever a setting that affects how cards are drawn changes. */
    val look: String get() = "$cardSize|$accent|$showLabels"

    /** Written atomically so a half-written file never wipes the user's setup. */
    fun save(dir: File?) {
        dir ?: return
        val tmp = File(dir, "config.json.tmp")
        tmp.writeText(toJson())
        tmp.renameTo(File(dir, FILE))
    }

    fun toJson(): String = ConfigJson.encode(this).toString(2)

    companion object {
        private const val FILE = "config.json"
        const val DEFAULT_SUBREDDIT = "WidescreenWallpaper"
        const val HUD_OFF = "off"

        /** How long each frame took: slow frames stand out, and fast ones still show their headroom. */
        const val HUD_MS = "ms"

        /** The same frames as a rate, capped at what the screen can actually show. */
        const val HUD_FPS = "fps"

        const val LAYOUT_ROWS = "rows"
        const val LAYOUT_INDEX = "index"

        /** Defaults stay generic: the user pins their own apps. */
        val DEFAULT_PINNED = emptyList<String>()

        // Vendor extras that are noise on a home screen. Reachable again by un-hiding them.
        val DEFAULT_HIDDEN = setOf(
            "com.sony.dtv.promos",
            "com.sony.dtv.sonyselect",
            "com.sony.dtv.seeds.iot",
            "com.sony.dtv.notificationcenter",
            "com.sony.dtv.smartmediaapp",
            "com.sony.dtv.timers",
            "com.sony.dtv.osat.music",
            "com.sony.dtv.tvx",
            "com.spocky.projengmenu",
        )

        fun load(dir: File?): Config {
            val text = try {
                dir?.let { File(it, FILE) }?.takeIf { it.isFile }?.readText()
            } catch (e: Exception) {
                null
            } ?: return Config()
            return fromJson(text)
        }

        /** Unreadable or malformed text gives the defaults, never a crash. */
        fun fromJson(text: String): Config {
            val config = Config()
            val json = try {
                org.json.JSONObject(text)
            } catch (e: Exception) {
                return config
            }
            ConfigJson.decode(json, config)
            return config
        }
    }
}

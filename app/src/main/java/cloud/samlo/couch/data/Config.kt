package cloud.samlo.couch.data

import java.io.File

/**
 * Everything the user can customise, stored in `Android/data/cloud.samlo.couch/files/config.json`
 * so it can also be edited or backed up over ADB. Custom images go next to it in
 * `icons/<key>.png` (an app's package name, or an input's slug such as `hdmi4`) and
 * `wallpapers/` (or the original `wallpaper.jpg`).
 */
class Config {
    // Apps and inputs
    var pinnedApps: List<String> = DEFAULT_PINNED
    /** Unpinned apps follow usage, a saved manual order, or their names. */
    var appSort: String = "usage"
    var manualAppOrder: List<String> = emptyList()
    var hiddenApps: Set<String> = DEFAULT_HIDDEN
    var hiddenInputs: Set<String> = emptySet()
    /** Custom names, keyed like icons. */
    var names: Map<String, String> = emptyMap()
    /** Input key to an [InputIcon] id. */
    var inputIcons: Map<String, String> = emptyMap()

    // Channels
    /** Channel id to shown or not; channels without a choice are shown. */
    var channels: Map<String, Boolean> = emptyMap()
    /** Package name to the last channel chosen on the home screen. */
    var preferredChannels: Map<String, String> = emptyMap()

    // Look
    var accent: String = Accents.default
    var wallpaper: String = "daily"
    var clock24h = true

    // Wallpaper: "daily" (Bing photo of the day), "reddit", "gradient" or "custom" (wallpapers/)
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

    /** Home Assistant's base URL, such as `http://homeassistant.local:8123`; empty hides the row. */
    var homeAssistantUrl = ""

    /** The lights and plugs in the Index's row, in order; at most [MAX_HOME_DEVICES]. */
    var homeDevices: List<String> = emptyList()

    /** Short names for the row's pills, by entity id; a missing one uses Home Assistant's name. */
    var homeNames: Map<String, String> = emptyMap()

    /** Godmode's frame HUD on the home screen: [HUD_OFF], [HUD_MS] or [HUD_FPS]. */
    var hud = HUD_OFF

    /** Written atomically, one writer at a time, so a half-written file never wipes the user's setup; a failed write keeps the old file. */
    fun save(dir: File?) {
        dir ?: return
        val text = toJson()
        synchronized(lock) {
            try {
                writeAtomically(File(dir, FILE), text)
            } catch (e: java.io.IOException) {
                // The old file stays; losing one change beats crashing a click handler.
            }
        }
    }

    fun toJson(): String = ConfigJson.encode(this).toString(2)

    companion object {
        private const val FILE = "config.json"
        private val lock = Any()
        const val DEFAULT_SUBREDDIT = "WidescreenWallpaper"
        const val HUD_OFF = "off"

        /** The row is one line at the top of the Index; six pills fit beside the weather and chips by starting over the app list. */
        const val MAX_HOME_DEVICES = 6

        /** How long each frame took: slow frames stand out, and fast ones still show their headroom. */
        const val HUD_MS = "ms"

        /** The same frames as a rate, capped at what the screen can actually show. */
        const val HUD_FPS = "fps"

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
            val file = dir?.let { File(it, FILE) }?.takeIf { it.isFile } ?: return Config()
            val json = try {
                org.json.JSONObject(file.readText())
            } catch (e: Exception) {
                // Kept aside, not overwritten by the next save.
                keepAside(file)
                return Config()
            }
            return Config().also { ConfigJson.decode(json, it) }
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

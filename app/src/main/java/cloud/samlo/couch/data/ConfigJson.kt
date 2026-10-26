package cloud.samlo.couch.data

import org.json.JSONArray
import org.json.JSONObject

/** The on-disk shape of [Config]. Key names are a user-facing contract: never rename them. */
internal object ConfigJson {
    fun encode(c: Config): JSONObject = JSONObject()
        .put("pinnedApps", JSONArray(c.pinnedApps))
        .put("hiddenApps", JSONArray(c.hiddenApps.sorted()))
        .put("hiddenInputs", JSONArray(c.hiddenInputs.sorted()))
        .put("names", JSONObject(c.names))
        .put("inputIcons", JSONObject(c.inputIcons))
        .put("sectionOrder", JSONArray(c.sectionOrder))
        .put("hiddenSections", JSONArray(c.hiddenSections.sorted()))
        .put("channels", JSONObject(c.channels))
        .put("layout", c.layout)
        .put("cardSize", c.cardSize)
        .put("accent", c.accent)
        .put("wallpaper", c.wallpaper)
        .put("dim", c.dim)
        .put("showLabels", c.showLabels)
        .put("showDescriptions", c.showDescriptions)
        .put("clock24h", c.clock24h)
        .put("showDate", c.showDate)
        .put("subreddit", c.subreddit)
        .put("wallpaperHours", c.wallpaperHours)
        .put("weather", c.weather)
        .put("weatherCity", c.weatherCity)
        .put("fahrenheit", c.fahrenheit)
        .put("ambientMinutes", c.ambientMinutes)
        .put("checkUpdates", c.checkUpdates)
        .put("godmode", c.godmode)
        .put("preReleases", c.preReleases)
        .put("syncEnabled", c.syncEnabled)
        .put("syncAllTvs", c.syncAllTvs)
        .put("hud", c.hud)

    /** Every field is optional; a missing one keeps what [into] already holds. */
    fun decode(json: JSONObject, into: Config) = with(into) {
        json.strings("pinnedApps")?.let { pinnedApps = it }
        json.strings("hiddenApps")?.let { hiddenApps = it.toSet() }
        json.strings("hiddenInputs")?.let { hiddenInputs = it.toSet() }
        names = json.stringMap("names")
        inputIcons = json.stringMap("inputIcons")
        json.strings("sectionOrder")?.let { sectionOrder = it }
        json.strings("hiddenSections")?.let { hiddenSections = it.toSet() }
        channels = json.optJSONObject("channels")?.let { o -> o.keys().asSequence().associateWith { o.optBoolean(it) } }
            ?: emptyMap()
        layout = json.optString("layout", layout)
        cardSize = json.optString("cardSize", cardSize)
        accent = Accents.migrate(json.optString("accent", accent))
        wallpaper = json.optString("wallpaper", wallpaper)
        dim = json.optString("dim", dim)
        showLabels = json.optBoolean("showLabels", showLabels)
        showDescriptions = json.optBoolean("showDescriptions", showDescriptions)
        clock24h = json.optBoolean("clock24h", clock24h)
        showDate = json.optBoolean("showDate", showDate)
        subreddit = json.optString("subreddit", subreddit)
        wallpaperHours = json.optInt("wallpaperHours", wallpaperHours)
        weather = json.optBoolean("weather", weather)
        weatherCity = json.optString("weatherCity", weatherCity)
        fahrenheit = json.optBoolean("fahrenheit", fahrenheit)
        ambientMinutes = json.optInt("ambientMinutes", ambientMinutes)
        checkUpdates = json.optBoolean("checkUpdates", checkUpdates)
        godmode = json.optBoolean("godmode", godmode)
        preReleases = json.optBoolean("preReleases", preReleases)
        syncEnabled = json.optBoolean("syncEnabled", syncEnabled)
        syncAllTvs = json.optBoolean("syncAllTvs", syncAllTvs)
        // Was a boolean before the HUD could show either unit.
        hud = when (val value = json.opt("hud")) {
            is Boolean -> if (value) Config.HUD_MS else Config.HUD_OFF
            is String -> value
            else -> hud
        }
    }

    private fun JSONObject.strings(name: String): List<String>? {
        val array = optJSONArray(name) ?: return null
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun JSONObject.stringMap(name: String): Map<String, String> {
        val o = optJSONObject(name) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.getString(it) }
    }
}

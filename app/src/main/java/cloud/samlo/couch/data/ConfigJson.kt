package cloud.samlo.couch.data

import cloud.samlo.couch.hass.HassDevice
import org.json.JSONArray
import org.json.JSONObject

/** The on-disk shape of [Config]. Key names are a user-facing contract: never rename them. */
internal object ConfigJson {
    fun encode(c: Config): JSONObject = JSONObject()
        .put("pinnedApps", JSONArray(c.pinnedApps))
        .put("appSort", c.appSort)
        .put("manualAppOrder", JSONArray(c.manualAppOrder))
        .put("hiddenApps", JSONArray(c.hiddenApps.sorted()))
        .put("hiddenInputs", JSONArray(c.hiddenInputs.sorted()))
        .put("names", JSONObject(c.names))
        .put("inputIcons", JSONObject(c.inputIcons))
        .put("folders", foldersJson(c.folders))
        .put("channels", JSONObject(c.channels))
        .put("preferredChannels", JSONObject(c.preferredChannels))
        .put("accent", c.accent)
        .put("wallpaper", c.wallpaper)
        .put("clock24h", c.clock24h)
        .put("continueWatching", c.continueWatching)
        .put("subreddit", c.subreddit)
        .put("wallpaperHours", c.wallpaperHours)
        .put("weather", c.weather)
        .put("weatherCity", c.weatherCity)
        .put("fahrenheit", c.fahrenheit)
        .put("ambientMinutes", c.ambientMinutes)
        .put("longSleepMinutes", c.longSleepMinutes)
        .put("checkUpdates", c.checkUpdates)
        .put("godmode", c.godmode)
        .put("preReleases", c.preReleases)
        .put("syncEnabled", c.syncEnabled)
        .put("syncAllTvs", c.syncAllTvs)
        .put("hud", c.hud)
        .put("remoteButtons", c.remoteButtons)
        .put("remoteInputKey", c.remoteInputKey)
        .put("remoteSettingsKey", c.remoteSettingsKey)
        .put("homeAssistantUrl", c.homeAssistantUrl)
        .put("homeDevices", JSONArray(c.homeDevices))
        .put("homeNames", JSONObject(c.homeNames))

    /** Every field is optional; a missing one keeps what [into] already holds. */
    fun decode(json: JSONObject, into: Config) = with(into) {
        json.strings("pinnedApps")?.let { pinnedApps = it }
        appSort = json.optString("appSort", appSort).takeIf { it in AppOrder.sorts } ?: "usage"
        json.strings("manualAppOrder")?.let { manualAppOrder = it.distinct() }
        json.strings("hiddenApps")?.let { hiddenApps = it.toSet() }
        json.strings("hiddenInputs")?.let { hiddenInputs = it.toSet() }
        names = json.stringMap("names")
        inputIcons = json.stringMap("inputIcons")
        json.folders("folders")?.let { folders = it }
        preferredChannels = json.stringMap("preferredChannels")
        channels = json.optJSONObject("channels")?.let { o -> o.keys().asSequence().associateWith { o.optBoolean(it) } }
            ?: emptyMap()
        accent = Accents.migrate(json.optString("accent", accent))
        wallpaper = json.optString("wallpaper", wallpaper)
        clock24h = json.optBoolean("clock24h", clock24h)
        continueWatching = json.optBoolean("continueWatching", continueWatching)
        subreddit = json.optString("subreddit", subreddit)
        wallpaperHours = json.optInt("wallpaperHours", wallpaperHours)
        weather = json.optBoolean("weather", weather)
        weatherCity = json.optString("weatherCity", weatherCity)
        fahrenheit = json.optBoolean("fahrenheit", fahrenheit)
        ambientMinutes = json.optInt("ambientMinutes", ambientMinutes)
        longSleepMinutes = json.optInt("longSleepMinutes", longSleepMinutes)
        checkUpdates = json.optBoolean("checkUpdates", checkUpdates)
        godmode = json.optBoolean("godmode", godmode)
        preReleases = json.optBoolean("preReleases", preReleases)
        syncEnabled = json.optBoolean("syncEnabled", syncEnabled)
        syncAllTvs = json.optBoolean("syncAllTvs", syncAllTvs)
        // Was a boolean before the HUD could show either unit.
        remoteButtons = json.optBoolean("remoteButtons", remoteButtons)
        remoteInputKey = json.optInt("remoteInputKey", remoteInputKey).coerceAtLeast(0)
        remoteSettingsKey = json.optInt("remoteSettingsKey", remoteSettingsKey).coerceAtLeast(0)
        homeAssistantUrl = json.optString("homeAssistantUrl", homeAssistantUrl)
        json.strings("homeDevices")?.let { ids ->
            homeDevices = ids.filter { HassDevice.supported(it) }.distinct().take(Config.MAX_HOME_DEVICES)
        }
        homeNames = json.stringMap("homeNames")
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

    private fun foldersJson(folders: List<Folder>) = JSONArray(
        folders.map { folder ->
            JSONObject().put("id", folder.id).put("name", folder.name).put("apps", JSONArray(folder.apps))
        },
    )

    /** `[{id, name, apps}]`; an entry missing one of the three is skipped, and so is a broken app key. */
    private fun JSONObject.folders(name: String): List<Folder>? {
        val array = optJSONArray(name) ?: return null
        val out = ArrayList<Folder>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val label = entry.optString("name").takeIf { it.isNotEmpty() } ?: continue
            val apps = entry.optJSONArray("apps") ?: continue
            out += Folder(id, label, (0 until apps.length()).mapNotNull { apps.optString(it).takeIf(String::isNotEmpty) })
        }
        return Folders.clean(out)
    }
}

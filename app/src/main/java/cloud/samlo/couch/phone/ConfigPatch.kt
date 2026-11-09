package cloud.samlo.couch.phone

import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.ConfigJson
import org.json.JSONException
import org.json.JSONObject

/**
 * A phone's `config.set`: its patch laid over the current config in `config.json`'s own shape,
 * then decoded the way the file is, so every value passes the same checks as one typed on the
 * TV. Keys the TV doesn't know are ignored.
 */
object ConfigPatch {
    /** The config with [patch] applied. Throws [JSONException] for a value of the wrong type. */
    fun apply(current: Config, patch: JSONObject): Config {
        val merged = ConfigJson.encode(current)
        for (key in patch.keys()) {
            if (!merged.has(key)) continue
            val value = patch.get(key)
            // optBoolean and friends would quietly turn "nope" into the old value: say so instead.
            if (!sameType(merged.get(key), value)) throw JSONException("$key has the wrong type")
            merged.put(key, value)
        }
        val out = Config()
        ConfigJson.decode(merged, out)
        // The TV shows the subreddit as r/name and takes either; an empty one means the default.
        out.subreddit = out.subreddit.trim().removePrefix("r/").ifEmpty { Config.DEFAULT_SUBREDDIT }
        out.ambientMinutes = out.ambientMinutes.coerceIn(0, MAX_AMBIENT_MINUTES)
        out.wallpaperHours = out.wallpaperHours.coerceIn(1, MAX_WALLPAPER_HOURS)
        return out
    }

    private fun sameType(current: Any, value: Any) = when (current) {
        is Boolean -> value is Boolean
        is Number -> value is Number
        is String -> value is String
        is org.json.JSONArray -> value is org.json.JSONArray
        is JSONObject -> value is JSONObject
        else -> true
    }

    private const val MAX_AMBIENT_MINUTES = 240
    private const val MAX_WALLPAPER_HOURS = 24 * 7
}

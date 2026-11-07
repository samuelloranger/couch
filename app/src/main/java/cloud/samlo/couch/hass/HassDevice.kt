package cloud.samlo.couch.hass

import org.json.JSONArray
import org.json.JSONObject

/** One light or plug as Home Assistant last reported it. */
data class HassDevice(
    val id: String,
    val name: String,
    val on: Boolean,
    /** False when Home Assistant reports it unavailable (unplugged, out of range). */
    val available: Boolean,
    /** The light takes a brightness; plugs and on/off-only lights don't. */
    val dimmable: Boolean,
    /** 0 to 100; meaningful only for a dimmable light that is on. */
    val brightness: Int,
) {
    val isLight get() = id.startsWith(LIGHT)
    val domain get() = id.substringBefore('.')

    companion object {
        const val LIGHT = "light."
        const val SWITCH = "switch."

        /** The only entities the row shows: what it draws is a bulb or a plug. */
        fun supported(id: String) = id.startsWith(LIGHT) || id.startsWith(SWITCH)
    }
}

/** Home Assistant's REST answers, read into [HassDevice]s. */
object HassJson {
    /** One `/api/states/<id>` object; null for anything that isn't a light or a plug. */
    fun device(json: JSONObject): HassDevice? {
        val id = json.optString("entity_id")
        if (!HassDevice.supported(id)) return null
        val state = json.optString("state")
        val attributes = json.optJSONObject("attributes") ?: JSONObject()
        val name = attributes.optString("friendly_name").trim().ifEmpty { id.substringAfter('.') }
        val modes = attributes.optJSONArray("supported_color_modes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val dimmable = id.startsWith(HassDevice.LIGHT) && modes.any { it != "onoff" && it.isNotEmpty() }
        // 0 to 255, absent while the light is off.
        val raw = attributes.optInt("brightness", 0).coerceIn(0, 255)
        return HassDevice(
            id = id,
            name = name,
            on = state == "on",
            available = state != "unavailable" && state != "unknown" && state.isNotEmpty(),
            dimmable = dimmable,
            brightness = if (raw == 0) 0 else ((raw * 100 + 127) / 255).coerceIn(1, 100),
        )
    }

    /** The `/api/states` list, lights and plugs only, by name. */
    fun devices(text: String): List<HassDevice>? {
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            return null
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::device) }.sortedBy { it.name.lowercase() }
    }

    /** What the user typed or pasted, as a base URL: scheme added, trailing slash and `/api` dropped. */
    fun baseUrl(input: String): String {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        if (url.endsWith("/api")) url = url.removeSuffix("/api")
        return url.trimEnd('/')
    }

    /**
     * A name short enough for the row's pills: at most [MAX_LABEL] characters, without the model and
     * channel some integrations append ("Tree (mss110) - Main channel" gives "Tree").
     */
    fun shortName(name: String): String {
        val trimmed = name.substringBefore(" (").substringBefore(" - ").trim().ifEmpty { name.trim() }
        return if (trimmed.length <= MAX_LABEL) trimmed else trimmed.take(MAX_LABEL - 1).trimEnd() + "…"
    }

    const val MAX_LABEL = 14
}

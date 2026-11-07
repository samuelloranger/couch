package cloud.samlo.couch.hass

import org.json.JSONArray
import org.json.JSONObject

/** One light, plug or appliance as Home Assistant last reported it. */
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
    /** Home Assistant's own state ("on", "run", "2026-11-07T16:20:00+00:00"...). */
    val raw: String = "",
    /** A sensor's unit ("min"), which says how to read [raw]. */
    val unit: String = "",
) {
    val isLight get() = id.startsWith(LIGHT)
    val isAppliance get() = id.startsWith(SENSOR)
    /** Lights and plugs switch; an appliance only reports. */
    val switchable get() = id.startsWith(LIGHT) || id.startsWith(SWITCH)
    val domain get() = id.substringBefore('.')

    companion object {
        const val LIGHT = "light."
        const val SWITCH = "switch."
        const val SENSOR = "sensor."

        /** The entities the row shows: a bulb, a plug or an appliance. */
        fun supported(id: String) = id.startsWith(LIGHT) || id.startsWith(SWITCH) || Appliances.isStateSensor(id)

        /** Lights, then plugs, then appliances, as the row and the Input panel group them. */
        fun order(id: String) = when {
            id.startsWith(LIGHT) -> 0
            id.startsWith(SWITCH) -> 1
            else -> 2
        }
    }
}

/** Home Assistant's REST answers, read into [HassDevice]s. */
object HassJson {
    /** One `/api/states/<id>` object; null for anything the row can't show. */
    fun device(json: JSONObject): HassDevice? = state(json)?.takeIf { HassDevice.supported(it.id) }

    /** Any entity's state, for an appliance's finish sensor too; null without an id. */
    fun state(json: JSONObject): HassDevice? {
        val id = json.optString("entity_id")
        if (id.isEmpty()) return null
        val state = json.optString("state")
        val attributes = json.optJSONObject("attributes") ?: JSONObject()
        val name = attributes.optString("friendly_name").trim().ifEmpty { id.substringAfter('.') }
            .let { if (Appliances.isStateSensor(id)) Appliances.deviceName(id, it) else it }
        val modes = attributes.optJSONArray("supported_color_modes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val dimmable = id.startsWith(HassDevice.LIGHT) && modes.any { it != "onoff" && it.isNotEmpty() }
        // 0 to 255, absent while the light is off.
        val raw = attributes.optInt("brightness", 0).coerceIn(0, 255)
        val running = if (id.startsWith(HassDevice.SENSOR)) Appliances.phase(state) == Appliances.Phase.RUNNING else state == "on"
        return HassDevice(
            id = id,
            name = name,
            on = running,
            available = state != "unavailable" && state != "unknown" && state.isNotEmpty(),
            dimmable = dimmable,
            brightness = if (raw == 0) 0 else ((raw * 100 + 127) / 255).coerceIn(1, 100),
            raw = state,
            unit = attributes.optString("unit_of_measurement"),
        )
    }

    /** The `/api/states` list, lights, plugs and appliances only, by name. */
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

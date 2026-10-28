package cloud.samlo.couch.bravia

import org.json.JSONArray
import org.json.JSONObject

/**
 * The picture and sound settings Couch's quick settings change through [Bravia]: what each is
 * now and what it can be. [read] fetches them all; a setting the TV doesn't offer (or offers
 * greyed out, like auto picture mode on some inputs) is left out.
 */
class TvSettings(private val bravia: Bravia) {
    /** One setting: a list of [choices], or a number from [min] to [max] when choices is empty. */
    class Setting(val target: String, val value: String, val choices: List<String>, val min: Int = 0, val max: Int = 0) {
        fun next(by: Int): String? {
            if (choices.isNotEmpty()) {
                val at = choices.indexOf(value).coerceAtLeast(0)
                return choices[(at + by + choices.size) % choices.size]
            }
            val number = value.toIntOrNull() ?: return null
            return (number + by * STEP).coerceIn(min, max).takeIf { it != number }?.toString()
        }
    }

    /** Everything at once; empty when the TV doesn't answer. Blocking. */
    fun read(): Map<String, Setting> {
        val found = mutableMapOf<String, Setting>()
        runCatching {
            bravia.call("video", "getPictureQualitySettings", JSONArray().put(JSONObject().put("target", "")))
                .optJSONArray(0)?.objects()?.forEach { o ->
                    if (o.optString("target") in PICTURE && o.optBoolean("isAvailable", true)) found[o.optString("target")] = setting(o)
                }
        }
        runCatching {
            bravia.call("audio", "getSoundSettings", JSONArray().put(JSONObject().put("target", "outputTerminal")), "1.1")
                .optJSONArray(0)?.objects()?.firstOrNull()?.let { o ->
                    // The API reports only the current output; these are the two the TV's own menu offers.
                    found[OUTPUT] = Setting(OUTPUT, o.optString("currentValue"), listOf("speaker", "audioSystem"))
                }
        }
        runCatching {
            bravia.call("system", "getPowerSavingMode", JSONArray()).optJSONObject(0)?.let {
                found[POWER_SAVING] = Setting(POWER_SAVING, it.optString("mode"), listOf("off", "low", "high"))
            }
        }
        return found
    }

    /** Changes one setting. Blocking; throws [Bravia.Failed] when the TV refuses (not paired, say). */
    fun write(target: String, value: String) {
        when (target) {
            OUTPUT -> bravia.call("audio", "setSoundSettings", settings(target, value), "1.1")
            POWER_SAVING -> bravia.call("system", "setPowerSavingMode", JSONArray().put(JSONObject().put("mode", value)))
            else -> bravia.call("video", "setPictureQualitySettings", settings(target, value))
        }
    }

    /** Turns the picture off and keeps the sound, as the TV's own "Picture off"; any key brings it back. */
    fun pictureOff() = write(POWER_SAVING, "pictureOff")

    private fun settings(target: String, value: String) =
        JSONArray().put(JSONObject().put("settings", JSONArray().put(JSONObject().put("target", target).put("value", value))))

    private fun setting(o: JSONObject): Setting {
        val candidates = o.optJSONArray("candidate")?.objects().orEmpty()
        val choices = candidates.mapNotNull { c -> c.optString("value").takeIf { it.isNotEmpty() } }
        val range = candidates.firstOrNull { it.has("max") }
        return Setting(o.optString("target"), o.optString("currentValue"), choices, range?.optInt("min") ?: 0, range?.optInt("max") ?: 0)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        const val PICTURE_MODE = "pictureMode"
        const val BRIGHTNESS = "brightness"
        const val LIGHT_SENSOR = "lightSensor"
        const val OUTPUT = "outputTerminal"
        const val POWER_SAVING = "powerSaving"
        private val PICTURE = setOf(PICTURE_MODE, BRIGHTNESS, LIGHT_SENSOR)
        /** Brightness moves by five, like the TV's own quick settings. */
        private const val STEP = 5
    }
}

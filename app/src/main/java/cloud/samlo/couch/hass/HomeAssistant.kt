package cloud.samlo.couch.hass

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home Assistant's REST API, for the Index's row of lights and plugs. Blocking: call from a worker
 * thread. The token lives in Couch's private preferences, never in `config.json`, so a config
 * backup or an ADB pull doesn't carry it.
 */
class HomeAssistant(private val prefs: SharedPreferences) {
    var token: String
        get() = prefs.getString(TOKEN, null).orEmpty()
        set(value) = prefs.edit().putString(TOKEN, value.trim()).apply()

    fun connected(url: String) = url.isNotBlank() && token.isNotBlank()

    /** The name Home Assistant last gave [id], so a list can label it without asking again. */
    fun knownName(id: String): String? = prefs.getString(NAME + id, null)

    /** Keeps [devices]' names for [knownName]; writes only what changed. */
    fun remember(devices: Collection<HassDevice>) {
        val changed = devices.filter { prefs.getString(NAME + it.id, null) != it.name }
        if (changed.isEmpty()) return
        prefs.edit().apply { changed.forEach { putString(NAME + it.id, it.name) } }.apply()
    }

    /** The entity's current state; null when Home Assistant can't be reached or doesn't know it. */
    fun state(url: String, id: String): HassDevice? =
        request("GET", "$url/api/states/$id", token)?.takeIf { it.first == 200 }?.let { (_, body) ->
            runCatching { HassJson.state(JSONObject(body)) }.getOrNull()
        }

    /** The sensor that says when appliance [id]'s cycle ends, if it has one. */
    fun finishSensor(url: String, id: String): String? =
        Appliances.finishCandidates(id).firstOrNull { request("GET", "$url/api/states/$it", token)?.first == 200 }

    /** Every light and plug, for choosing the row's devices; null when unreachable. */
    fun all(url: String): List<HassDevice>? =
        request("GET", "$url/api/states", token)?.takeIf { it.first == 200 }?.let { HassJson.devices(it.second) }

    /**
     * Turns [id] on or off. Never Home Assistant's toggle: it flips what Home Assistant last
     * heard, so a second press sent before the device reported the first would undo the wrong way.
     */
    fun switch(url: String, id: String, on: Boolean): Boolean =
        call(url, id.substringBefore('.'), if (on) "turn_on" else "turn_off", JSONObject().put("entity_id", id))

    /** Sets a light's brightness, which also turns it on. */
    fun brightness(url: String, id: String, percent: Int): Boolean =
        call(url, "light", "turn_on", JSONObject().put("entity_id", id).put("brightness_pct", percent.coerceIn(1, 100)))

    /** True when [url] answers as Home Assistant and accepts [token]. */
    fun check(url: String, token: String): Boolean = request("GET", "$url/api/", token)?.first == 200

    private fun call(url: String, domain: String, service: String, body: JSONObject): Boolean =
        request("POST", "$url/api/services/$domain/$service", token, body.toString())?.first == 200

    private fun request(method: String, url: String, token: String, body: String? = null): Pair<Int, String>? = try {
        // Plain HTTP only on the home network, where the token can't be read on the way.
        if (!LocalNetwork.allowed(url)) throw java.io.IOException("Not sent: plain HTTP off the home network")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            code to text
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TOKEN = "homeassistant:token"
        const val NAME = "homeassistant:name:"
        // On the home network: a slow answer means it is down, and the row should say so soon.
        const val TIMEOUT_MS = 4000
    }
}

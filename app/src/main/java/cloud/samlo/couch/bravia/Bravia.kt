package cloud.samlo.couch.bravia

import android.content.Context
import android.util.Base64
import cloud.samlo.couch.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Sony's own control API for Bravia TVs ("IP control"), reached on the TV itself at 127.0.0.1.
 * Reading needs nothing; changing a setting needs pairing once: [startPairing] makes the TV show
 * a four-digit PIN, [finishPairing] trades it for a sign-in cookie. The cookie lasts two weeks
 * and is renewed without a PIN while it's still valid, so pairing happens once. Calls block: run
 * them on a worker.
 */
class Bravia(context: Context) {
    private val prefs = Prefs.of(context)

    /** Thrown for any answer that isn't a result, so callers can tell "not a Bravia" from "not paired". */
    class Failed(val code: Int, message: String) : IOException(message)

    val paired: Boolean get() = prefs.getString(COOKIE, null) != null

    /** True when this TV answers Sony's API at all. */
    fun available(): Boolean = runCatching { call("system", "getInterfaceInformation", JSONArray()) }.isSuccess

    /** Asks the TV to show a PIN on screen. */
    fun startPairing() {
        try {
            register(null)
        } catch (e: Failed) {
            if (e.code != 401) throw e
        }
    }

    fun finishPairing(pin: String) = register(pin)

    fun forget() = prefs.edit().remove(COOKIE).remove(COOKIE_AT).apply()

    /** One method's result, with the sign-in cookie when there is one. */
    fun call(service: String, method: String, params: JSONArray, version: String = "1.0"): JSONArray {
        renewIfOld()
        val body = JSONObject().put("method", method).put("id", 1).put("params", params).put("version", version)
        val (code, text, _) = post("/sony/$service", body.toString(), prefs.getString(COOKIE, null), null)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: throw Failed(code, "HTTP $code")
        json.optJSONArray("result")?.let { return it }
        val error = json.optJSONArray("error")
        throw Failed(error?.optInt(0) ?: code, error?.optString(1) ?: "HTTP $code")
    }

    private fun renewIfOld() {
        if (!paired || System.currentTimeMillis() - prefs.getLong(COOKIE_AT, 0L) < RENEW_MS) return
        runCatching { register(null) }
    }

    /** Sony's actRegister: with a PIN it pairs, with a valid cookie it hands out a fresh one. */
    private fun register(pin: String?) {
        val client = JSONObject().put("clientid", clientId()).put("nickname", NICKNAME).put("level", "private")
        val params = JSONArray().put(client).put(JSONArray().put(JSONObject().put("function", "WOL").put("value", "yes")))
        val body = JSONObject().put("method", "actRegister").put("id", 1).put("params", params).put("version", "1.0")
        val basic = pin?.let { "Basic " + Base64.encodeToString(":$it".toByteArray(), Base64.NO_WRAP) }
        val cookie = if (pin == null) prefs.getString(COOKIE, null) else null
        val (code, text, fresh) = post("/sony/accessControl", body.toString(), cookie, basic)
        if (code != 200) throw Failed(code, text)
        fresh?.let { prefs.edit().putString(COOKIE, it).putLong(COOKIE_AT, System.currentTimeMillis()).apply() }
    }

    private fun clientId(): String = prefs.getString(CLIENT, null) ?: "couch:${UUID.randomUUID()}".also {
        prefs.edit().putString(CLIENT, it).apply()
    }

    /** The status, the body, and the `auth` cookie the TV set, if it set one. */
    private fun post(path: String, body: String, cookie: String?, authorization: String?): Triple<Int, String, String?> {
        val conn = URL("http://127.0.0.1$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            cookie?.let { conn.setRequestProperty("Cookie", "auth=$it") }
            authorization?.let { conn.setRequestProperty("Authorization", it) }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val fresh = conn.headerFields["Set-Cookie"].orEmpty()
                .firstNotNullOfOrNull { Regex("(?:^|;\\s*)auth=([^;]+)").find(it)?.groupValues?.get(1) }
            return Triple(code, text, fresh)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val NICKNAME = "Couch"
        const val COOKIE = "bravia:cookie"
        const val COOKIE_AT = "bravia:cookieAt"
        const val CLIENT = "bravia:client"
        const val TIMEOUT_MS = 3_000
        /** The cookie lasts 14 days; renewing after 7 leaves a week of TV left off before a PIN is needed again. */
        const val RENEW_MS = 7 * 24 * 3_600_000L
    }
}

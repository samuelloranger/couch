package cloud.samlo.couch.sync

import cloud.samlo.couch.data.writeAtomically
import org.json.JSONObject
import java.io.File

/**
 * The settings paired TVs keep the same. Each one carries when it last changed, here or on another
 * TV, and the newest change wins. A setting nobody changed since this TV started keeping track has
 * no time and never travels, so pairing never overwrites a TV's setup with another's defaults.
 * What belongs to one TV (channels, inputs, the remote's keys, Home Assistant devices, Godmode,
 * sharing itself) is not in [KEYS] and stays put.
 */
class SharedSettings(private val file: File?) {
    private val stamps = HashMap<String, Long>().also { map ->
        runCatching { file?.takeIf { it.isFile }?.readText()?.let(::JSONObject) }.getOrNull()?.let { o ->
            o.keys().forEach { k -> if (k in KEYS) map[k] = o.optLong(k) }
        }
    }

    /** Marks [keys] as changed on this TV [at] that time; false when none of them is shared. */
    @Synchronized fun changed(keys: Set<String>, at: Long): Boolean {
        val shared = keys.filter { it in KEYS }
        if (shared.isEmpty()) return false
        shared.forEach { stamps[it] = at }
        save()
        return true
    }

    /** What this TV sends: each shared setting that has been changed, its value in [config]'s file shape and when. */
    @Synchronized fun outgoing(config: JSONObject): JSONObject {
        val out = JSONObject()
        stamps.forEach { (k, at) -> config.opt(k)?.let { out.put(k, JSONObject().put("v", it).put("at", at)) } }
        return out
    }

    /**
     * The settings in [incoming] that win over this TV's [current] ones, as a patch of those whose
     * value differs, and the times of all that won. Nothing is recorded until [took], so a patch
     * that fails to apply leaves this TV as it was. A time later than [now] counts as [now]: a TV whose
     * clock runs ahead can't keep every later change from winning.
     */
    @Synchronized fun winners(myId: String, theirId: String, incoming: JSONObject, current: JSONObject, now: Long): Pair<JSONObject, Map<String, Long>> {
        val patch = JSONObject()
        val at = HashMap<String, Long>()
        for (k in incoming.keys()) {
            if (k !in KEYS) continue
            val entry = incoming.optJSONObject(k) ?: continue
            val theirs = entry.optLong("at").coerceAtMost(now)
            if (theirs <= 0 || !entry.has("v") || !wins(theirs, theirId, stamps[k] ?: 0L, myId)) continue
            val value = entry.get("v")
            // Already the same here: only its time is taken, so nothing is saved or reloaded.
            if (current.opt(k)?.toString() != value.toString()) patch.put(k, value)
            at[k] = theirs
        }
        return patch to at
    }

    /** Records the times of settings taken from another TV, once they are saved. */
    @Synchronized fun took(at: Map<String, Long>) {
        if (at.isEmpty()) return
        at.forEach { (k, t) -> stamps[k] = maxOf(stamps[k] ?: 0L, t) }
        save()
    }

    private fun save() {
        val target = file ?: return
        runCatching { writeAtomically(target, JSONObject(stamps.toMap()).toString()) }
    }

    companion object {
        /** `config.json` keys every paired TV shares. */
        val KEYS = setOf(
            "accent", "clock24h",
            "wallpaper", "subreddit", "wallpaperHours", "ambientMinutes",
            "weather", "weatherCity", "fahrenheit",
            "pinnedApps", "appSort", "manualAppOrder", "hiddenApps", "names",
            "homeAssistantUrl", "checkUpdates",
        )

        /** Whether a change made at [theirs] replaces one made here at [mine]; the same time goes to the larger id, so both TVs settle on one value. */
        fun wins(theirs: Long, theirId: String, mine: Long, myId: String) = theirs > mine || (theirs == mine && theirId > myId)
    }
}

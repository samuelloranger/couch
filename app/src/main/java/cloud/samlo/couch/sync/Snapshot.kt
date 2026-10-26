package cloud.samlo.couch.sync

import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Usage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DateTimeException
import java.time.LocalDate

/**
 * One TV's last seven days of screen time, as it is handed to another TV. [Usage] counts days back
 * from "today", which means nothing once the snapshot is a day old, so [lastDay] pins the final
 * bar of every app's `perDayMs` to a date.
 */
class Snapshot(
    val deviceId: String,
    val name: String,
    val generatedAt: Long,
    val lastDay: LocalDate,
    val apps: Map<String, AppUsage>,
    /**
     * Every name each package goes by, one per language: a TV set to French shows "Télé Fibe" where
     * another shows "Fibe TV", and apps whose package differs between TVs are matched on these.
     */
    val labels: Map<String, List<String>> = emptyMap(),
) {
    fun toJson(): String {
        val out = JSONObject()
            .put("version", VERSION)
            .put("deviceId", deviceId)
            .put("name", name)
            .put("generatedAt", generatedAt)
            .put("lastDay", lastDay.toString())
        val rows = JSONObject()
        apps.forEach { (pkg, usage) ->
            rows.put(
                pkg,
                JSONObject()
                    .put("perDayMs", JSONArray(usage.perDayMs.toList()))
                    .put("opens", usage.opens)
                    .put("lastUsed", usage.lastUsed)
                    .apply { labels[pkg]?.takeIf { it.isNotEmpty() }?.let { put("labels", JSONArray(it)) } },
            )
        }
        return out.put("apps", rows).toString()
    }

    companion object {
        const val VERSION = 1

        // A peer is trusted with its key, not with its numbers: nothing it sends can be unbounded.
        const val MAX_CHARS = 2_000_000
        const val MAX_APPS = 5_000
        const val MAX_LABELS = 8
        const val MAX_NAME = 80
        const val DAY_MS = 86_400_000L
        const val MAX_OPENS = 1_000_000
        private val EARLIEST = LocalDate.of(2000, 1, 1)
        private val LATEST = LocalDate.of(2200, 1, 1)

        /** Null for anything that isn't a snapshot this version understands, so a peer can't break a read. */
        fun fromJson(text: String): Snapshot? {
            if (text.length > MAX_CHARS) return null
            try {
                val json = JSONObject(text)
                if (json.getInt("version") != VERSION) return null
                val rows = json.getJSONObject("apps")
                if (rows.length() > MAX_APPS) return null
                val apps = HashMap<String, AppUsage>()
                val labels = HashMap<String, List<String>>()
                for (pkg in rows.keys()) {
                    val row = rows.getJSONObject(pkg)
                    val days = row.getJSONArray("perDayMs")
                    if (days.length() != Usage.DAYS) return null
                    val perDay = LongArray(Usage.DAYS) { days.getLong(it).coerceIn(0L, DAY_MS) }
                    apps[pkg] = AppUsage(perDay, row.getInt("opens").coerceIn(0, MAX_OPENS), row.getLong("lastUsed"))
                    row.optJSONArray("labels")?.let { names ->
                        if (names.length() > MAX_LABELS) return null
                        labels[pkg] = (0 until names.length()).map { names.getString(it).take(MAX_NAME) }
                    }
                }
                val lastDay = LocalDate.parse(json.getString("lastDay"))
                if (lastDay < EARLIEST || lastDay > LATEST) return null
                return Snapshot(
                    json.getString("deviceId").take(MAX_NAME),
                    json.getString("name").take(MAX_NAME),
                    json.getLong("generatedAt"),
                    lastDay,
                    apps,
                    labels,
                )
            } catch (e: JSONException) {
                return null
            } catch (e: DateTimeException) {
                return null
            }
        }
    }
}

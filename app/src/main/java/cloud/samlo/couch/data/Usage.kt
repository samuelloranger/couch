package cloud.samlo.couch.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** One app's last seven days: [perDayMs] runs from six days ago to today. */
class AppUsage(val perDayMs: LongArray, val opens: Int, val lastUsed: Long) {
    val weekMs: Long get() = perDayMs.sum()
}

/**
 * Screen time per app, from Android's usage events. Needs usage access, which Android only
 * grants from its settings or over ADB (`appops set cloud.samlo.couch GET_USAGE_STATS allow`).
 *
 * Time counts from an app's activity resuming until it pauses, the screen turns off or the TV
 * shuts down, split at local midnight. An open is an app coming to the front after another one.
 */
object Usage {
    const val DAYS = 7
    /** Loads come in bursts (every return home); one read serves them for a few seconds. */
    private const val FRESH_MS = 15_000L

    @Volatile
    private var cached: Pair<Long, Map<String, AppUsage>>? = null

    fun canRead(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Null without usage access. Call from a background thread. */
    fun lastWeek(context: Context, now: Long = System.currentTimeMillis()): Map<String, AppUsage>? {
        if (!canRead(context)) return null
        cached?.let { (at, map) -> if (now - at < FRESH_MS) return map }
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Midnights from six days ago up to tomorrow: day i spans dayStarts[i] until dayStarts[i + 1].
        val dayStarts = LongArray(DAYS + 1) { today.minusDays((DAYS - 1 - it).toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        val tally = Tally(dayStarts, context.packageName)
        val events = try {
            manager.queryEvents(dayStarts[0], now)
        } catch (e: SecurityException) {
            return null
        } ?: return null
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) tally.add(event.eventType, event.packageName, event.timeStamp)
        val result = tally.finish(now)
        cached = now to result
        return result
    }

    /** Walks the event stream in order; kept apart from Android so it can be tested on its own. */
    internal class Tally(private val dayStarts: LongArray, private val self: String) {
        private val perDay = HashMap<String, LongArray>()
        private val opens = HashMap<String, Int>()
        private val lastUsed = HashMap<String, Long>()
        private var front: String? = null
        private var since = 0L
        /** The last app to come to the front, launcher included, so returning to it isn't an open. */
        private var previous: String? = null

        fun add(type: Int, pkg: String?, at: Long) {
            when (type) {
                RESUMED -> {
                    pkg ?: return
                    if (front != pkg) {
                        front?.let { close(it, at) }
                        front = pkg
                        since = at
                    }
                    if (previous != pkg) opens[pkg] = (opens[pkg] ?: 0) + 1
                    previous = pkg
                    lastUsed[pkg] = at
                }
                PAUSED -> if (pkg != null && pkg == front) {
                    close(pkg, at)
                    front = null
                }
                SCREEN_OFF, SHUTDOWN -> {
                    front?.let { close(it, at) }
                    front = null
                    previous = null
                }
            }
        }

        fun finish(now: Long): Map<String, AppUsage> {
            front?.let { close(it, now) }
            front = null
            return (perDay.keys + opens.keys).filter { it != self }.associateWith {
                AppUsage(perDay[it] ?: LongArray(DAYS), opens[it] ?: 0, lastUsed[it] ?: 0L)
            }
        }

        private fun close(pkg: String, end: Long) {
            lastUsed[pkg] = maxOf(lastUsed[pkg] ?: 0L, end)
            val days = perDay.getOrPut(pkg) { LongArray(DAYS) }
            for (i in 0 until DAYS) {
                val from = maxOf(since, dayStarts[i])
                val to = minOf(end, dayStarts[i + 1])
                if (to > from) days[i] += to - from
            }
        }

        // UsageEvents.Event values; Android 9 sends the same numbers under older names.
        companion object {
            const val RESUMED = 1 // ACTIVITY_RESUMED, MOVE_TO_FOREGROUND before Android 10
            const val PAUSED = 2 // ACTIVITY_PAUSED, MOVE_TO_BACKGROUND before Android 10
            const val SCREEN_OFF = 16 // SCREEN_NON_INTERACTIVE
            const val SHUTDOWN = 26 // DEVICE_SHUTDOWN, Android 10 and later
        }
    }
}

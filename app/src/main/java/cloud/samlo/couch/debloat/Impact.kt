package cloud.samlo.couch.debloat

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import cloud.samlo.couch.data.Usage

/**
 * What removing one app would actually buy on this TV. [alwaysOn] apps are persistent, so Android
 * keeps them running; [memoryKb] is what the app's processes use right now, counting their share
 * of libraries only (a resident figure would count the whole shared system), or null when the
 * shell couldn't be asked; [lastUsed] is the last time it was on screen in the past 30 days.
 * Removal keeps an app's files so it can come back, so it frees memory, never storage.
 */
class Impact(val alwaysOn: Boolean, val atBoot: Boolean, val memoryKb: Int?, val lastUsed: Long?) {
    /** Something to gain: it runs all the time, or uses at least a megabyte now. */
    val worth: Boolean get() = alwaysOn || (memoryKb ?: 0) >= 1024
}

object Impacts {
    private const val RECENT_MS = 30L * 24 * 60 * 60 * 1000

    /** Blocking (package manager and usage stats): call from a worker. */
    fun measure(context: Context, pkgs: Collection<String>, memory: Map<String, Int>?): Map<String, Impact> {
        val pm = context.packageManager
        val atBoot = pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), 0)
            .mapTo(HashSet()) { it.activityInfo.packageName }
        val used = lastUsed(context)
        return pkgs.associateWith { pkg ->
            val flags = try {
                pm.getApplicationInfo(pkg, 0).flags
            } catch (e: PackageManager.NameNotFoundException) {
                0
            }
            Impact(flags and ApplicationInfo.FLAG_PERSISTENT != 0, pkg in atBoot, memory?.let { it[pkg] ?: 0 }, used[pkg])
        }
    }

    /**
     * Each package's memory in KB, from `dumpsys meminfo -s <package>` blocks: a
     * `** MEMINFO in pid 2810 [com.example.app] **` header, then `TOTAL PSS: 4414`. Processes like
     * `com.example.app:service` count toward their app.
     */
    fun parseMemory(dumpsys: String): Map<String, Int> {
        val block = Regex("""\*\* MEMINFO in pid \d+ \[([\w.]+)(?::[\w.]+)?] \*\*[\s\S]*?TOTAL PSS:\s+(\d+)""")
        val out = HashMap<String, Int>()
        block.findAll(dumpsys).forEach { m -> out.merge(m.groupValues[1], m.groupValues[2].toInt(), Int::plus) }
        return out
    }

    /** When each app was last on screen in the past 30 days; empty without usage access. */
    private fun lastUsed(context: Context): Map<String, Long> {
        if (!Usage.canRead(context)) return emptyMap()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val stats = try {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - RECENT_MS, now)
        } catch (e: SecurityException) {
            null
        } ?: return emptyMap()
        val out = HashMap<String, Long>()
        stats.forEach { s ->
            val seen = if (Build.VERSION.SDK_INT >= 29) s.lastTimeVisible else s.lastTimeUsed
            if (seen > now - RECENT_MS) out.merge(s.packageName, seen, ::maxOf)
        }
        return out
    }
}

package cloud.samlo.couch.sync

import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Usage
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Screen time across every TV in the house. This TV's own figures are always the live ones; each
 * peer contributes only its latest [Snapshot], so syncing twice never counts a day twice.
 */
object Merge {
    /**
     * [local] and every snapshot are summed per app: days by date, opens added, last use the latest.
     * A snapshot with no day left in the window is dropped whole, its last use included. A peer's
     * opens aren't dated, so a snapshot that only partly overlaps the window brings all of them.
     */
    fun all(local: Map<String, AppUsage>, today: LocalDate, peers: List<Snapshot>): Map<String, AppUsage> {
        val days = HashMap<String, LongArray>()
        val opens = HashMap<String, Int>()
        val last = HashMap<String, Long>()
        fun add(pkg: String, perDay: LongArray, shift: Int, count: Int, lastUsed: Long) {
            val sum = days.getOrPut(pkg) { LongArray(Usage.DAYS) }
            // A peer's day i is this TV's day i - shift; whatever falls outside the window is gone.
            perDay.forEachIndexed { i, ms -> if (i - shift in 0 until Usage.DAYS) sum[i - shift] += ms }
            opens[pkg] = (opens[pkg] ?: 0) + count
            last[pkg] = maxOf(last[pkg] ?: 0L, lastUsed)
        }
        local.forEach { (pkg, u) -> add(pkg, u.perDayMs, 0, u.opens, u.lastUsed) }
        for (peer in peers) {
            val shift = ChronoUnit.DAYS.between(peer.lastDay, today).toInt()
            if (shift >= Usage.DAYS || -shift >= Usage.DAYS) continue
            peer.apps.forEach { (pkg, u) -> add(pkg, u.perDayMs, shift, u.opens, u.lastUsed) }
        }
        return days.mapValues { (pkg, perDay) -> AppUsage(perDay, opens[pkg] ?: 0, last[pkg] ?: 0L) }
    }
}

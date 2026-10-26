package cloud.samlo.couch.sync

import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Usage
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Screen time across every TV in the house. This TV's own figures are always the live ones; each
 * peer contributes only its latest [Snapshot], so syncing twice never counts a day twice.
 */
object Merge {
    /**
     * [local] and every snapshot are summed per app: days by date, opens added, last use the latest.
     * Apps match by package name first. A peer's app with a package nobody here has falls back to
     * its labels (the same service often ships under different packages per TV brand, and a TV set to
     * another language shows another name, so any one name in common is a match), but never
     * merges into another app of its own TV. A merged app keeps the first package seen, this TV's
     * before any peer's. [localLabels] names this TV's packages the way a snapshot names its own.
     * A snapshot with no day left in the window is dropped whole, its last use included. A peer's
     * opens aren't dated, so a snapshot that only partly overlaps the window brings all of them.
     */
    fun all(
        local: Map<String, AppUsage>,
        localLabels: Map<String, List<String>>,
        today: LocalDate,
        peers: List<Snapshot>,
    ): Map<String, AppUsage> {
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
        // Which package a normalized label belongs to; the first app to claim a label keeps it.
        val byLabel = HashMap<String, String>()
        fun register(pkg: String, labels: List<String>?) {
            labels?.map(::normalize)?.filter { it.isNotEmpty() }?.forEach { byLabel.putIfAbsent(it, pkg) }
        }
        local.forEach { (pkg, u) ->
            add(pkg, u.perDayMs, 0, u.opens, u.lastUsed)
            register(pkg, localLabels[pkg])
        }
        for (peer in peers) {
            val shift = ChronoUnit.DAYS.between(peer.lastDay, today).toInt()
            if (shift >= Usage.DAYS || -shift >= Usage.DAYS) continue
            // Groups this TV has already filled, so two of its apps never merge into each other.
            val filled = HashSet<String>()
            peer.apps.toSortedMap().forEach { (pkg, u) ->
                val byName = pkg.takeIf { it in days }
                val byNames = peer.labels[pkg].orEmpty().asSequence().map(::normalize).mapNotNull(byLabel::get)
                    .firstOrNull { it !in filled }
                val group = byName ?: byNames ?: pkg
                filled += group
                add(group, u.perDayMs, shift, u.opens, u.lastUsed)
                if (group == pkg) register(pkg, peer.labels[pkg])
            }
        }
        return days.mapValues { (pkg, perDay) -> AppUsage(perDay, opens[pkg] ?: 0, last[pkg] ?: 0L) }
    }

    /** Case, accents, spacing and punctuation don't tell two names apart. */
    private fun normalize(label: String) =
        Normalizer.normalize(label, Normalizer.Form.NFD).filter { it.isLetterOrDigit() }.lowercase()
}

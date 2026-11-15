package cloud.samlo.couch.data

/** Ordering policy shared by Index and its remote-controlled Move action. */
object AppOrder {
    val sorts = listOf("usage", "manual", "alphabetical")

    /** [alphabetical] is already sorted using the device's language. Pins always stay first. */
    fun sorted(alphabetical: List<String>, config: Config, usage: Map<String, Long>?): List<String> =
        alphabetical.sortedWith(compareBy<String> { rank(config.pinnedApps, it) }.thenComparator { a, b ->
            when (config.appSort) {
                "manual" -> rank(config.manualAppOrder, a).compareTo(rank(config.manualAppOrder, b))
                "usage" -> (usage?.get(b) ?: 0L).compareTo(usage?.get(a) ?: 0L)
                else -> 0
            }
        })

    fun pin(config: Config, pkg: String) {
        config.pinnedApps = if (pkg in config.pinnedApps) config.pinnedApps - pkg else config.pinnedApps + pkg
    }

    /** Move within favorites or within the other apps; neither group can displace the other. */
    fun move(config: Config, visible: List<String>, pkg: String, position: Int) {
        val pinned = pkg in config.pinnedApps
        val group = visible.filter { (it in config.pinnedApps) == pinned }.distinct().toMutableList()
        if (pkg !in group || position !in group.indices) return
        group.remove(pkg)
        group.add(position, pkg)
        if (pinned) {
            config.pinnedApps = group + config.pinnedApps.filter { it !in group }
        } else {
            config.manualAppOrder = group + config.manualAppOrder.filter { it !in group }
            config.appSort = "manual"
        }
    }

    /**
     * The positions [pkg] can be moved to in [visible]: its own group's, pinned apps among pinned
     * apps and the others among themselves. Empty when [pkg] isn't in the list.
     */
    fun group(visible: List<String>, pinned: List<String>, pkg: String): IntRange {
        val at = visible.indexOf(pkg)
        if (at < 0) return IntRange.EMPTY
        val rowsPinned = pkg in pinned
        fun sameGroup(i: Int) = (visible[i] in pinned) == rowsPinned
        var first = at
        var last = at
        while (first > 0 && sameGroup(first - 1)) first--
        while (last < visible.lastIndex && sameGroup(last + 1)) last++
        return first..last
    }

    private fun rank(order: List<String>, pkg: String) = order.indexOf(pkg).let { if (it < 0) Int.MAX_VALUE else it }
}

package cloud.samlo.couch.data

/** Row ids and the user's order for them, shared by the home screen and settings. */
object Sections {
    const val APPS = "apps"
    const val INPUTS = "inputs"
    const val RECENT = "recent"

    /** Apps in the Recent row, at most. */
    const val RECENT_COUNT = 8

    /**
     * [ids] in the user's order; rows the user never placed keep their default order, at the end.
     * Recent is the exception: until placed, it follows Apps, so it appears where it's useful.
     */
    fun order(ids: List<String>, config: Config): List<String> {
        val ordered = (config.sectionOrder.filter { it in ids } + ids.filter { it !in config.sectionOrder }).toMutableList()
        if (RECENT in ordered && RECENT !in config.sectionOrder && APPS in ordered) {
            ordered.remove(RECENT)
            ordered.add(ordered.indexOf(APPS) + 1, RECENT)
        }
        return ordered
    }

    /** The [keys] last used in the past week, most recent first; apps never opened are left out. */
    fun recent(keys: List<String>, usage: Map<String, AppUsage>?, limit: Int = RECENT_COUNT): List<String> {
        if (usage == null) return emptyList()
        return keys.filter { (usage[it]?.lastUsed ?: 0L) > 0L }
            .sortedByDescending { usage.getValue(it).lastUsed }
            .take(limit)
    }

    fun channelId(id: Long) = "ch:$id"
}

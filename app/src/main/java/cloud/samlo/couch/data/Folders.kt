package cloud.samlo.couch.data

import kotlin.random.Random

/** A named group of apps, shown as one row in the Index and opened as a grid of its apps. */
class Folder(val id: String, val name: String, val apps: List<String>)

/**
 * Folders in the list's app order: a folder takes the place its rank earns it, and its apps no
 * longer show on their own. [arrange] and [drop] stay apart from the views so their rules can be
 * tested on a plain JVM, as [AppOrder] is.
 */
object Folders {
    /** One row of the arranged list: an app alone, or a folder standing for [Group.members]. */
    sealed class Slot {
        data class App(val key: String) : Slot()
        data class Group(val id: String, val name: String, val members: List<String>) : Slot()
    }

    /** One row of the list as a manual move sees it: an app, a folder with the apps it stands for, or Couch's own. */
    sealed class Row {
        data class App(val key: String) : Row()
        data class Group(val apps: List<String>) : Row()
        /** Continue watching: a row the saved order leaves out. */
        object Own : Row()
    }

    /** What a drop saves: the visible order, and where the moved app sits in its own group. */
    class Drop(val order: List<String>, val position: Int)

    /**
     * [apps] under [config]'s sort, with each folder replaced by one [Slot.Group]. The usage sort
     * weighs a folder as one candidate, timed as its members together, so it lands where that much
     * use puts it rather than where its best-used app sat; a pinned app keeps its rank, and a
     * folder holding one ranks with the pinned ones. Manual and alphabetical sorts keep a folder
     * at its highest-ranked app's place. [visible] is what the TV still shows: a hidden or
     * uninstalled app leaves its folder, and a folder left with none is skipped. An app keeps the
     * first folder that lists it, so two folders never show the same app.
     */
    fun arrange(apps: List<String>, config: Config, usage: Map<String, Long>?, visible: Set<String>): List<Slot> {
        val owner = HashMap<String, Folder>()
        for (folder in config.folders) for (app in folder.apps) owner.putIfAbsent(app, folder)
        val ordered = AppOrder.sorted(apps, config, usage)
        // The members keep the list's own order, so the grid reads like the rows it replaces.
        val members = config.folders.associateWith { ArrayList<String>() }
        for (key in ordered) if (key in visible) owner[key]?.let { members.getValue(it) += key }
        return if (config.appSort == "usage") byUsage(ordered, config, usage, owner, members, visible)
        else place(ordered, owner, members, visible)
    }

    /**
     * What a manual drop saves: [rows] as the list reads after the move, each folder row replaced in
     * place by the apps it stands for. A folder's apps keep their places that way, instead of
     * falling behind the apps the list was showing. [position] is [key]'s place among its own group,
     * as [AppOrder.move] counts it; null when [key] is no app of [rows].
     */
    fun drop(rows: List<Row>, pinned: List<String>, key: String): Drop? {
        val order = ArrayList<String>(rows.size)
        for (row in rows) when (row) {
            is Row.Own -> Unit
            is Row.App -> order += row.key
            is Row.Group -> order += row.apps
        }
        val at = order.indexOf(key)
        val group = AppOrder.group(order, pinned, key)
        if (at < 0 || group.isEmpty()) return null
        return Drop(order, at - group.first)
    }

    /** The usage sort's rows: one per app outside a folder and one per folder, ranked and sorted together. */
    private fun byUsage(
        ordered: List<String>,
        config: Config,
        usage: Map<String, Long>?,
        owner: Map<String, Folder>,
        members: Map<Folder, List<String>>,
        visible: Set<String>,
    ): List<Slot> {
        val candidates = ArrayList<Slot>(ordered.size)
        val placed = HashSet<Folder>()
        for (key in ordered) {
            if (key !in visible) continue
            val folder = owner[key]
            when {
                folder == null -> candidates += Slot.App(key)
                // One candidate per folder, taken at its first member's place in the list's order.
                folder in placed -> Unit
                else -> {
                    placed += folder
                    candidates += Slot.Group(folder.id, folder.name, members.getValue(folder))
                }
            }
        }
        // Pinned rows keep their own order; the rest fall by their week, a folder's its members' added up.
        return candidates.sortedWith(
            compareBy<Slot> { pinRank(config.pinnedApps, it) }
                .thenComparator { a, b -> time(usage, b).compareTo(time(usage, a)) },
        )
    }

    /** The rows [ordered] makes, each folder at the place of its highest-ranked app. */
    private fun place(
        ordered: List<String>,
        owner: Map<String, Folder>,
        members: Map<Folder, List<String>>,
        visible: Set<String>,
    ): List<Slot> {
        val out = ArrayList<Slot>(ordered.size)
        val placed = HashSet<Folder>()
        for (key in ordered) {
            if (key !in visible) continue
            when (val folder = owner[key]) {
                null -> out += Slot.App(key)
                // The group lands on the first of its apps in the order; the rest add nothing.
                else -> if (placed.add(folder)) out += Slot.Group(folder.id, folder.name, members.getValue(folder))
            }
        }
        return out
    }

    /** A row's place among the pinned apps: a folder takes its best-pinned member's. */
    private fun pinRank(pinned: List<String>, slot: Slot): Int = when (slot) {
        is Slot.App -> rank(pinned, slot.key)
        is Slot.Group -> slot.members.minOfOrNull { rank(pinned, it) } ?: Int.MAX_VALUE
    }

    /** A row's week: a folder's is its members' added up, as its row shows it. */
    private fun time(usage: Map<String, Long>?, slot: Slot): Long = when (slot) {
        is Slot.App -> usage?.get(slot.key) ?: 0L
        is Slot.Group -> slot.members.sumOf { usage?.get(it) ?: 0L }
    }

    private fun rank(order: List<String>, key: String) = order.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it }

    /** The apps' week added up: a folder sorts and reads as one app made of its own. */
    fun usage(of: List<AppUsage>): AppUsage {
        val perDay = LongArray(Usage.DAYS)
        for (usage in of) usage.perDayMs.forEachIndexed { i, ms -> perDay[i] += ms }
        return AppUsage(perDay, of.sumOf { it.opens }, of.maxOfOrNull { it.lastUsed } ?: 0L)
    }

    /** One folder per id and each app in one folder, for a file edited by hand. */
    fun clean(folders: List<Folder>): List<Folder> {
        val ids = HashSet<String>()
        val apps = HashSet<String>()
        return folders.mapNotNull { folder ->
            if (folder.id.isEmpty() || !ids.add(folder.id)) return@mapNotNull null
            Folder(folder.id, folder.name, folder.apps.filter { it.isNotEmpty() && apps.add(it) })
        }
    }

    /** A short random id, in hex, that [taken] doesn't already use. */
    fun newId(taken: Collection<String> = emptyList()): String {
        while (true) {
            val id = "%08x".format(Random.nextInt())
            if (id !in taken) return id
        }
    }
}

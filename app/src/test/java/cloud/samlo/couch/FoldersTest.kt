package cloud.samlo.couch

import cloud.samlo.couch.data.AppOrder
import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Folder
import cloud.samlo.couch.data.Folders
import cloud.samlo.couch.data.Folders.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersTest {
    private val kids = Folder("1a2b3c4d", "Kids", listOf("y", "p"))

    @Test
    fun aFolderTakesThePlaceOfItsHighestRankedApp() {
        val slots = arrange(listOf("a", "y", "b", "p"), listOf(kids), setOf("a", "y", "b", "p"))
        assertEquals(
            listOf(Slot.App("a"), Slot.Group(kids.id, "Kids", listOf("y", "p")), Slot.App("b")),
            slots,
        )
    }

    @Test
    fun hiddenAndUninstalledAppsLeaveTheirFolder() {
        // p was uninstalled, so only y is left of the folder; the folder keeps y's place.
        val slots = arrange(listOf("a", "y", "b"), listOf(kids), setOf("a", "y", "b"))
        assertEquals(listOf(Slot.App("a"), Slot.Group(kids.id, "Kids", listOf("y")), Slot.App("b")), slots)
    }

    @Test
    fun theListHidesAFolderItCanSeeNothingIn() {
        // Every member hidden: the folder stays in the config but has no row.
        val slots = arrange(listOf("a", "b"), listOf(kids), setOf("a", "b"))
        assertEquals(listOf(Slot.App("a"), Slot.App("b")), slots)
    }

    @Test
    fun anAppShownByTwoFoldersStaysWithTheFirst() {
        val music = Folder("9f8e7d6c", "Music", listOf("p", "s"))
        val slots = arrange(listOf("p", "a", "s"), listOf(kids, music), setOf("a", "y", "p", "s"))
        assertEquals(listOf(Slot.Group(kids.id, "Kids", listOf("p")), Slot.App("a"), Slot.Group(music.id, "Music", listOf("s"))), slots)
    }

    @Test
    fun anAppListedTwiceInAFolderShowsOnce() {
        val twice = Folder("abc", "Twice", listOf("a", "a", "b"))
        val slots = arrange(listOf("a", "b"), listOf(twice), setOf("a", "b"))
        assertEquals(listOf(Slot.Group(twice.id, "Twice", listOf("a", "b"))), slots)
    }

    @Test
    fun theUsageSortPlacesAFolderWhereItsAppsTimeTogetherPutsIt() {
        // Alone, y and p would sit at 25 and 15 minutes; together they rank above b's 30.
        val usage = mapOf("a" to 10L, "y" to 25L, "p" to 15L, "b" to 30L)
        val slots = arrange(listOf("a", "b", "p", "y"), listOf(kids), setOf("a", "b", "p", "y"), "usage", usage)
        assertEquals(listOf(Slot.Group(kids.id, "Kids", listOf("y", "p")), Slot.App("b"), Slot.App("a")), slots)
    }

    @Test
    fun aFolderHoldingAPinnedAppSortsWithThePinned() {
        // a is used far more, but a pin holds the folder ahead of it, as it held its app.
        val usage = mapOf("a" to 500L, "y" to 1L, "p" to 1L)
        val slots = arrange(listOf("a", "p", "y"), listOf(kids), setOf("a", "p", "y"), "usage", usage, pinned = listOf("y"))
        assertEquals(listOf(Slot.Group(kids.id, "Kids", listOf("y", "p")), Slot.App("a")), slots)
    }

    @Test
    fun manualAndAlphabeticalSortsKeepAFolderAtItsAppsPlace() {
        val usage = mapOf("a" to 500L, "y" to 1L, "p" to 1L)
        // Alphabetical: a, p, y. The folder keeps p's place, and neither sort reads the week.
        assertEquals(
            listOf(Slot.App("a"), Slot.Group(kids.id, "Kids", listOf("p", "y"))),
            arrange(listOf("a", "p", "y"), listOf(kids), setOf("a", "p", "y"), "alphabetical", usage),
        )
        val config = Config().apply {
            folders = listOf(kids)
            appSort = "manual"
            manualAppOrder = listOf("y", "p", "a")
        }
        assertEquals(
            listOf(Slot.Group(kids.id, "Kids", listOf("y", "p")), Slot.App("a")),
            Folders.arrange(listOf("a", "p", "y"), config, usage, setOf("a", "p", "y")),
        )
    }

    @Test
    fun aDropSavesAFoldersAppsInItsPlace() {
        // A folder above the moved app and one below it keep their apps where the list reads them.
        val rows = listOf(
            Folders.Row.Group(listOf("y", "p")),
            Folders.Row.App("a"),
            Folders.Row.Own,
            Folders.Row.Group(listOf("m", "n")),
            Folders.Row.App("b"),
        )
        val saved = Folders.drop(rows, pinned = emptyList(), key = "a")!!
        assertEquals(listOf("y", "p", "a", "m", "n", "b"), saved.order)
        assertEquals(2, saved.position)
        // Continue watching and a folder's own key are no apps: a row that isn't one saves nothing.
        assertNull(Folders.drop(rows, emptyList(), "folder:kids"))
    }

    @Test
    fun aDropCountsThePlaceInItsOwnGroup() {
        val rows = listOf(
            Folders.Row.App("pin1"),
            Folders.Row.App("pin2"),
            Folders.Row.Group(listOf("y", "p")),
            Folders.Row.App("a"),
            Folders.Row.App("b"),
        )
        val pinned = listOf("pin1", "pin2")
        val unpinned = Folders.drop(rows, pinned, "a")!!
        assertEquals(listOf("pin1", "pin2", "y", "p", "a", "b"), unpinned.order)
        // The unpinned group starts under the two pinned rows: a sits third among its own.
        assertEquals(2, unpinned.position)
        assertEquals(0, Folders.drop(rows, pinned, "pin1")!!.position)
    }

    @Test
    fun aSavedDropNeverSendsAFoldersAppsToTheEnd() {
        val config = Config()
        // Games holds y and p: dropping a below it must keep them above a, not push them behind it.
        val rows = listOf(Folders.Row.Group(listOf("y", "p")), Folders.Row.Own, Folders.Row.App("a"), Folders.Row.App("b"))
        val saved = Folders.drop(rows, pinned = emptyList(), key = "a")!!
        AppOrder.move(config, saved.order, "a", saved.position)
        assertEquals(listOf("y", "p", "a", "b"), config.manualAppOrder)
        assertEquals(listOf("y", "p", "a", "b"), AppOrder.sorted(listOf("a", "b", "p", "y"), config, null))
    }

    @Test
    fun aFoldersScreenTimeIsItsAppsAddedUp() {
        val first = AppUsage(longArrayOf(0, 0, 0, 0, 0, 10, 20), opens = 2, lastUsed = 100)
        val second = AppUsage(longArrayOf(0, 0, 0, 0, 0, 5, 1), opens = 1, lastUsed = 300)
        val all = Folders.usage(listOf(first, second))
        assertEquals(longArrayOf(0, 0, 0, 0, 0, 15, 21).toList(), all.perDayMs.toList())
        assertEquals(3, all.opens)
        assertEquals(300L, all.lastUsed)
        assertEquals(36L, all.weekMs)
    }

    @Test
    fun aFileEditedByHandKeepsOneFolderPerIdAndOneFolderPerApp() {
        val clean = Folders.clean(
            listOf(
                Folder("1", "Kids", listOf("a", "b")),
                Folder("1", "Copy", listOf("c")),
                Folder("2", "Music", listOf("b", "c", "", "c")),
            ),
        )
        assertEquals(listOf("1", "2"), clean.map { it.id })
        assertEquals(listOf("a", "b"), clean[0].apps)
        assertEquals(listOf("c"), clean[1].apps)
    }

    @Test
    fun newIdsAreShortHexAndUnused() {
        val id = Folders.newId()
        assertEquals(8, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]{8}")))
        assertTrue(Folders.newId(listOf(id)) != id)
    }

    /** The list's rows with [folders] in it, under a sort that leaves [ordered] alone unless it says otherwise. */
    private fun arrange(
        ordered: List<String>,
        folders: List<Folder>,
        visible: Set<String>,
        sort: String = "alphabetical",
        usage: Map<String, Long>? = null,
        pinned: List<String> = emptyList(),
    ): List<Slot> = Folders.arrange(
        ordered,
        Config().apply {
            this.folders = folders
            appSort = sort
            pinnedApps = pinned
        },
        usage,
        visible,
    )
}

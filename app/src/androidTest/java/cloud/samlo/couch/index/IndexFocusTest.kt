package cloud.samlo.couch.index

import android.view.KeyEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.rules.ActivityScenarioRule
import cloud.samlo.couch.ui.FocusRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Index's D-pad map, over an [IndexScreen] bound to crafted data (see
 * [IndexTestActivity.defaultData]: apps a and b, a folder, then c to e, app d carrying two
 * channels). The arrows go through the framework's focus search, so these tests cover the
 * [IndexScreen.focusSearch] routing exactly as a remote's presses reach it.
 */
@RunWith(AndroidJUnit4::class)
class IndexFocusTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(IndexTestActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: IndexTestActivity
    private lateinit var index: IndexScreen

    @Before
    fun setUp() {
        activityRule.scenario.onActivity { a ->
            activity = a
            index = a.index
        }
        instrumentation.waitForIdleSync()
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private fun focused(): View = activity.currentFocus ?: error("nothing has focus")

    private fun focusedRow(): AppRow = focused() as AppRow

    /** The index's direct child of [type]: the screen is a flat FrameLayout. */
    private fun <T : View> child(type: Class<T>): T =
        (0 until index.childCount).asSequence().map { index.getChildAt(it) }
            .filterIsInstance(type).firstOrNull() ?: error("$type not on the screen")

    private fun rail() = child(IndexRail::class.java)
    private fun list() = child(AppList::class.java)
    private fun search() = child(SearchPanel::class.java)
    private fun spotlight() = child(Spotlight::class.java)
    private fun strip() = child(FocusRow::class.java)
    private fun grid() = child(FolderGrid::class.java)

    /** The row the list marks selected, or null before a selection exists. */
    private fun selected(): AppRow? = list().rows().firstOrNull { it.chosen }

    @Test
    fun bindPlacesFocusOnTheFirstApp() {
        assertSame(list().rows().first(), focused())
        assertEquals("a", focusedRow().entry.key)
        assertSame(list().rows().first(), selected())
    }

    @Test
    fun downWalksTheListAndUpComesBack() {
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("b", focusedRow().entry.key)
        assertSame(focusedRow(), selected())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        // The folder row is a row like any other for up and down.
        assertTrue(focusedRow().entry.isFolder)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("b", focusedRow().entry.key)
    }

    @Test
    fun upFromTheFirstAppStaysPut() {
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("a", focusedRow().entry.key)
    }

    @Test
    fun leftReachesTheRailAndRightReturnsToTheSelectedApp() {
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        // Without inputs the rail offers Search and Settings; which one depends on the row's place.
        assertTrue(focused() === rail().search || focused() === rail().settings)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("b", focusedRow().entry.key)
    }

    @Test
    fun rightEntersTheChannelRowAndLeftReturnsToTheList() {
        // App a has one channel: Right skips the channel picker and lands on its first card.
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val card = focused() as cloud.samlo.couch.ui.Card
        assertSame(strip().cards().first(), card)
        assertEquals("a-1-1", card.tile.key)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("a", focusedRow().entry.key)
    }

    @Test
    fun leftBetweenCardsMovesWithinTheChannelRow() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val second = focused() as cloud.samlo.couch.ui.Card
        assertSame(strip().cards()[1], second)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertSame(strip().cards().first(), focused())
    }

    @Test
    fun rightFromAMultiChannelAppReachesTheFirstCardAndUpTheChannelButton() {
        // Walk to app d: a, b, the folder, c, d.
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("d", focusedRow().entry.key)
        // Right goes to the cards; the channel picker is one Up away, and Down returns to the cards.
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertSame(strip().cards().first(), focused())
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertSame(spotlight().channelButton, focused())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSame(strip().cards().first(), focused())
        onMain { activity.consumesKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP) }
        assertEquals("d", focusedRow().entry.key)
    }

    @Test
    fun upFromTheCardsWithNoChannelButtonStaysOnTheCard() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val card = focused()
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertSame(card, focused())
    }

    @Test
    fun downFromTheCardsStopsAtTheUsageRequestAndNothingBeyondItIsReached() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        // The test data has no usage access: the request is the next stop, then nothing (no dock).
        assertSame(child(StatsBar::class.java).allow, focused())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSame(child(StatsBar::class.java).allow, focused())
    }

    @Test
    fun rightFromTheListReturnsToTheCardTheRowStoodOn() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val second = strip().cards()[1]
        assertSame(second, focused())
        // Down to the usage request and back up: the card the row stood on, not the first.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertSame(second, focused())
        // Back to the list and Right again: the same card.
        onMain { activity.consumesKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP) }
        assertEquals("a", focusedRow().entry.key)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertSame(second, focused())
    }

    @Test
    fun rightIntoAFolderOpensItsTilesAndLeftComesBack() {
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(focusedRow().entry.isFolder)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val tile = focused() as cloud.samlo.couch.ui.Card
        assertSame(grid().first(), tile)
        assertEquals("x", tile.tile.key)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(focusedRow().entry.isFolder)
    }

    @Test
    fun searchKeyOpensThePanelAndBackClosesIt() {
        onMain { assertTrue(activity.consumesKey(KeyEvent.KEYCODE_SEARCH, KeyEvent.ACTION_DOWN)) }
        assertEquals(View.VISIBLE, search().visibility)
        // Back out of the panel: the list comes back with focus on the app that had it.
        onMain { assertTrue(activity.consumesKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)) }
        assertEquals(View.GONE, search().visibility)
        assertEquals(View.VISIBLE, list().visibility)
        assertEquals("a", focusedRow().entry.key)
    }

    @Test
    fun backFromAResultsCardsStepsToTheResultAndKeepsSearchOpen() {
        onMain { assertTrue(activity.consumesKey(KeyEvent.KEYCODE_SEARCH, KeyEvent.ACTION_DOWN)) }
        // Down the panel to app a's result, then Right into its program cards.
        repeat(4) { if (search().entryOf(focused())?.key != "a") key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("a", search().entryOf(focused())?.key)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(focused() is cloud.samlo.couch.ui.Card)
        // Back returns to the result; the panel and its query stay.
        onMain { assertTrue(activity.consumesKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)) }
        onMain { assertTrue(activity.consumesKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)) }
        assertEquals(View.VISIBLE, search().visibility)
        assertEquals("a", search().entryOf(focused())?.key)
    }

    @Test
    fun liftedRowMovesWithTheKeysAndDropsOnOk() {
        // The lift owns the remote: its keys reach the screen through consumesKey, as the
        // activity's dispatch would route them, so the arrows no longer walk the list.
        onMain { index.startMove("c") }
        onMain { activity.consumesKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN) }
        onMain { activity.consumesKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN) }
        // Back puts the lifted row back without saving anything.
        onMain { activity.consumesKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN) }
        assertTrue(activity.moves.isEmpty())
        // Lift again, move one down, and drop: the saved move reports c in its new place.
        onMain { index.startMove("c") }
        onMain { activity.consumesKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN) }
        onMain { activity.consumesKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP) }
        assertEquals(listOf("c"), activity.moves.map { it.second })
        // The saved place counts every movable app in the list's order, the folder's members included.
        assertEquals(5, activity.moves.single().third)
    }

    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync { block() }
        instrumentation.waitForIdleSync()
    }
}

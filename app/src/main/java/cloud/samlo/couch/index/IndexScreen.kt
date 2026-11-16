package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.app.Activity
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import cloud.samlo.couch.R
import cloud.samlo.couch.data.AppLabels
import cloud.samlo.couch.data.AppOrder
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Folders
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.hass.HomeAssistant
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.FocusRow
import cloud.samlo.couch.ui.NowPlaying
import cloud.samlo.couch.ui.Popover
import cloud.samlo.couch.ui.Side
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * The Index layout: a rail of Search, Home, Inputs and Settings down the left edge, the apps
 * listed beside it; on the right, what the selected app offers (the focused program's text over
 * its chosen channel's cards), its last week of use, and the TV's readings along the bottom.
 * What's playing sits in a dock under the week. A folder row opens its apps there instead, as a
 * grid of app cards in place of the channel row.
 *
 * Search takes the app list's place while it is open: the field, then voice, the TV's own search,
 * the matching apps and folders and Continue watching titles, with the rail's bar on Search.
 *
 * D-pad: up/down walks the list, right enters the app's channel row (or the folder's grid), left
 * (from its first card), down comes back; while something plays, down from the cards reaches the
 * dock's buttons and up or back returns. With several channels, right reaches the channel picker
 * first and up from the cards returns to it. Left from the list (or the Home Assistant row)
 * reaches the rail, right from the rail returns to the selected app. Up from the first app reaches
 * the Home Assistant row, or else the chips at the top right.
 *
 * A manual move lifts the row itself: up and down take it through its group, OK drops it there and
 * Back puts it back, with a hint beside it instead of a dialog in front of it.
 */
@SuppressLint("ViewConstructor")
class IndexScreen(
    private val activity: Activity,
    private val images: Images,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
    private val onAppMenu: (Tile, View) -> Unit,
    /** A folder row is long-pressed: its name and its id, for the folder's own menu. */
    private val onFolderMenu: (IndexEntry, View) -> Unit,
    private val onProgramMenu: (Card) -> Unit,
    private val onChannelSelected: (String, Long) -> Unit,
    /**
     * Saves a dropped row: every visible key in order, the app that moved and its new place among
     * its own group.
     */
    private val onMoved: (List<String>, String, Int) -> Unit,
) : FrameLayout(activity) {
    val topBar = IndexTopBar(activity, onUpdate)
    private val rail = IndexRail(activity, { openSearch() }, { pickInput() }, onSettings, { closeSearch() })
    private val list = AppList(activity) { entry -> row(entry) }
    private val search = SearchPanel(activity, images, { from -> toRail(from)?.requestFocus() }, { entry, program -> preview(entry, program) }, { openFolder(it) })
    private val spotlight = Spotlight(activity)
    private val programs = FocusRow(activity).apply {
        gapDp = IndexTokens.CARD_GAP
        leavesAtStart = true
        setStartPadding(activity.dp(STRIP_START) - activity.dp(Tokens.RING_INSET))
    }
    /** The selected folder's apps, in the channel row's place. */
    private val folderGrid = FolderGrid(activity) { tile -> appCard(tile) }
    /** The app's banner, in the channel row's place when the app has no channel. */
    private val banner = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val stats = StatsBar(activity)
    /** What's playing, under the week: hidden when nothing does. */
    val nowPlaying = NowPlaying(activity)
    val systemLine = SystemLine(activity)
    /** Lights and plugs from Home Assistant, top of the right column; gone until some are chosen. */
    private val homeRow = HomeRow(activity, HomeAssistant(Prefs.of(activity)))
    /** Whether the Home Assistant row is showing. */
    private var homeLayout = false
    /** Whether Search has the app list's place. */
    private var searchOpen = false
    /** The app selected before Search opened: focus goes back to it when the panel closes. */
    private var searchReturn: String? = null

    private var data: IndexData? = null
    private var config = Config()
    private var look = ""
    private var selectedKey: String? = null
    /** The app and channel whose programs the row holds. */
    private var stripKey: String? = null
    /** The rail's Inputs menu while it is open. */
    private var inputMenu: InputMenu? = null
    private var channelMenu: Popover? = null
    /** The app lifted out of the list for a manual move; null when the list is only a list. */
    private var moving: String? = null
    /** Where the lifted row started, and how many places down it has been moved. */
    private var moveFrom = 0
    private var moveBy = 0
    /** The lifted row and the hint beside it, which follows the row. */
    private var moveRow: AppRow? = null
    private var moveHint: Popover? = null
    /** The hint is re-placed once the rows settle: a glide moves its anchor after the call. */
    private val followHint = Runnable { moveRow?.let { row -> moveHint?.follow(row) } }
    /** False until the first bind has painted; what appears after it fades in. */
    private var settled = false
    /** Placed once, on the first bind; later binds keep focus where it is. */
    private var placedFocus = false
    /** Set while [bind] moves focus itself, so the focus listener doesn't fill the column twice. */
    private var binding = false
    /** The channel row fills once the selection rests, so holding down never builds cards and art. */
    private val fillStrip = Runnable { selectedRow()?.let { fill(it.entry) } }
    /** The figures follow a frame after the press, so the press's own frame stays light. */
    private val statsLater = Runnable { selectedRow()?.let { showStats(it.entry) } }
    /** Cards are added a couple per frame: building them all at once cost a weak TV two frames. */
    private var built = 0
    private val buildMore = Runnable { selectedRow()?.entry?.takeIf { it.stripKey == stripKey }?.let { buildStep(it) } }

    init {
        // Nothing shows until the first bind: a half-built screen with a stray focus ring reads as broken.
        visibility = INVISIBLE
        alpha = 0f
        spotlight.channelButton.setOnClickListener { chooseChannel() }
        spotlight.channelButton.setOnFocusChangeListener { _, has ->
            if (has) selectedRow()?.let {
                list.select(it, inRow = true)
                showProgram(it.entry, null)
            }
        }
        // The window paints the background (HomeViews swaps it to Index's flat colour), and no
        // layer here covers the screen: each full-screen layer costs the TV's GPU.
        place(rail, 0, 0, IndexTokens.RAIL_W, LayoutParams.MATCH_PARENT)
        // Focus in the rail quietens the list's selection, as focus in the channel row does.
        listOf(rail.search, rail.inputs, rail.settings).forEach { item ->
            item.setOnFocusChangeListener { _, has -> if (has) selectedRow()?.let { list.select(it, inRow = true) } }
        }
        place(list, IndexTokens.LIST_X, IndexTokens.LIST_Y, IndexTokens.LIST_W, IndexTokens.LIST_H)
        place(search, IndexTokens.LIST_X, IndexTokens.LIST_Y, IndexTokens.LIST_W, IndexTokens.LIST_H)
        search.visibility = GONE
        place(spotlight, IndexTokens.COLUMN_X, IndexTokens.HEADER_Y, IndexTokens.COLUMN_W, LayoutParams.WRAP_CONTENT)
        // The row starts a little left of the column so a scaled card's ring isn't clipped.
        val cardTop = 10 + Tokens.RING_INSET
        addView(programs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = activity.dp(IndexTokens.COLUMN_X - STRIP_START)
            topMargin = activity.dp(IndexTokens.STRIP_Y - cardTop)
        })
        place(banner, IndexTokens.COLUMN_X, IndexTokens.STRIP_Y, IndexTokens.cardWidth(-1), IndexTokens.CARD_H)
        // Placed like a rule of cards: its tiles start where the channel row's first card does.
        place(
            folderGrid, IndexTokens.COLUMN_X - Tokens.RING_INSET, IndexTokens.STRIP_Y - Tokens.RING_INSET,
            FolderGrid.W, FolderGrid.H,
        )
        folderGrid.visibility = GONE
        place(stats, IndexTokens.COLUMN_X, IndexTokens.STATS_Y, IndexTokens.COLUMN_W, LayoutParams.WRAP_CONTENT)
        place(nowPlaying, IndexTokens.COLUMN_X, IndexTokens.DOCK_Y, IndexTokens.COLUMN_W, IndexTokens.DOCK_H)
        // Nothing playing takes the dock away: focus comes back to the app the column describes.
        nowPlaying.onHide = { returnTarget()?.requestFocus() }
        // From the list's edge, not the column's: a full address and longer labels outgrow the column.
        addView(systemLine, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = activity.dp(IndexTokens.LIST_X)
            topMargin = activity.dp(IndexTokens.FOOTER_Y) - systemLine.inset
        })
        addView(topBar, LayoutParams(LayoutParams.WRAP_CONTENT, activity.dp(IndexTokens.TOP_H), Gravity.TOP or Gravity.END).apply {
            topMargin = activity.dp(IndexTokens.TOP_Y)
            rightMargin = activity.dp(IndexTokens.RIGHT_MARGIN)
        })
        // The row carries a ring-sized margin of its own, so it sits that much up and left.
        place(
            homeRow, IndexTokens.COLUMN_X - Tokens.RING_INSET, IndexTokens.HOME_ROW_Y - Tokens.RING_INSET,
            LayoutParams.WRAP_CONTENT, IndexTokens.HOME_ROW_H + 2 * Tokens.RING_INSET,
        )
    }

    /**
     * The Home Assistant row takes what the top bar leaves, so the two never overlap. With many
     * devices and chips showing it starts over the list instead of the right column.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (homeRow.visibility == VISIBLE) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            topBar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val limit = width - activity.dp(IndexTokens.RIGHT_MARGIN + HOME_ROW_GAP) - topBar.measuredWidth
            val ring = activity.dp(Tokens.RING_INSET)
            val left = HomeRowPlacement.startX(homeRow.naturalWidth(), activity.dp(IndexTokens.COLUMN_X) - ring, activity.dp(IndexTokens.LIST_X) - ring, limit)
            (homeRow.layoutParams as LayoutParams).leftMargin = left
            homeRow.fit(limit - left)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun bind(data: IndexData, config: Config) {
        // A reload can reorder the list under a lift: the move ends, keeping the saved order.
        endMove(focus = false)
        this.data = data
        this.config = config
        val animate = settled
        topBar.animate = animate
        homeRow.bind(config, animate)
        homeLayout = homeRow.visibility == VISIBLE
        // The inputs went away (unplugged, hidden): their item and menu go with them, so focus moves on.
        if (data.inputs.isEmpty()) {
            inputMenu?.dismiss()
            if (rail.inputs.hasFocus()) rail.settings.requestFocus()
        }
        // The accent is the one setting that changes how rows and cards are drawn.
        val rebuild = look != config.accent
        look = config.accent
        rail.setHasInputs(data.inputs.isNotEmpty(), animate)
        rail.paintBar()
        binding = true
        list.bind(data.entries, rebuild, animate) { it.requestFocus() }
        search.bind(data.entries, data.watchNext)
        val rows = list.rows()
        if (!settled) {
            // The whole screen arrives at once. One layer for the fade: rendered once, then only blended.
            visibility = VISIBLE
            animate().alpha(1f).setDuration(FIRST_FADE_MS).setInterpolator(Tokens.ease).withLayer().start()
        }
        settled = true
        if (rows.isEmpty()) {
            binding = false
            return
        }
        // The selected app may be gone (hidden, uninstalled): follow focus, which arrange moved.
        val target = rows.firstOrNull { it.entry.key == selectedKey } ?: rows.firstOrNull { it.hasFocus() } ?: rows.first()
        if (target.hasFocus()) list.reveal(rows.indexOf(target))
        if (!placedFocus || !hasFocus()) {
            placedFocus = true
            list.reveal(rows.indexOf(target), animate = false)
            target.requestFocus()
        }
        binding = false
        select(target, refresh = true)
        if (activity.currentFocus === spotlight.channelButton && !spotlight.channelButton.isFocusable) target.requestFocus()
    }

    /** The Home Assistant row streams changes only while the home screen is in front. */
    fun live(on: Boolean) = homeRow.live(on)

    fun appTiles(): List<Tile> = data?.entries.orEmpty().filterNot { it.continues || it.isFolder }.map { it.app }

    /** Back from the channel row or the top pills returns to the selected app. True when handled. */
    fun consumesKey(event: KeyEvent): Boolean {
        // A lifted row owns the remote until it is dropped or put back.
        if (moving != null) return moveKey(event)
        // The remote's search key opens the panel from anywhere on the Index, and closes it again.
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_DOWN) if (searchOpen) closeSearch() else openSearch()
            return true
        }
        // Back out of Search first: the list comes back as it was.
        if (searchOpen && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) closeSearch()
            return true
        }
        if (homeRow.consumesKey(event)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        val focused = activity.currentFocus ?: return false
        val elsewhere = programs.isAncestorOf(focused) || folderGrid.isAncestorOf(focused) || topBar.isAncestorOf(focused) ||
            homeRow.isAncestorOf(focused) || rail.isAncestorOf(focused) || nowPlaying.isAncestorOf(focused) ||
            focused === spotlight.channelButton
        if (!elsewhere) return false
        if (event.action == KeyEvent.ACTION_UP) returnTarget()?.requestFocus()
        return true
    }

    /** HOME while home: back to the first app. */
    fun reset() {
        endMove()
        closeSearch()
        homeRow.stopDimming()
        inputMenu?.dismiss()
        channelMenu?.dismiss()
        val first = list.rows().firstOrNull() ?: return
        // Focus first: its listener selects the row, then the jump lands the selection with the list.
        first.requestFocus()
        list.reveal(0, animate = false)
    }

    /** OK on the rail's Search, or the remote's search key: the panel takes the app list's place. */
    private fun openSearch() {
        if (searchOpen) return
        searchOpen = true
        searchReturn = selectedKey
        list.animate().cancel()
        list.animate().alpha(0f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).withEndAction {
            // A reopen cancels this fade, which must not hide the list under the panel.
            if (searchOpen) {
                list.visibility = GONE
                list.alpha = 1f
            }
        }.start()
        rail.setSearch(true)
        // The TV's keyboard drops over the screen without moving Couch's fixed layout.
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        search.open()
    }

    /** BACK on the panel, or OK on the rail's Home: the list comes back as it was. */
    private fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        search.close()
        rail.setSearch(false)
        list.animate().cancel()
        list.visibility = VISIBLE
        list.alpha = 0f
        list.animate().alpha(1f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).start()
        val rows = list.rows()
        val back = rows.firstOrNull { it.entry.key == searchReturn } ?: rows.firstOrNull()
        searchReturn = null
        // Its focus listener selects it again, and the right column follows it.
        back?.requestFocus()
    }

    private fun row(entry: IndexEntry) = AppRow(activity, entry).apply {
        setOnFocusChangeListener { v, has -> if (has && !binding) select(v as AppRow, refresh = false) }
        // Read this.entry, not the one this row was made with: the list reuses a row across rebinds.
        setOnClickListener {
            val entry = this.entry
            when {
                // Continue watching has no app to open: OK resumes the first program.
                entry.continues -> entry.programs.firstOrNull()?.launch(activity)
                // A folder opens to its apps, which the right column is already showing.
                entry.isFolder -> folderGrid.first()?.requestFocus()
                else -> entry.app.launch(activity)
            }
        }
        setOnLongClickListener {
            val entry = this.entry
            when {
                // The row is Couch's own: there is no app to pin or hide.
                entry.continues -> Unit
                // A folder is renamed and removed, and its apps moved in and out of it.
                entry.isFolder -> onFolderMenu(entry, this)
                else -> onAppMenu(entry.app, this)
            }
            true
        }
    }

    /** Makes [row] the selected app and fills the right column with it. */
    private fun select(row: AppRow, refresh: Boolean) {
        val changed = row.entry.key != selectedKey
        selectedKey = row.entry.key
        val inRow = programs.hasFocus() || spotlight.channelButton.hasFocus() || folderGrid.hasFocus()
        list.select(row, inRow)
        if (row.hasFocus()) list.reveal(list.rows().indexOf(row))
        if (changed || refresh) show(row.entry, deferStrip = changed && !refresh)
        else if (!inRow) showProgram(row.entry, null)
    }

    /** Fills the right column with [entry]: its channel row (or banner), the text above it, the week. */
    private fun show(entry: IndexEntry, deferStrip: Boolean) {
        if (entry.isFolder) {
            showFolder(entry)
            return
        }
        folderGrid.visibility = GONE
        val data = data ?: return
        removeCallbacks(fillStrip)
        if (deferStrip) {
            // Another app's cards must not linger under this one while the row waits.
            if (stripKey != entry.stripKey) programs.update(emptyList(), "") { error("no cards") }
            postDelayed(fillStrip, STRIP_SETTLE_MS)
        } else {
            fill(entry)
        }
        showProgram(entry, (activity.currentFocus as? Card)?.takeIf { programs.isAncestorOf(it) }?.tile)
        banner.setImageBitmap(entry.app.banner)
        // While the channels load, no banner: it would only flash before the app's cards.
        banner.visibility = if (data.channelsKnown && entry.programs.isEmpty() && entry.app.banner != null) VISIBLE else GONE
        removeCallbacks(statsLater)
        if (deferStrip) post(statsLater) else showStats(entry)
    }

    private fun showStats(entry: IndexEntry) {
        val data = data ?: return
        // Screen time under the week belongs to apps: Continue watching and folders have none to show.
        stats.visibility = if (entry.continues || entry.isFolder) GONE else VISIBLE
        if (entry.continues || entry.isFolder) return
        stats.show(entry, data.usageKnown, System.currentTimeMillis(), config.clock24h)
    }

    /**
     * The right column for a folder: its count and name above, its apps as app cards in place of
     * the channel row. No banner and no week: a folder has no channel and no screen time of its own.
     */
    private fun showFolder(entry: IndexEntry) {
        removeCallbacks(fillStrip)
        removeCallbacks(statsLater)
        removeCallbacks(buildMore)
        if (stripKey != entry.stripKey) {
            stripKey = entry.stripKey
            programs.forget()
        }
        // Another app's cards must not linger under the folder's tiles.
        programs.update(emptyList(), "") { error("no cards") }
        spotlight.showFolder(entry.app.label, entry.folderApps.size)
        folderGrid.show(entry.key, entry.folderApps, look)
        folderGrid.visibility = VISIBLE
        banner.visibility = GONE
        stats.visibility = GONE
    }

    private fun fill(entry: IndexEntry) {
        removeCallbacks(fillStrip)
        if (stripKey != entry.stripKey) {
            stripKey = entry.stripKey
            programs.forget()
        }
        removeCallbacks(buildMore)
        built = 0
        buildStep(entry)
    }

    private fun buildStep(entry: IndexEntry) {
        // Past the screen's edge nobody sees them arrive, so the rest come in one go.
        val visible = visibleCount(entry.programs)
        built = if (built >= visible) entry.programs.size else minOf(visible, built + CARDS_PER_FRAME)
        update(entry, entry.programs.take(built))
        if (built < entry.programs.size) postOnAnimation(buildMore)
    }

    private fun update(entry: IndexEntry, tiles: List<Tile>) {
        val cardLook = "${entry.stripKey}|$look"
        programs.update(tiles, cardLook) { card(it, cardLook) }
    }

    /** How many cards reach the right edge of the screen, plus one to glide in. */
    private fun visibleCount(tiles: List<Tile>): Int {
        var x = 0
        tiles.forEachIndexed { i, tile ->
            x += IndexTokens.cardWidth(tile.imageRatio) + IndexTokens.CARD_GAP
            if (x > SCREEN_W - IndexTokens.COLUMN_X) return i + 2
        }
        return tiles.size
    }

    /** The text for [program], or the app's first one, or the app itself when it has no channel. */
    private fun showProgram(entry: IndexEntry, program: Tile?) {
        val tile = program ?: entry.programs.firstOrNull()
        if (tile != null) {
            // Continue watching's channel is Couch's own, so the line above the title names the app
            // the program comes from; an app's own row names its channel.
            val source = if (entry.continues) {
                tile.packageName?.let { AppLabels.of(activity, it) }.orEmpty()
            } else {
                entry.channelTitle.orEmpty()
            }
            spotlight.showProgram(source, tile, entry.channels.size > 1)
        } else {
            // While the channels load, the app's name alone: not yet "no channel".
            spotlight.showApp(entry.app, if (data?.channelsKnown == false) "" else activity.getString(R.string.index_no_channel, entry.app.label))
        }
    }

    private fun card(tile: Tile, cardLook: String) = Card(
        activity, tile, IndexTokens.cardWidth(tile.imageRatio), IndexTokens.CARD_H, images,
        showLabel = false, look = cardLook, onFocused = { onProgramFocused(it) },
        radiusDp = IndexTokens.CARD_RADIUS, thinRing = true,
    ).apply {
        isLongClickable = true
        setOnLongClickListener {
            onProgramMenu(this)
            true
        }
    }

    private fun onProgramFocused(card: Card) {
        val row = selectedRow() ?: return
        list.select(row, inRow = true)
        showProgram(row.entry, card.tile)
    }

    /** One of a folder's apps: an app card, opened with OK and given the app's own long-press menu. */
    private fun appCard(tile: Tile) = Card(
        activity, tile, Tokens.APP_W, Tokens.APP_H, images,
        showLabel = false, look = "folder|$look", onFocused = { onTileFocused() },
        radiusDp = Tokens.CARD_RADIUS, thinRing = true,
    ).apply {
        isLongClickable = true
        setOnLongClickListener {
            onAppMenu(tile, this)
            true
        }
    }

    /** Focus is on one of the folder's tiles: the list keeps the folder row selected, quietened. */
    private fun onTileFocused() {
        val row = selectedRow() ?: return
        list.select(row, inRow = true)
    }

    private fun selectedRow() = list.rows().firstOrNull { it.entry.key == selectedKey }

    /** Where focus comes back to from the column, the rail or the top row: the panel's, or the selected app. */
    private fun returnTarget(): View? = if (searchOpen) search.target() else selectedRow()

    /** A search result has focus: the right column shows its app, and its program when it has one. */
    private fun preview(entry: IndexEntry, program: Tile?) {
        selectedKey = entry.key
        list.rows().firstOrNull { it.entry.key == entry.key }?.let { list.select(it, inRow = false) }
        // The result is already picked: its cards come at once rather than after the row's settle.
        show(entry, deferStrip = false)
        if (program != null) showProgram(entry, program)
    }

    /** A folder found by search: the list comes back with it selected, and focus moves into its tiles. */
    private fun openFolder(entry: IndexEntry) {
        closeSearch()
        val rows = list.rows()
        val row = rows.firstOrNull { it.entry.key == entry.key } ?: return
        list.reveal(rows.indexOf(row))
        // Its focus listener selects it, which fills the grid the tiles are reached from.
        row.requestFocus()
        folderGrid.first()?.requestFocus()
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        // Where left and up lead back to: the search panel's row while it has the list's place.
        val target = returnTarget()
        val inSearch = focused != null && search.isAncestorOf(focused)
        val inStrip = focused != null && programs.isAncestorOf(focused)
        val inTiles = focused != null && folderGrid.isAncestorOf(focused)
        val inTop = focused != null && topBar.isAncestorOf(focused)
        val inHome = focused != null && homeRow.isAncestorOf(focused)
        val inRail = focused != null && rail.isAncestorOf(focused)
        val inDock = focused != null && nowPlaying.isAncestorOf(focused)
        return when {
            inSearch -> when (direction) {
                // Right from an app result reaches its channel's cards, as from the app list.
                FOCUS_RIGHT -> search.entryOf(focused)?.let { entry ->
                    if (entry.isFolder) {
                        // The folder's tiles already have the column: Right lands on them.
                        folderGrid.first() ?: focused
                    } else {
                        if (stripKey != entry.stripKey) fill(entry)
                        spotlight.channelButton.takeIf { it.isFocusable } ?: programs.cards().firstOrNull() ?: nowPlaying.entry() ?: target ?: focused
                    }
                } ?: focused
                // Clear of the results: up, down and left are the panel's own business.
                else -> focused
            }
            focused is AppRow -> {
                val rows = list.rows()
                val i = rows.indexOf(focused)
                when (direction) {
                    FOCUS_UP -> rows.getOrNull(i - 1) ?: homeRow.takeIf { homeLayout }?.entry() ?: firstTopTarget() ?: focused
                    FOCUS_DOWN -> rows.getOrNull(i + 1) ?: focused
                    FOCUS_LEFT -> toRail(focused)
                    FOCUS_RIGHT -> if (focused.entry.isFolder) {
                        // Right enters the folder's grid, which the column filled with the row.
                        folderGrid.first() ?: focused
                    } else {
                        // Right before the row has filled: fill it now.
                        if (stripKey != focused.entry.stripKey) fill(focused.entry)
                        // An app with no channel has no cards: what's playing is still worth reaching.
                        if (spotlight.channelButton.isFocusable) spotlight.channelButton else programs.cards().firstOrNull() ?: nowPlaying.entry() ?: focused
                    }
                    else -> focused
                }
            }
            inTiles -> when (val next = folderGrid.next(focused, direction)) {
                // Left from the first column and up from the top row come back to the folder row.
                null -> target ?: focused
                // Down past the last row stays put; the grid's own focus change glides its rows.
                else -> next
            }
            inStrip -> when (direction) {
                FOCUS_UP -> if (spotlight.channelButton.isFocusable) spotlight.channelButton else target ?: focused
                // Down past the cards reaches what's playing, when anything is.
                FOCUS_DOWN -> nowPlaying.entry() ?: target ?: focused
                FOCUS_LEFT -> if (programs.cards().firstOrNull() === focused) target ?: focused else super.focusSearch(focused, direction)
                else -> super.focusSearch(focused, direction)
            }
            focused === spotlight.channelButton -> when (direction) {
                FOCUS_DOWN -> programs.cards().firstOrNull() ?: nowPlaying.entry() ?: target ?: focused
                FOCUS_RIGHT -> programs.cards().firstOrNull() ?: target ?: focused
                else -> target ?: focused
            }
            inDock -> when (direction) {
                // The dock sits at the bottom: down stays, up returns to the cards, back to the list.
                FOCUS_DOWN -> focused
                FOCUS_UP -> programs.cards().firstOrNull() ?: target ?: focused
                FOCUS_LEFT -> nowPlaying.neighbour(focused, FOCUS_LEFT) ?: target ?: focused
                FOCUS_RIGHT -> nowPlaying.neighbour(focused, FOCUS_RIGHT) ?: focused
                else -> focused
            }
            inHome -> when (direction) {
                FOCUS_DOWN -> target ?: focused
                FOCUS_LEFT -> super.focusSearch(focused, direction)?.takeIf { homeRow.isAncestorOf(it) } ?: toRail(focused)
                FOCUS_RIGHT -> super.focusSearch(focused, direction)?.takeIf { homeRow.isAncestorOf(it) } ?: firstTopTarget() ?: focused
                else -> focused
            }
            inRail -> when (direction) {
                FOCUS_UP -> rail.neighbour(focused!!, down = false) ?: focused
                FOCUS_DOWN -> rail.neighbour(focused!!, down = true) ?: focused
                FOCUS_RIGHT -> target ?: focused
                else -> focused
            }
            inTop -> when (direction) {
                FOCUS_DOWN -> target ?: focused
                // Left past the first chip returns to the Home Assistant row's last pill.
                else -> super.focusSearch(focused, direction)?.takeIf { topBar.isAncestorOf(it) }
                    ?: homeRow.lastPill().takeIf { direction == FOCUS_LEFT && homeLayout } ?: focused
            }
            else -> super.focusSearch(focused, direction)
        }
    }

    /** The first chip at the top right that is showing, reached from the apps (no Home Assistant row) or its end. */
    private fun firstTopTarget(): View? = listOf<View>(topBar.inputOffer, topBar.update)
        .firstOrNull { it.visibility == VISIBLE && it.isFocusable }

    /** The rail item level with [from]'s middle. */
    private fun toRail(from: View): View? {
        val at = IntArray(2).also { from.getLocationOnScreen(it) }
        return rail.nearest(at[1] + from.height / 2)
    }

    private fun View.isAncestorOf(view: View): Boolean {
        var p: View? = view
        while (p != null) {
            if (p === this) return true
            p = p.parent as? View
        }
        return false
    }

    private fun pickInput() {
        val inputs = data?.inputs.orEmpty()
        if (inputs.isEmpty() || inputMenu != null) return
        inputMenu = InputMenu(activity, inputs, rail.inputs).apply {
            setOnDismissListener { if (inputMenu === this) inputMenu = null }
            show()
        }
    }

    private fun chooseChannel() {
        val entry = selectedRow()?.entry ?: return
        if (entry.channels.size < 2 || channelMenu != null) return
        val items = entry.channels.map { channel ->
            // A check marks the channel the app is showing; the others keep its room.
            val icon = if (channel.id == entry.channel?.id) R.drawable.ic_menu_check else 0
            Popover.Item(icon, channel.title) { onChannelSelected(entry.key, channel.id) }
        }
        channelMenu = Popover(activity, spotlight.channelButton, items, side = Side.BELOW).apply {
            setOnDismissListener { if (channelMenu === this) channelMenu = null }
            show()
        }
    }

    /**
     * Lifts the app [key] out of the list, the dialog-free Move: up and down take it through its
     * own group (pinned apps among pinned apps, the others among themselves), OK drops it there
     * and Back puts it back. The long-press menu offers it only where its group has two apps.
     */
    fun startMove(key: String) {
        if (moving != null) return
        val rows = list.rows()
        val row = rows.firstOrNull { it.entry.key == key } ?: return
        // Continue watching and folders are Couch's own rows: they take no place in the saved order.
        if (IndexEntry.isOwn(key)) return
        if (group(key).count() < 2) return
        moving = key
        moveFrom = rows.indexOf(row)
        moveBy = 0
        moveRow = row
        row.requestFocus()
        list.lift(row)
        list.shift(0, animate = false)
        // The hint is anchored to the row but takes no keys: the lift keeps the remote.
        moveHint = Popover.hint(activity, row, activity.getString(R.string.move_hint)).apply { show() }
    }

    /** While an app is lifted, the remote moves it: up and down, OK drops, Back puts it back. */
    private fun moveKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) shift(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) shift(1)
            // On release, so the press's other half doesn't open the app.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!down) drop()
            KeyEvent.KEYCODE_BACK -> if (down) endMove()
            // Left and right have nowhere to lift it: the list keeps the row.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            // Volume and the rest still work; the move owns only the list's own keys.
            else -> return false
        }
        return true
    }

    /**
     * Moves the lifted row one app [by], when its group has room, and the hint follows. A folder or
     * Continue watching row in the way is crossed in one step: it keeps its place in the list.
     */
    private fun shift(by: Int) {
        val key = moving ?: return
        val rows = list.rows()
        val apps = rows.withIndex().filterNot { IndexEntry.isOwn(it.value.entry.key) }
        val at = apps.indexOfFirst { it.value.entry.key == key }
        val to = at + by
        if (at < 0 || to !in group(key)) return
        moveBy = apps[to].index - moveFrom
        list.shift(moveBy)
        // The row may have left the visible rows: the list glides to keep it in sight.
        list.reveal(moveFrom + moveBy)
        removeCallbacks(followHint)
        postDelayed(followHint, Tokens.GLIDE_MS)
    }

    /** Drops the lifted row where it is: the order is saved and the list reloads in it. */
    private fun drop() {
        val key = moving ?: return
        val rows = ArrayList(list.rows())
        // The list only glides the lifted row, its column keeps it in place: read it where it now shows.
        rows.add(moveFrom + moveBy, rows.removeAt(moveFrom))
        val saved = Folders.drop(
            rows.map { row ->
                val entry = row.entry
                when {
                    entry.continues -> Folders.Row.Own
                    entry.isFolder -> Folders.Row.Group(entry.folderApps.map { app -> app.key })
                    else -> Folders.Row.App(entry.key)
                }
            },
            config.pinnedApps, key,
        )
        endMove()
        if (saved != null) onMoved(saved.order, key, saved.position)
    }

    /** The apps [key] can be moved through, its own group's, in the order the list shows them. */
    private fun group(key: String): IntRange {
        val apps = list.rows().map { it.entry.key }.filterNot { IndexEntry.isOwn(it) }
        return AppOrder.group(apps, config.pinnedApps, key)
    }

    /** Ends a lift without saving: the rows go back where they were and the hint goes away. */
    private fun endMove(focus: Boolean = true) {
        val key = moving ?: return
        moving = null
        removeCallbacks(followHint)
        moveHint?.dismiss()
        moveHint = null
        list.endLift()
        // Focus stays on the row that was lifted, wherever it now sits.
        if (focus) list.rows().firstOrNull { it.entry.key == key }?.requestFocus()
        moveRow = null
        moveBy = 0
    }

    // The activity is going away: a dialog left open would leak its window.
    override fun onDetachedFromWindow() {
        inputMenu?.dismiss()
        channelMenu?.dismiss()
        // The hint is a window of its own, and would outlive the screen that shows it.
        endMove(focus = false)
        super.onDetachedFromWindow()
    }

    private fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
        addView(view, LayoutParams(if (w > 0) activity.dp(w) else w, if (h > 0) activity.dp(h) else h).apply {
            leftMargin = activity.dp(x)
            topMargin = activity.dp(y)
        })
    }

    private companion object {
        /** Where the channel row's clipping starts, left of the column: room for a focused card's ring. */
        const val STRIP_START = 16
        /** Between the Home Assistant row and the top bar's first item. */
        const val HOME_ROW_GAP = 16
        const val STRIP_SETTLE_MS = 160L
        const val FIRST_FADE_MS = 260L
        const val CARDS_PER_FRAME = 2
        const val SCREEN_W = 960
    }
}

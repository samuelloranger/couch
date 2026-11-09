package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.hass.HomeAssistant
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.FocusRow
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * The Index layout: a rail of Inputs and Settings down the left edge, the apps listed beside it;
 * on the right, what the selected app offers (the focused program's text over its chosen
 * channel's cards), its last week of use, and the TV's readings along the bottom.
 *
 * D-pad: up/down walks the list, right enters the app's channel row, left (from its first card),
 * down comes back. With several channels, right reaches the channel picker first and up from
 * the cards returns to it. Left from the list (or the Home Assistant row) reaches the rail, right
 * from the rail returns to the selected app. Up from the first app reaches the Home Assistant row,
 * or else the chips at the top right.
 */
@SuppressLint("ViewConstructor")
class IndexScreen(
    private val activity: Activity,
    private val images: Images,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
    private val onAppMenu: (Tile) -> Unit,
    private val onProgramMenu: (Card) -> Unit,
    private val onChannelSelected: (String, Long) -> Unit,
) : FrameLayout(activity) {
    val topBar = IndexTopBar(activity, onUpdate)
    private val rail = IndexRail(activity, { pickInput() }, onSettings)
    private val list = AppList(activity) { entry -> row(entry) }
    private val spotlight = Spotlight(activity)
    private val programs = FocusRow(activity).apply {
        gapDp = IndexTokens.CARD_GAP
        leavesAtStart = true
        setStartPadding(activity.dp(STRIP_START) - activity.dp(Tokens.RING_INSET))
    }
    /** The app's banner, in the channel row's place when the app has no channel. */
    private val banner = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val stats = StatsBar(activity)
    val systemLine = SystemLine(activity)
    /** Lights and plugs from Home Assistant, top of the right column; gone until some are chosen. */
    private val homeRow = HomeRow(activity, HomeAssistant(Prefs.of(activity)))
    /** Whether the Home Assistant row is showing. */
    private var homeLayout = false

    private var data: IndexData? = null
    private var config = Config()
    private var look = ""
    private var selectedKey: String? = null
    /** The app and channel whose programs the row holds. */
    private var stripKey: String? = null
    /** The rail's Inputs menu while it is open. */
    private var inputMenu: InputMenu? = null
    private var channelMenu: AlertDialog? = null
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
        listOf(rail.inputs, rail.settings).forEach { item ->
            item.setOnFocusChangeListener { _, has -> if (has) selectedRow()?.let { list.select(it, inRow = true) } }
        }
        place(list, IndexTokens.LIST_X, IndexTokens.LIST_Y, IndexTokens.LIST_W, IndexTokens.LIST_H)
        place(spotlight, IndexTokens.COLUMN_X, IndexTokens.HEADER_Y, IndexTokens.COLUMN_W, LayoutParams.WRAP_CONTENT)
        // The row starts a little left of the column so a scaled card's ring isn't clipped.
        val cardTop = 10 + Tokens.RING_INSET
        addView(programs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = activity.dp(IndexTokens.COLUMN_X - STRIP_START)
            topMargin = activity.dp(IndexTokens.STRIP_Y - cardTop)
        })
        place(banner, IndexTokens.COLUMN_X, IndexTokens.STRIP_Y, IndexTokens.cardWidth(-1), IndexTokens.CARD_H)
        place(stats, IndexTokens.COLUMN_X, IndexTokens.STATS_Y, IndexTokens.COLUMN_W, LayoutParams.WRAP_CONTENT)
        addView(systemLine, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = activity.dp(IndexTokens.COLUMN_X)
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

    /** What's playing gives way to the Home Assistant row when it shows: its devices matter more there. */
    private fun layoutFor(home: Boolean) {
        if (home == homeLayout) return
        homeLayout = home
        topBar.nowPlaying.suppressed = home
    }

    fun bind(data: IndexData, config: Config) {
        this.data = data
        this.config = config
        val animate = settled
        topBar.animate = animate
        homeRow.bind(config, animate)
        layoutFor(homeRow.visibility == VISIBLE)
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

    fun appTiles(): List<Tile> = data?.entries.orEmpty().map { it.app }

    /** Back from the channel row or the top pills returns to the selected app. True when handled. */
    fun consumesKey(event: KeyEvent): Boolean {
        if (homeRow.consumesKey(event)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        val focused = activity.currentFocus ?: return false
        val elsewhere = programs.isAncestorOf(focused) || topBar.isAncestorOf(focused) || homeRow.isAncestorOf(focused) ||
            rail.isAncestorOf(focused) || focused === spotlight.channelButton
        if (!elsewhere) return false
        if (event.action == KeyEvent.ACTION_UP) selectedRow()?.requestFocus()
        return true
    }

    /** HOME while home: back to the first app. */
    fun reset() {
        homeRow.stopDimming()
        inputMenu?.dismiss()
        channelMenu?.dismiss()
        val first = list.rows().firstOrNull() ?: return
        // Focus first: its listener selects the row, then the jump lands the selection with the list.
        first.requestFocus()
        list.reveal(0, animate = false)
    }

    private fun row(entry: IndexEntry) = AppRow(activity, entry).apply {
        setOnFocusChangeListener { v, has -> if (has && !binding) select(v as AppRow, refresh = false) }
        setOnClickListener { this.entry.app.launch(activity) }
        setOnLongClickListener {
            onAppMenu(this.entry.app)
            true
        }
    }

    /** Makes [row] the selected app and fills the right column with it. */
    private fun select(row: AppRow, refresh: Boolean) {
        val changed = row.entry.key != selectedKey
        selectedKey = row.entry.key
        val inRow = programs.hasFocus() || spotlight.channelButton.hasFocus()
        list.select(row, inRow)
        if (row.hasFocus()) list.reveal(list.rows().indexOf(row))
        if (changed || refresh) show(row.entry, deferStrip = changed && !refresh)
        else if (!inRow) showProgram(row.entry, null)
    }

    /** Fills the right column with [entry]: its channel row (or banner), the text above it, the week. */
    private fun show(entry: IndexEntry, deferStrip: Boolean) {
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
        stats.show(entry, data.usageKnown, System.currentTimeMillis(), config.clock24h)
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
            spotlight.showProgram(entry.channelTitle.orEmpty(), tile, entry.channels.size > 1)
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

    private fun selectedRow() = list.rows().firstOrNull { it.entry.key == selectedKey }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val row = selectedRow()
        val inStrip = focused != null && programs.isAncestorOf(focused)
        val inTop = focused != null && topBar.isAncestorOf(focused)
        val inHome = focused != null && homeRow.isAncestorOf(focused)
        val inRail = focused != null && rail.isAncestorOf(focused)
        return when {
            focused is AppRow -> {
                val rows = list.rows()
                val i = rows.indexOf(focused)
                when (direction) {
                    FOCUS_UP -> rows.getOrNull(i - 1) ?: homeRow.takeIf { homeLayout }?.entry() ?: firstTopTarget() ?: focused
                    FOCUS_DOWN -> rows.getOrNull(i + 1) ?: focused
                    FOCUS_LEFT -> toRail(focused)
                    FOCUS_RIGHT -> {
                        // Right before the row has filled: fill it now.
                        if (stripKey != focused.entry.stripKey) fill(focused.entry)
                        if (spotlight.channelButton.isFocusable) spotlight.channelButton else programs.cards().firstOrNull() ?: focused
                    }
                    else -> focused
                }
            }
            inStrip -> when (direction) {
                FOCUS_UP -> if (spotlight.channelButton.isFocusable) spotlight.channelButton else row ?: focused
                FOCUS_DOWN -> row ?: focused
                FOCUS_LEFT -> if (programs.cards().firstOrNull() === focused) row ?: focused else super.focusSearch(focused, direction)
                else -> super.focusSearch(focused, direction)
            }
            focused === spotlight.channelButton -> when (direction) {
                FOCUS_DOWN, FOCUS_RIGHT -> programs.cards().firstOrNull() ?: row ?: focused
                else -> row ?: focused
            }
            inHome -> when (direction) {
                FOCUS_DOWN -> row ?: focused
                FOCUS_LEFT -> super.focusSearch(focused, direction)?.takeIf { homeRow.isAncestorOf(it) } ?: toRail(focused)
                FOCUS_RIGHT -> super.focusSearch(focused, direction)?.takeIf { homeRow.isAncestorOf(it) } ?: firstTopTarget() ?: focused
                else -> focused
            }
            inRail -> when (direction) {
                FOCUS_UP -> rail.neighbour(focused!!, down = false) ?: focused
                FOCUS_DOWN -> rail.neighbour(focused!!, down = true) ?: focused
                FOCUS_RIGHT -> row ?: focused
                else -> focused
            }
            inTop -> when (direction) {
                FOCUS_DOWN -> row ?: focused
                // Left past the first chip returns to the Home Assistant row's last pill.
                else -> super.focusSearch(focused, direction)?.takeIf { topBar.isAncestorOf(it) }
                    ?: homeRow.lastPill().takeIf { direction == FOCUS_LEFT && homeLayout } ?: focused
            }
            else -> super.focusSearch(focused, direction)
        }
    }

    /** The first chip at the top right that is showing, reached from the apps (no Home Assistant row) or its end. */
    private fun firstTopTarget(): View? = listOf<View>(topBar.inputOffer, topBar.nowPlaying, topBar.update)
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
        channelMenu = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.choose_channel)
            .setSingleChoiceItems(entry.channels.map { it.title }.toTypedArray(), entry.channels.indexOf(entry.channel)) { dialog, which ->
                onChannelSelected(entry.key, entry.channels[which].id)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create().apply {
                setOnDismissListener { channelMenu = null }
                show()
            }
    }

    // The activity is going away: a dialog left open would leak its window.
    override fun onDetachedFromWindow() {
        inputMenu?.dismiss()
        channelMenu?.dismiss()
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

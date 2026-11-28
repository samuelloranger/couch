package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.data.Banners
import cloud.samlo.couch.ui.Focus
import cloud.samlo.couch.ui.Popover
import cloud.samlo.couch.ui.PopoverRow
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.Type
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * An app's page in the Index's right column: its icon and name over what can be done with it. An app
 * with no channel shows one as the column's content (an Open button, [modal] false); the long-press
 * menu shows another over the whole column (arrange tiles, a Manage list, Uninstall alone below,
 * [modal] true). Both are built from the same [Spec], so the actions look and move the same way.
 *
 * The sheet routes its own D-pad moves (tiles across, rows down, tiles again up). What leaves it:
 * Left and Down from the inline sheet go to [IndexScreen]'s routing; the modal one never lets focus
 * out, its owner closes it on Left (from the first tile or any row) and Back.
 */
@SuppressLint("ViewConstructor")
class AppSheet(context: Context, private val modal: Boolean) : FrameLayout(context) {
    /**
     * What a sheet shows. [open] is the filled primary button; [tiles] a row of arrange tiles; each
     * [Section] a titled list of rows. [up] rebuilds the page this one came from (Back returns to it),
     * and [focus] names the item to focus when the page opens.
     */
    class Spec(
        val name: String,
        val subtitle: String,
        val icon: Bitmap?,
        val iconDp: Int,
        val open: Popover.Item? = null,
        val tiles: List<Popover.Item> = emptyList(),
        val sections: List<Section> = emptyList(),
        val up: (() -> Spec)? = null,
        val focus: String? = null,
    )

    /** A list of rows under an optional [title]; [divided] sets it apart with a rule, as Uninstall is. */
    class Section(val title: String?, val items: List<Popover.Item>, val divided: Boolean = false)

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val main = Handler(Looper.getMainLooper())
    private val unask = Runnable { ask(null) }
    private var openButton: ActionButton? = null
    private var tiles: List<SheetTile> = emptyList()
    private var rows: List<SheetRow> = emptyList()
    /** The tile focus last stood on, where Up from the rows returns. */
    private var lastTile = 0
    /** The row waiting for its second OK. */
    private var asking: SheetRow? = null
    private var spec: Spec? = null
    /** Runs a picked item: the owner closes the sheet first when it is a menu. */
    var onPick: (Popover.Item) -> Unit = { it.action() }

    init {
        clipChildren = false
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Rows and tiles carry their focus ring past their own edge.
        column.clipChildren = false
    }

    /** The page showing now. */
    fun page(): Spec? = spec

    /** The page that Back returns to, or null on the first one. */
    fun up(): Spec? = spec?.up?.invoke()

    /** Shows [next], replacing the page there is. Focus is left to the caller. */
    fun show(next: Spec) {
        main.removeCallbacks(unask)
        asking = null
        spec = next
        column.removeAllViews()
        lastTile = 0
        column.addView(header(next), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(INSET) })
        openButton = next.open?.let { item ->
            ActionButton(context, filled = true).sizedFor(OPEN_W).apply {
                text = item.label
                setOnClickListener { pick(item, null) }
            }.also { column.addView(it, LinearLayout.LayoutParams(context.dp(OPEN_W), context.dp(OPEN_H)).apply {
                marginStart = context.dp(INSET)
                topMargin = context.dp(OPEN_TOP)
            }) }
        }
        tiles = next.tiles.map { item -> SheetTile(context, item).apply { setOnClickListener { pick(item, null) } } }
        if (tiles.isNotEmpty()) {
            column.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                tiles.forEachIndexed { i, tile ->
                    addView(tile, LinearLayout.LayoutParams(context.dp(TILE_W), context.dp(TILE_H)).apply { if (i > 0) marginStart = context.dp(TILE_GAP) })
                }
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(INSET)
                topMargin = context.dp(TILES_TOP)
            })
        }
        val built = mutableListOf<SheetRow>()
        next.sections.forEach { section ->
            if (section.divided) column.addView(rule(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1)).apply {
                topMargin = context.dp(RULE_GAP)
                bottomMargin = context.dp(RULE_GAP)
                marginStart = context.dp(INSET)
            })
            section.title?.let { column.addView(sectionTitle(it), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(TITLE_H)).apply {
                topMargin = context.dp(SECTION_TOP)
                marginStart = context.dp(INSET)
            }) }
            section.items.forEachIndexed { i, item ->
                val row = SheetRow(context, item).apply { setOnClickListener { pick(item, this) } }
                built += row
                // A list with no title or rule over it still stands clear of what is above it.
                val clear = i == 0 && section.title == null && !section.divided
                column.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(ROW_H)).apply {
                    if (clear) topMargin = context.dp(SECTION_TOP)
                })
            }
        }
        rows = built
    }

    /** The page's first focusable: where focus lands coming in from the list. */
    fun entry(): View? = openButton ?: tiles.firstOrNull() ?: rows.firstOrNull()

    /** Puts focus on the page's [Spec.focus] item, else its first focusable. */
    fun focusFirst() {
        val s = spec ?: return
        val named = s.focus
        val target: View? = tiles.firstOrNull { it.item.label == named } ?: rows.firstOrNull { it.item.label == named }
        (target ?: entry())?.requestFocus()
    }

    /** True when Left from [view] has nowhere to go inside the sheet: the first tile, or any row. */
    fun atLeftEdge(view: View?): Boolean = view != null && (view === openButton || view === tiles.firstOrNull() || view is SheetRow)

    private fun pick(item: Popover.Item, row: SheetRow?) {
        // A row that asks twice keeps the sheet open on its first OK.
        if (item.confirm != null && row != null && asking !== row) {
            ask(row)
            return
        }
        onPick(item)
    }

    /** Makes [row] show its question in place of its label for a few seconds, or with null stops asking. */
    private fun ask(row: SheetRow?) {
        main.removeCallbacks(unask)
        asking?.relabel(back = true)
        asking = row
        row ?: return
        row.relabel(back = false)
        main.postDelayed(unask, CONFIRM_MS)
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        return when {
            focused is SheetTile -> when (direction) {
                FOCUS_LEFT -> tiles.getOrNull(tiles.indexOf(focused) - 1) ?: edge(focused, direction)
                FOCUS_RIGHT -> tiles.getOrNull(tiles.indexOf(focused) + 1) ?: focused
                FOCUS_DOWN -> {
                    lastTile = tiles.indexOf(focused)
                    rows.firstOrNull() ?: edge(focused, direction)
                }
                else -> focused
            }
            focused is SheetRow -> {
                val i = rows.indexOf(focused)
                when (direction) {
                    FOCUS_UP -> rows.getOrNull(i - 1) ?: tiles.getOrNull(lastTile) ?: openButton ?: focused
                    FOCUS_DOWN -> rows.getOrNull(i + 1) ?: edge(focused, direction)
                    FOCUS_LEFT -> edge(focused, direction)
                    else -> focused
                }
            }
            focused === openButton -> when (direction) {
                FOCUS_LEFT, FOCUS_DOWN -> edge(focused, direction)
                else -> focused
            }
            else -> focused
        }
    }

    /** Past the sheet's own edge: an inline sheet hands over to the screen's routing, a menu stays put. */
    private fun edge(focused: View?, direction: Int): View? = if (modal) focused else super.focusSearch(focused, direction)

    override fun onDetachedFromWindow() {
        main.removeCallbacks(unask)
        super.onDetachedFromWindow()
    }

    private fun header(spec: Spec) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(spec.icon)
            // A missing icon leaves its place, so the name doesn't jump.
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(spec.iconDp * ICON_RADIUS_RATIO)
                setColor(Tokens.surface)
            }
        }, LinearLayout.LayoutParams(context.dp(spec.iconDp), context.dp(spec.iconDp)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(IndexText(context, Type.TITLE, 600, Tokens.textPrimary).apply {
                text = spec.name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                letterSpacing = -0.02f
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(NAME_H)))
            if (spec.subtitle.isNotEmpty()) addView(IndexText(context, Type.LABEL, 400, IndexTokens.hours).apply {
                text = spec.subtitle
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(SUB_H)).apply { topMargin = context.dp(2) })
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(16) })
    }

    private fun sectionTitle(text: String) = IndexText(context, Type.CAPTION, 600, IndexTokens.hours).apply {
        this.text = text
        isAllCaps = true
        letterSpacing = 0.08f
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun rule() = View(context).apply { setBackgroundColor(0x14FFFFFF) }

    companion object {
        /** The app's launcher icon at [dp], drawn once at that size: the sheet's header. */
        fun icon(context: Context, pkg: String?, dp: Int): Bitmap? {
            pkg ?: return null
            val pm = context.packageManager
            val updated = try {
                pm.getPackageInfo(pkg, 0).lastUpdateTime
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                return null
            }
            val size = context.dp(dp)
            return Banners.memoized("sheet|$pkg|$size|$updated") {
                try {
                    Banners.render(pm.getApplicationIcon(pkg), size, size, size * ICON_RADIUS_RATIO)
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    null
                }
            }
        }

        const val OPEN_W = 200
        const val OPEN_H = 40
        private const val ICON_RADIUS_RATIO = 0.2f
        /** The text's left edge from the sheet's: rows start this much further left so their plates reach beyond it. */
        private const val INSET = 8
        private const val OPEN_TOP = 20
        private const val TILES_TOP = 18
        private const val TILE_W = 114
        private const val TILE_H = 84
        private const val TILE_GAP = 8
        private const val SECTION_TOP = 18
        private const val TITLE_H = 20
        private const val ROW_H = 34
        private const val RULE_GAP = 8
        private const val NAME_H = 30
        private const val SUB_H = 20
        private const val CONFIRM_MS = 3_000L
    }
}

/** One arrange tile: an icon over a short label, a card's ring and growth when focused. */
@SuppressLint("ViewConstructor")
private class SheetTile(context: Context, val item: Popover.Item) : FrameLayout(context) {
    private val label = IndexText(context, Type.LABEL, 500, Tokens.textPrimary).apply {
        text = item.label
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        isFocusable = true
        isClickable = true
        contentDescription = item.label
        clipChildren = false
        val icon = ImageView(context).apply {
            if (item.icon != 0) setImageResource(item.icon)
            setColorFilter(Tokens.textMuted)
        }
        addView(icon, LayoutParams(context.dp(ICON), context.dp(ICON), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(14) })
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(LABEL_H), Gravity.TOP).apply {
            topMargin = context.dp(44)
            marginStart = context.dp(8)
            marginEnd = context.dp(8)
        })
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Focus.tileRing(context, RADIUS, Tokens.surfaceFocused))
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = context.dpf(RADIUS)
                setColor(Tokens.surface)
            })
        }
        val scale = Focus.scale(114)
        setOnFocusChangeListener { _, has ->
            icon.setColorFilter(if (has) Color.WHITE else Tokens.textMuted)
            animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
                .setDuration(if (has) Tokens.FOCUS_IN_MS else Tokens.FOCUS_OUT_MS).setInterpolator(Tokens.ease).start()
            // The scaled tile draws over its neighbours.
            translationZ = if (has) 1f else 0f
        }
    }

    private companion object {
        const val ICON = 22
        const val LABEL_H = 34
        const val RADIUS = 10f
    }
}

/** One row of a list: the shared menu row, focusable, in the plate-and-marker language while focused. */
@SuppressLint("ViewConstructor")
private class SheetRow(context: Context, val item: Popover.Item) : FrameLayout(context) {
    private val row = PopoverRow(context, item)

    init {
        isFocusable = true
        isClickable = true
        contentDescription = item.label
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnFocusChangeListener { _, has -> row.chosen = has }
    }

    /** Shows the row's own label again ([back]) or its confirmation question in the warning colour. */
    fun relabel(back: Boolean) {
        val confirm = item.confirm ?: return
        row.relabel(if (back) item.label else confirm, warn = !back || item.warn)
    }
}

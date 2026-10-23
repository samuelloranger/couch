package cloud.samlo.couch.debloat

import android.widget.GridLayout
import android.widget.LinearLayout
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/** One tile to draw: [kind] picks the glyph when the app has no icon; [locked] tiles can't be picked. */
internal class GridItem(val pkg: String, val kind: DebloatList.Why, val help: String, val locked: Boolean = false)

/**
 * Adds a grid of [AppTile]s to the page's list, five to a row, and starts their icons loading.
 * The D-pad moves through it by geometry like any focusable view; the page's scroll view follows.
 */
internal fun DebloatPage.grid(items: List<GridItem>, onClick: (String, AppTile) -> Unit = { _, _ -> }): List<AppTile> {
    val context = ctx.activity
    val grid = GridLayout(context).apply {
        columnCount = COLUMNS
        clipChildren = false
        clipToPadding = false
    }
    val px = context.dp(AppTile.ICON)
    val radius = context.dpf(AppTile.ICON_RADIUS)
    val tiles = items.map { item ->
        AppTile(context, label(item.pkg), item.kind, item.help, ctx.layout.help, item.locked).also { tile ->
            tile.tag = item.pkg
            if (!item.locked) tile.setOnClickListener { onClick(item.pkg, tile) }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = context.dp(AppTile.WIDTH)
                height = context.dp(AppTile.HEIGHT)
                setMargins(context.dp(GAP / 2), context.dp(GAP / 2), context.dp(GAP / 2), context.dp(GAP / 2))
            })
            icons.load(item.pkg, px, radius) { bitmap -> tile.post { tile.show(bitmap) } }
        }
    }
    ctx.layout.list.clipChildren = false
    ctx.layout.list.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        marginStart = context.dp(SIDE)
    })
    return tiles
}

private const val COLUMNS = 5
private const val GAP = 12
/** Lines the first tile up with the rows' text, which sits 20 dp in. */
private const val SIDE = 14

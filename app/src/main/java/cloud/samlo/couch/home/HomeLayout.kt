package cloud.samlo.couch.home

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.index.IndexScreen
import cloud.samlo.couch.ui.Ambient
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Hero
import cloud.samlo.couch.ui.Rows
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * The home screen's view tree, bottom to top: the rows layout (hero, rows, top bar), the Index
 * layout over it, and the ambient overlay. Only the layout the user chose is visible.
 */
class HomeViews(
    activity: Activity,
    images: Images,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
    onAppMenu: (Tile) -> Unit,
    onProgramMenu: (Card) -> Unit,
) {
    val hero = Hero(activity, images)
    val rows = Rows(activity)
    val topBar = TopBar(activity, onSettings, onUpdate)
    val index = IndexScreen(activity, images, onSettings, onUpdate, onAppMenu, onProgramMenu).apply { visibility = View.GONE }
    val ambient = Ambient(activity, images)
    private val window = activity.window

    val root = FrameLayout(activity).apply {
        addView(hero, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(rows, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            topMargin = activity.dp(Tokens.ROWS_TOP)
        })
        addView(topBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = activity.dp(Tokens.OVERSCAN_V)
            marginEnd = activity.dp(Tokens.OVERSCAN_H)
        })
        addView(index, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(ambient, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** True when the Index layout is the one showing. */
    val indexShown: Boolean get() = index.visibility == View.VISIBLE

    /** Shows one layout and hides the other, so the hidden one costs nothing to draw. */
    fun showIndex(show: Boolean) {
        if (show == indexShown) return
        // Index sits on a flat colour; painting it as the window background avoids a full-screen layer.
        if (show) window.setBackgroundDrawable(ColorDrawable(Tokens.background)) else window.setBackgroundDrawableResource(R.drawable.background)
        index.visibility = if (show) View.VISIBLE else View.GONE
        val rowsVisibility = if (show) View.GONE else View.VISIBLE
        hero.visibility = rowsVisibility
        rows.visibility = rowsVisibility
        topBar.visibility = rowsVisibility
    }
}

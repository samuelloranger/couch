package cloud.samlo.couch.settings

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * The settings screen's frame: title and hint above a scrolling list. With [withRail], the groups
 * of Customize run down the left and the list is the page on their right; without, the list sits
 * on the left and a help panel on the right explains the focused grid tile.
 */
class SettingsLayout(context: Context, val withRail: Boolean = false) {
    val title = TextView(context).apply {
        setText(R.string.settings_title)
        textSize = if (withRail) 26f else 30f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Tokens.textPrimary)
        setPadding(context.dp(20), 0, 0, 0)
    }
    val hint = TextView(context).apply {
        textSize = 15f
        setTextColor(Tokens.textMuted)
        setPadding(context.dp(20), context.dp(4), 0, context.dp(16))
    }
    val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, context.dp(60))
    }
    val help = TextView(context).apply {
        textSize = 17f
        setTextColor(Tokens.textMuted)
        setLineSpacing(0f, 1.25f)
    }

    /** The groups of Customize; empty unless [withRail]. */
    val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val root = FrameLayout(context).apply {
        if (withRail) {
            addView(railColumn(context), FrameLayout.LayoutParams(context.dp(RAIL_W), MATCH_PARENT).apply {
                leftMargin = context.dp(Tokens.OVERSCAN_H - 12)
                topMargin = context.dp(Tokens.OVERSCAN_V + 10)
            })
            addView(FlatView(context).apply { setBackgroundColor(Tokens.surface) }, FrameLayout.LayoutParams(context.dp(1), MATCH_PARENT).apply {
                leftMargin = context.dp(PANE_X - 32)
                topMargin = context.dp(Tokens.OVERSCAN_V + 16)
                bottomMargin = context.dp(Tokens.OVERSCAN_V + 16)
            })
        }
        val x = if (withRail) PANE_X else Tokens.OVERSCAN_H
        addView(column(context), FrameLayout.LayoutParams(context.dp(if (withRail) CANVAS_W - Tokens.OVERSCAN_H - x + 20 else 560), MATCH_PARENT).apply {
            leftMargin = context.dp(x - 20)
            topMargin = context.dp(Tokens.OVERSCAN_V + 10)
        })
        addView(help, FrameLayout.LayoutParams(context.dp(300), WRAP_CONTENT).apply {
            leftMargin = context.dp(Tokens.OVERSCAN_H + 600)
            topMargin = context.dp(Tokens.OVERSCAN_V + 120)
        })
    }

    /** Everything currently in the list: section headers and option rows. */
    fun items(): List<View> = (0 until list.childCount).map { list.getChildAt(it) }

    private fun railColumn(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            setText(R.string.settings_title)
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Tokens.textPrimary)
            setPadding(context.dp(12), 0, 0, context.dp(20))
        })
        addView(rail)
    }

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(title)
        addView(hint)
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(40))
            addView(list)
        }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private companion object {
        /** The canvas is 960 dp wide; the rail and the page share what overscan leaves. */
        const val CANVAS_W = 960
        const val RAIL_W = 290
        const val PANE_X = 360
    }
}

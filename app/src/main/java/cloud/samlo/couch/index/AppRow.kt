package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * One app in the list: icon, name, and its screen time on the right. Selecting it only swaps
 * the text colours; the background and accent marker belong to [AppList], which glides them
 * between rows. No size changes, so no layout pass.
 */
@SuppressLint("ViewConstructor")
class AppRow(context: Context, entry: IndexEntry) : FrameLayout(context) {
    var entry: IndexEntry = entry
        private set

    private val icon = ImageView(context).apply {
        this.background = GradientDrawable().apply {
            cornerRadius = context.dpf(ICON_RADIUS)
            setColor(Tokens.surface)
        }
    }
    private val name = IndexText(context, 19f, 600, Tokens.textMuted).apply {
        letterSpacing = -0.015f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val hours = IndexText(context, 11f, 500, IndexTokens.hours).apply {
        fontFeatureSettings = "tnum"
        maxLines = 1
    }

    /** The app the list (and the right column) is showing. */
    var chosen = false
        set(value) {
            if (field == value) return
            field = value
            restyle()
        }

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(15), 0, context.dp(13), 0)
            addView(icon, LinearLayout.LayoutParams(context.dp(IndexTokens.ICON), context.dp(IndexTokens.ICON)))
            addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
            addView(hours, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) })
        }
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bind(entry)
    }

    fun bind(entry: IndexEntry) {
        this.entry = entry
        contentDescription = entry.app.label
        icon.setImageBitmap(entry.icon)
        name.text = entry.app.label
        hours.text = entry.usage?.let { Formats.short(context, it.weekMs) }.orEmpty()
        restyle()
    }

    private fun restyle() {
        name.setTextColor(if (chosen) Tokens.textPrimary else Tokens.textMuted)
        hours.setTextColor(if (chosen) Tokens.accent else IndexTokens.hours)
    }

    companion object {
        const val ICON_RADIUS = 6f
    }
}

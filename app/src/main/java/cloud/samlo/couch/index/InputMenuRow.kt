package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * One input in [InputMenu], set like an app in the list: its card colour and icon, its name, and
 * its port or "Disconnected" on the right. Selecting it only swaps text colours; the background
 * and marker belong to the menu, which glides them between rows.
 */
@SuppressLint("ViewConstructor")
class InputMenuRow(context: Context, val tile: Tile) : FrameLayout(context) {
    private val name = IndexText(context, 19f, 600, Tokens.textMuted).apply {
        letterSpacing = -0.015f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        text = tile.label
    }
    private val port = IndexText(context, 11f, 500, IndexTokens.hours).apply {
        maxLines = 1
        text = tile.subtitle.orEmpty()
    }

    var chosen = false
        set(value) {
            if (field == value) return
            field = value
            name.setTextColor(if (value) Tokens.textPrimary else Tokens.textMuted)
            port.setTextColor(if (value) Tokens.accent else IndexTokens.hours)
        }

    init {
        contentDescription = tile.label
        if (tile.dimmed) alpha = DIMMED_ALPHA
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(15), 0, context.dp(13), 0)
            addView(icon(context), LinearLayout.LayoutParams(context.dp(ICON_W), context.dp(IndexTokens.ICON)))
            addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
            addView(port, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) })
        }
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun icon(context: Context) = ImageView(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(ICON_RADIUS)
            // Darker than both the panel and the selection, so a plain icon shows on either.
            setColor(tile.cardColor ?: Tokens.background)
        }
        clipToOutline = true
        val pad = context.dp(if (tile.iconWide) 3 else 6)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        when {
            // A custom image fills the whole slot, like on the Inputs card.
            tile.banner != null -> {
                setPadding(0, 0, 0, 0)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(tile.banner)
            }
            tile.iconRes != 0 -> {
                setImageResource(tile.iconRes)
                setColorFilter(Tokens.textPrimary)
            }
        }
    }

    companion object {
        /** 16:9 like the input cards, so brand colours read the same. */
        const val ICON_W = 52
        const val ICON_RADIUS = 6f
        const val DIMMED_ALPHA = 0.45f
    }
}

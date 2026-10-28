package cloud.samlo.couch.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.index.IndexText
import cloud.samlo.couch.index.IndexTokens
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * One line in a panel, set like an app in the Index list. An input shows its card colour and
 * icon, an app its launcher icon, a setting its glyph on a plain card; all sit in the same slot
 * so the names line up. The item's detail (a port, a value) is on the right. Selecting it only
 * swaps text colours; the background and marker belong to [PanelView], which glides them.
 */
@SuppressLint("ViewConstructor")
class PanelRow(context: Context, val item: PanelItem) : FrameLayout(context) {
    private val name = IndexText(context, 19f, 600, Tokens.textMuted).apply {
        letterSpacing = -0.015f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        text = item.label
    }
    private val port = IndexText(context, 11f, 500, IndexTokens.hours).apply { maxLines = 1 }

    var chosen = false
        set(value) {
            if (field == value) return
            field = value
            name.setTextColor(if (value) Tokens.textPrimary else Tokens.textMuted)
            port.setTextColor(if (value) Tokens.accent else IndexTokens.hours)
        }

    init {
        if (item.dimmed) alpha = DIMMED_ALPHA
        refresh()
        val slot = FrameLayout(context)
        when (val icon = item.icon) {
            is PanelItem.Icon.Input -> slot.addView(inputIcon(context, icon.tile), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            is PanelItem.Icon.Glyph -> slot.addView(glyph(context, icon.res), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            is PanelItem.Icon.App -> {
                val size = context.dp(IndexTokens.ICON)
                slot.addView(ImageView(context).apply { setImageBitmap(icon.bitmap) }, LayoutParams(size, size, Gravity.CENTER))
            }
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(15), 0, context.dp(13), 0)
            addView(slot, LinearLayout.LayoutParams(context.dp(SLOT_W), context.dp(IndexTokens.ICON)))
            addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
            addView(port, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) })
        }
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Reads the item's detail again, after OK changed it in place. */
    fun refresh() {
        port.text = item.detail().orEmpty()
    }

    private fun glyph(context: Context, res: Int) = ImageView(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(ICON_RADIUS)
            setColor(Tokens.surface)
        }
        val pad = context.dp(6)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(res)
        setColorFilter(Tokens.textPrimary)
    }

    private fun inputIcon(context: Context, tile: Tile) = ImageView(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(ICON_RADIUS)
            setColor(tile.cardColor ?: Tokens.surface)
        }
        val pad = context.dp(if (tile.iconWide) 3 else 6)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        when {
            tile.banner != null -> {
                setPadding(0, 0, 0, 0)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                setImageBitmap(tile.banner)
            }
            tile.iconRes != 0 -> {
                setImageResource(tile.iconRes)
                setColorFilter(Tokens.textPrimary)
            }
        }
    }

    companion object {
        /** 16:9 like the input cards on the home screen, so brand colours read the same. */
        const val SLOT_W = 52
        const val ICON_RADIUS = 6f
        const val DIMMED_ALPHA = 0.45f
    }
}

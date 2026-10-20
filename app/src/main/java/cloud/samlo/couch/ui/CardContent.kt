package cloud.samlo.couch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile

/**
 * What a card shows inside its ring: a pre-rendered banner, remote art, or an icon with the
 * name. [isAlive] lets late image loads drop themselves once the card is gone.
 */
@SuppressLint("ViewConstructor")
class CardContent(
    context: Context,
    private val tile: Tile,
    private val widthDp: Int,
    private val heightDp: Int,
    private val images: Images,
    private val radiusDp: Float,
    private val isAlive: () -> Boolean,
) : FrameLayout(context) {
    init {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(radiusDp)
            setColor(tile.cardColor ?: Tokens.surface)
        }
        val banner = tile.banner
        when {
            // Pre-scaled and pre-rounded off the main thread, so drawing it is a plain blit.
            banner != null -> addView(image().apply { setImageBitmap(banner) }, fill())
            tile.imageUrl != null -> addRemoteArt(tile.imageUrl)
            else -> addView(iconColumn(), fill())
        }
        if (tile.progress >= 0f) addView(CardProgress(context, tile.progress), fill())
    }

    private fun addRemoteArt(url: String) {
        // Title stays underneath as the placeholder until (or unless) the art arrives.
        addView(cardLabel(context, tile.label, 14f, Tokens.textMuted).apply {
            maxLines = 2
            setPadding(context.dp(10), 0, context.dp(10), 0)
        }, fill())
        val view = image()
        addView(view, fill())
        // Art already in memory is set before the card is drawn; art that arrives later fades in
        // over the title (an ImageView without a background fades without an offscreen layer).
        var arrivedLate = false
        images.load(
            url,
            context.dp(widthDp),
            context.dp(heightDp),
            context.dpf(radiusDp),
            isCurrent = isAlive,
        ) {
            view.setImageBitmap(it)
            if (arrivedLate) {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).start()
            }
        }
        arrivedLate = true
    }

    private fun iconColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(8), 0, context.dp(8), 0)
        // Brand marks carry the identity, so they get more room.
        val iconSize = context.dp(if (tile.cardColor != null) 32 else if (tile.subtitle != null) 24 else 30)
        addView(icon(), if (tile.iconWide) LinearLayout.LayoutParams(iconSize * 3, iconSize) else LinearLayout.LayoutParams(iconSize, iconSize))
        addView(cardLabel(context, tile.label, 14f, Tokens.textPrimary).apply { setPadding(0, context.dp(4), 0, 0) })
        tile.subtitle?.let { addView(cardLabel(context, it, 11f, Tokens.textMuted)) }
    }

    private fun icon() = ImageView(context).apply {
        when {
            tile.icon != null -> setImageDrawable(tile.icon)
            tile.iconRes != 0 -> {
                setImageResource(tile.iconRes)
                setColorFilter(Tokens.textPrimary)
            }
        }
        // Wordmarks sit in a thin band of their square canvas: crop to that band so the
        // logo fills the slot instead of shrinking to fit its empty top and bottom.
        val square = drawable?.let { it.intrinsicWidth == it.intrinsicHeight } ?: true
        scaleType = if (tile.iconWide && square) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
    }

    private fun image() = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
}

/** A single-line, centred, ellipsized caption. */
fun cardLabel(context: Context, text: String, sizeSp: Float, color: Int) = TextView(context).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    gravity = Gravity.CENTER
}

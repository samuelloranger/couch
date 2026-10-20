package cloud.samlo.couch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile

/**
 * A focusable tile. Focus only animates render properties (scale and the ring's alpha), so a
 * move never triggers layout or a redraw of the card's content.
 */
@SuppressLint("ViewConstructor")
class Card(
    context: Context,
    val tile: Tile,
    widthDp: Int,
    private val heightDp: Int,
    images: Images,
    private val showLabel: Boolean,
    /** Size and style the card was drawn with; a change means it must be rebuilt. */
    val look: String,
    radiusDp: Float = Tokens.CARD_RADIUS,
    thinRing: Boolean = false,
    private val onFocused: (Card) -> Unit,
) : FrameLayout(context) {
    private val ring = FlatView(context)
    private val ringDrawable: LayerDrawable = cardRing(context, radiusDp, thinRing)

    /** Read from the image workers; flips when the card leaves the screen for good. */
    @Volatile
    private var alive = true
    private val content = CardContent(context, tile, widthDp, heightDp, images, radiusDp) { alive }

    var moving = false
        set(value) {
            field = value
            (ringDrawable.getDrawable(1) as GradientDrawable).setStroke(
                context.dp(3),
                if (value) Tokens.accent else Tokens.ring,
            )
            scaleTo(if (value) Tokens.MOVE_SCALE else Tokens.FOCUS_SCALE, Tokens.FOCUS_IN_MS)
        }

    init {
        isFocusable = true
        isClickable = true
        contentDescription = tile.label
        val inset = context.dp(Tokens.RING_INSET)
        val labelHeight = if (showLabel) context.dp(LABEL_DP) else 0
        layoutParams = LinearLayout.LayoutParams(
            context.dp(widthDp) + inset * 2,
            context.dp(heightDp) + inset * 2 + labelHeight,
        )

        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(heightDp)).apply {
            setMargins(inset, inset, inset, 0)
        })
        ring.background = ringDrawable
        ring.alpha = 0f
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(heightDp) + inset * 2))
        if (showLabel) addView(belowLabel(inset, labelHeight))

        setOnFocusChangeListener { _, hasFocus -> animateFocus(hasFocus) }
        setOnClickListener { tile.launch(context) }
        setDimmed(tile.dimmed)
    }

    private fun belowLabel(inset: Int, height: Int) =
        cardLabel(context, tile.label, 13f, Tokens.textMuted).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM).apply {
                leftMargin = inset
                rightMargin = inset
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alive = true
    }

    override fun onDetachedFromWindow() {
        alive = false
        super.onDetachedFromWindow()
    }

    fun setDimmed(dimmed: Boolean) {
        content.alpha = if (dimmed) 0.45f else 1f
    }

    private fun scaleTo(scale: Float, durationMs: Long) {
        animate().scaleX(scale).scaleY(scale).setDuration(durationMs).setInterpolator(Tokens.ease).start()
    }

    private fun animateFocus(hasFocus: Boolean) {
        val duration = if (hasFocus) Tokens.FOCUS_IN_MS else Tokens.FOCUS_OUT_MS
        scaleTo(if (hasFocus) Tokens.FOCUS_SCALE else 1f, duration)
        ring.animate().alpha(if (hasFocus) 1f else 0f)
            .setDuration(duration).setInterpolator(Tokens.ease).start()
        // The focused card draws above its neighbours: rows reorder drawing, and a Z bump covers
        // any other parent (the card has no outline, so this casts no shadow).
        translationZ = if (hasFocus) 1f else 0f
        (parent as? View)?.invalidate()
        if (hasFocus) onFocused(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isConfirmKey(keyCode) && event.repeatCount == 0 && !moving) scaleTo(Tokens.PRESS_SCALE, Tokens.PRESS_MS)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isConfirmKey(keyCode) && hasFocus() && !moving) scaleTo(Tokens.FOCUS_SCALE, Tokens.FOCUS_IN_MS)
        return super.onKeyUp(keyCode, event)
    }

    private fun isConfirmKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private companion object {
        const val LABEL_DP = 24
    }
}

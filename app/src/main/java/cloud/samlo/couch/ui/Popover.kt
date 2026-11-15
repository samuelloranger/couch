package cloud.samlo.couch.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.index.IndexText

/**
 * A small menu beside the view that opened it: an arrow points at that view, rows stack under
 * each other, and only a light dim covers the screen so the anchor stays readable. Up and down
 * walk the rows, OK runs one, Back or Left closes. A dialog window, so it takes the remote while
 * it shows and hands focus back to the anchor when it closes.
 */
@SuppressLint("RtlHardcoded")
class Popover(activity: Activity, anchor: View, private val items: List<Item>) : Dialog(activity) {
    /** One row. [divider] draws a thin line above the row. [warn] tints it with the warning colour. */
    data class Item(
        val icon: Int,
        val label: String,
        val hint: String? = null,
        val warn: Boolean = false,
        val divider: Boolean = false,
        val action: () -> Unit,
    )

    private val rows = items.map { PopoverRow(activity, it) }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = activity.dpf(PANEL_RADIUS)
            setColor(panelFill)
            setStroke(activity.dp(1), panelStroke)
        }
        elevation = activity.dpf(12f)
        setPadding(activity.dp(PANEL_PAD), activity.dp(PANEL_PAD), activity.dp(PANEL_PAD), activity.dp(PANEL_PAD))
    }
    private var selected = 0
    /** Which side of the anchor the panel sits on; the arrow and the Right key follow it. */
    private var onLeft = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        items.forEachIndexed { i, item ->
            if (item.divider) {
                panel.addView(FlatView(activity).apply { background = ColorDrawable(dividerLine) }, LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, activity.dp(1),
                ).apply {
                    topMargin = activity.dp(4)
                    bottomMargin = activity.dp(4)
                    marginStart = activity.dp(6)
                    marginEnd = activity.dp(6)
                })
            }
            panel.addView(rows[i], LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(ROW_H)))
        }
        // The panel's height sets where it sits, so measure it before placing the window.
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(activity.dp(PANEL_W), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        val spot = PopoverPlacement.place(
            anchorLeft = at[0],
            anchorTop = at[1],
            anchorW = anchor.width,
            anchorH = anchor.height,
            screenW = activity.window.decorView.width,
            screenH = activity.window.decorView.height,
            panelW = activity.dp(PANEL_W),
            panelH = panel.measuredHeight,
            gap = activity.dp(GAP),
            margin = activity.dp(MARGIN),
            arrowInset = activity.dp(ARROW_H / 2),
        )
        onLeft = spot.left
        // The panel sits in transparent room for its shadow, and the arrow overhangs the edge
        // facing the anchor: the panel's right edge when the panel is on the left, else its left.
        val frame = FrameLayout(activity)
        frame.addView(ArrowView(activity, arrowLeft = !onLeft), FrameLayout.LayoutParams(activity.dp(ARROW_W), activity.dp(ARROW_H)).apply {
            leftMargin = if (onLeft) activity.dp(SHADOW + PANEL_W) else activity.dp(SHADOW - ARROW_W)
            topMargin = activity.dp(SHADOW) + spot.arrowY - activity.dp(ARROW_H) / 2
        })
        frame.addView(panel, FrameLayout.LayoutParams(activity.dp(PANEL_W), panel.measuredHeight).apply {
            leftMargin = activity.dp(SHADOW)
            topMargin = activity.dp(SHADOW)
        })
        setContentView(frame, LayoutParams(activity.dp(PANEL_W + 2 * SHADOW), panel.measuredHeight + activity.dp(2 * SHADOW)))
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(DIM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // LEFT, not START: the anchor's place on screen decides the side, so the panel must not
            // mirror in a right-to-left language.
            setGravity(Gravity.TOP or Gravity.LEFT)
            attributes = attributes.apply {
                x = spot.panelLeft - activity.dp(SHADOW)
                y = spot.panelTop - activity.dp(SHADOW)
                windowAnimations = 0
            }
        }
        rows.firstOrNull()?.chosen = true
        // It arrives from the anchor's side: a short slide and a fade, over the light dim.
        setOnShowListener {
            frame.alpha = 0f
            frame.translationX = activity.dpf(if (onLeft) SLIDE_DP else -SLIDE_DP)
            frame.animate().alpha(1f).translationX(0f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).start()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(selected - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(selected + 1)
            // On release, so the press's other half doesn't reach the anchor's screen.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!down) choose()
            // Back is the dialog's own: it dismisses before this sees it. Left closes too.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) dismiss()
            // Right leaves a panel on the anchor's left, and is quiet on its right.
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                !onLeft -> return super.dispatchKeyEvent(event)
                down -> dismiss()
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun move(index: Int) {
        if (index !in rows.indices || index == selected) return
        rows[selected].chosen = false
        selected = index
        rows[selected].chosen = true
    }

    private fun choose() {
        val item = items.getOrNull(selected) ?: return
        dismiss()
        item.action()
    }
}

/**
 * Where a popover goes, in the window's pixels: the side it takes, the panel's top left corner,
 * and how far down the arrow sits from the panel's top. Kept apart from the view code so the
 * clamping rules can be tested on a plain JVM.
 */
object PopoverPlacement {
    /** [left] is true when the panel sits left of the anchor. */
    data class Spot(val left: Boolean, val panelLeft: Int, val panelTop: Int, val arrowY: Int)

    fun place(
        anchorLeft: Int,
        anchorTop: Int,
        anchorW: Int,
        anchorH: Int,
        screenW: Int,
        screenH: Int,
        panelW: Int,
        panelH: Int,
        gap: Int,
        margin: Int,
        arrowInset: Int,
    ): Spot {
        val right = anchorLeft + anchorW
        val middle = anchorTop + anchorH / 2
        // Left of the anchor when the right side would push the panel past the margin.
        val left = right + gap + panelW > screenW - margin
        val panelLeft = if (left) anchorLeft - gap - panelW else right + gap
        // The anchor's middle is usually the panel's middle, but the panel never leaves the screen.
        val panelTop = (middle - panelH / 2).coerceIn(margin, maxOf(margin, screenH - panelH - margin))
        // The arrow follows the anchor's middle; clamped, it slides along the panel instead.
        val arrowY = (middle - panelTop).coerceIn(arrowInset, maxOf(arrowInset, panelH - arrowInset))
        return Spot(left, panelLeft, panelTop, arrowY)
    }
}

/** One row: icon, label, and an optional hint at its right end; the dialog owns the keys. */
@SuppressLint("ViewConstructor")
private class PopoverRow(context: Context, private val item: Popover.Item) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = IndexText(context, LABEL_SP, 500, rowText).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // IndexText trims the font's line box, so the label fills the row and centres its text in
        // it: a view that only wraps the trimmed box would cut the taller glyphs.
        gravity = Gravity.CENTER_VERTICAL
    }
    private val hint = IndexText(context, HINT_SP, 500, hintText)

    var chosen = false
        set(value) {
            if (field == value) return
            field = value
            paint()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(ROW_SIDE), 0, context.dp(ROW_SIDE), 0)
        addView(icon, LayoutParams(context.dp(ROW_ICON), context.dp(ROW_ICON)))
        addView(label, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginStart = context.dp(ROW_GAP) })
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(6) })
        // Icon 0 keeps the icon's room, for a row whose text starts where the others' icons do.
        if (item.icon != 0) icon.setImageResource(item.icon)
        label.text = item.label
        hint.text = item.hint.orEmpty()
        hint.visibility = if (item.hint == null) GONE else VISIBLE
        paint()
    }

    private fun paint() {
        val labelTint = when {
            item.warn -> warnText
            chosen -> Color.WHITE
            else -> rowText
        }
        val iconTint = when {
            item.warn -> warnText
            chosen -> Color.WHITE
            else -> Tokens.textMuted
        }
        label.setTextColor(labelTint)
        icon.setColorFilter(iconTint)
        background = if (chosen) ringedRow(context) else null
    }
}

/** A focused row's fill with a thin white ring pulled inside its bounds. */
private fun ringedRow(context: Context): Drawable {
    val stroke = context.dpf(1.5f).toInt()
    val fill = GradientDrawable().apply {
        cornerRadius = context.dpf(ROW_RADIUS)
        setColor(focusedFill)
    }
    val ring = GradientDrawable().apply {
        cornerRadius = context.dpf(ROW_RADIUS)
        setStroke(stroke, Tokens.ring)
    }
    return LayerDrawable(arrayOf(fill, ring)).apply {
        val inset = stroke / 2
        setLayerInset(1, inset, inset, inset, inset)
    }
}

/**
 * The pointer: a 10dp square turned 45°, so its tip reaches past the panel's edge. [arrowLeft]
 * says whether it hangs off the panel's left edge; the view clips the half that belongs under
 * the panel.
 */
@SuppressLint("ViewConstructor")
private class ArrowView(context: Context, private val arrowLeft: Boolean) : View(context) {
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = panelFill
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1f)
        color = panelStroke
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val d = context.dpf(ARROW_HALF)
        val x = if (arrowLeft) width.toFloat() else 0f
        val y = height / 2f
        path.reset()
        path.moveTo(x, y - d)
        path.lineTo(x - d, y)
        path.lineTo(x, y + d)
        path.lineTo(x + d, y)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
    }
}

// A shade darker than the Index surface, so a panel reads as part of the same family.
private val panelFill = Color.parseColor("#1F2624")
private val panelStroke = Color.parseColor("#2E3734")
private val rowText = Color.parseColor("#D5DBD8")
private val hintText = Color.parseColor("#7A8481")
private val focusedFill = Color.parseColor("#2C3532")
private val dividerLine = Color.parseColor("#2C3532")
private val warnText = Color.parseColor("#F59E8B")

/** The panel's width, and the room the arrow's tip needs outside it. */
private const val PANEL_W = 196
private const val PANEL_RADIUS = 10f
private const val PANEL_PAD = 4
private const val ROW_H = 26
private const val ROW_SIDE = 9
private const val ROW_ICON = 12
private const val ROW_GAP = 9
private const val ROW_RADIUS = 6f
private const val LABEL_SP = 11.5f
private const val HINT_SP = 8.5f
/** Between the anchor and the panel. */
private const val GAP = 10
/** Screen edge the panel never crosses. */
private const val MARGIN = 20
/** Transparent room around the panel for its shadow and the arrow. */
private const val SHADOW = 12
private const val ARROW_W = 8
private const val ARROW_H = 17
/** Half the diagonal of a 10dp square. */
private const val ARROW_HALF = 7.1f
private const val SLIDE_DP = 8f
private const val DIM = 0.25f

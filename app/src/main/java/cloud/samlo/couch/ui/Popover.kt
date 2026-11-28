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
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.index.IndexText

/**
 * A small menu beside the view that opened it: an arrow points at that view, rows stack under
 * each other, and a menu keeps a light dim so the anchor stays readable. Up and down walk the
 * rows, OK runs one, Back or Left closes. A dialog window, so it takes the remote while it shows
 * and hands focus back to the anchor when it closes.
 *
 * A [Side.BELOW] panel opens under its anchor instead, like a dropdown, and closes on Up from the
 * first row.
 *
 * It also holds one view of the caller's own instead of rows, and has a [hint] form that never
 * takes the remote and never dims, for a panel that labels what the screen behind it keeps doing.
 */
@SuppressLint("RtlHardcoded")
class Popover private constructor(
    private val activity: Activity,
    anchor: View,
    private val items: List<Item>,
    /** A panel of one view rather than rows; it takes the keys itself. */
    private val content: View?,
    pad: Int,
    width: Int,
    /** False for a hint: it never takes the remote, so the screen behind it keeps the keys. */
    private val takesKeys: Boolean,
    /** Which way the panel leaves the anchor: beside it, or under it for a dropdown. */
    private val side: Side,
) : Dialog(activity) {
    /**
     * One row. [divider] draws a thin line above the row. [warn] tints it with the warning colour.
     * [confirm], on a row that could be a mistake, asks a second OK: the first press shows that text
     * in its place for [CONFIRM_MS].
     */
    data class Item(
        val icon: Int,
        val label: String,
        val hint: String? = null,
        val warn: Boolean = false,
        val divider: Boolean = false,
        val confirm: String? = null,
        val action: () -> Unit,
    )

    /** A menu of rows beside [anchor]: up and down walk them, OK runs one. */
    constructor(activity: Activity, anchor: View, items: List<Item>, width: Int = PANEL_W, side: Side = Side.BESIDE) :
        this(activity, anchor, items, null, PANEL_PAD, width, true, side)

    /** A panel holding [content] beside [anchor]; the content takes every key but Back. */
    constructor(activity: Activity, anchor: View, content: View, width: Int = PANEL_W, pad: Int = PANEL_PAD) :
        this(activity, anchor, emptyList(), content, pad, width, true, Side.BESIDE)

    private val rows = items.map { PopoverRow(activity, it) }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = activity.dpf(PANEL_RADIUS)
            setColor(panelFill)
            setStroke(activity.dp(1), panelStroke)
        }
        elevation = activity.dpf(12f)
    }
    private val arrow = ArrowView(activity)
    private val frame = FrameLayout(activity)
    private val panelW = activity.dp(width)
    private val panelPad = activity.dp(pad)
    private var panelH = 0
    private var selected = 0
    /** Which side of the anchor the panel sits on; the arrow and the Right key follow it. */
    private var onLeft = false
    /** The row waiting for its second OK, and the timer that takes the question back. */
    private var asking = -1
    /** OK went down while the panel showed: the release of the long press that opened it doesn't count. */
    private var pressed = false
    private val main = Handler(Looper.getMainLooper())
    private val unask = Runnable { ask(-1) }
    /** Runs once the window is up: a field can only take focus after that. */
    private var shownHook: (() -> Unit)? = null

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        panel.setPadding(panelPad, panelPad, panelPad, panelPad)
        if (content != null) {
            panel.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
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
        }
        // The panel's height sets where it sits, so measure it before placing the window.
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panelH = panel.measuredHeight
        // The panel sits in transparent room for its shadow, and the arrow overhangs the edge
        // facing the anchor; the frame is big enough for all four edges.
        frame.addView(arrow, FrameLayout.LayoutParams(activity.dp(ARROW_W), activity.dp(ARROW_H)))
        frame.addView(panel, FrameLayout.LayoutParams(panelW, panelH).apply {
            leftMargin = activity.dp(SHADOW)
            topMargin = activity.dp(SHADOW)
        })
        setContentView(frame, LayoutParams(panelW + activity.dp(2 * SHADOW), panelH + activity.dp(2 * SHADOW)))
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            // LEFT, not START: the anchor's place on screen decides the side, so the panel must not
            // mirror in a right-to-left language.
            setGravity(Gravity.TOP or Gravity.LEFT)
            attributes = attributes.apply { windowAnimations = 0 }
            if (takesKeys) {
                setDimAmount(DIM)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            } else {
                // A hint must leave the screen behind it at full brightness: the dialog theme can
                // ask for a dim of its own, so the flag is cleared and not just left unset.
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
        }
        follow(anchor)
        // A hint takes no keys and shows no rows: it only labels what the screen is doing.
        if (takesKeys) {
            rows.firstOrNull()?.chosen = true
            // It arrives from the anchor's side: a short slide and a fade, over the screen.
            setOnShowListener {
                frame.alpha = 0f
                if (side == Side.BELOW) {
                    frame.translationY = -activity.dpf(SLIDE_DP)
                } else {
                    frame.translationX = activity.dpf(if (onLeft) SLIDE_DP else -SLIDE_DP)
                }
                frame.animate().alpha(1f).translationX(0f).translationY(0f)
                    .setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).start()
                shownHook?.invoke()
            }
        }
    }
    /** Puts the panel at [anchor] again, for a row that moved after the panel opened. */
    fun follow(anchor: View) {
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        val spot = PopoverPlacement.place(
            anchorLeft = at[0],
            anchorTop = at[1],
            anchorW = anchor.width,
            anchorH = anchor.height,
            screenW = activity.window.decorView.width,
            screenH = activity.window.decorView.height,
            panelW = panelW,
            panelH = panelH,
            gap = activity.dp(GAP),
            margin = activity.dp(MARGIN),
            arrowInset = activity.dp(ARROW_H / 2),
            side = side,
            anchorX = contentX(anchor, at[0]),
        )
        onLeft = spot.left
        placeArrow(spot)
        window?.apply {
            attributes = attributes.apply {
                x = spot.panelLeft - activity.dp(SHADOW)
                y = spot.panelTop - activity.dp(SHADOW)
            }
        }
    }

    /**
     * The x the arrow aims at. A wide anchor whose text sits at its left (the channel chip runs
     * the whole column) would otherwise take the arrow to its middle, far from the words: a
     * TextView aims at the middle of its first line instead, anything else at its own middle.
     */
    private fun contentX(anchor: View, anchorLeft: Int): Int {
        val text = anchor as? TextView
        val layout = text?.layout
        if (text == null || layout == null || layout.lineCount == 0) return anchorLeft + anchor.width / 2
        val room = (text.width - text.totalPaddingLeft - text.totalPaddingRight).coerceAtLeast(0)
        val lineW = layout.getLineWidth(0).coerceAtMost(room.toFloat())
        // Start-aligned text sits at the padding; centred or right-aligned text moves from there.
        val gravity = Gravity.getAbsoluteGravity(text.gravity, text.layoutDirection) and
            Gravity.HORIZONTAL_GRAVITY_MASK
        val lineLeft = when (gravity) {
            Gravity.CENTER_HORIZONTAL -> (room - lineW) / 2f
            Gravity.RIGHT -> room - lineW
            else -> 0f
        }
        return (anchorLeft + text.totalPaddingLeft + lineLeft + lineW / 2f).toInt()
    }

    /** Puts the arrow on the panel's edge that faces the anchor, sticking out toward it. */
    private fun placeArrow(spot: PopoverPlacement.Spot) {
        val sideways = spot.edge == PopoverPlacement.Edge.LEFT || spot.edge == PopoverPlacement.Edge.RIGHT
        // A side arrow stands tall on the panel's edge; a top or bottom one lies along it.
        val params = FrameLayout.LayoutParams(
            activity.dp(if (sideways) ARROW_W else ARROW_H),
            activity.dp(if (sideways) ARROW_H else ARROW_W),
        )
        if (sideways) {
            params.leftMargin = activity.dp(SHADOW) +
                if (spot.edge == PopoverPlacement.Edge.RIGHT) panelW else -activity.dp(ARROW_W)
            params.topMargin = activity.dp(SHADOW) + spot.arrowY - activity.dp(ARROW_H) / 2
        } else {
            params.leftMargin = activity.dp(SHADOW) + spot.arrowX - activity.dp(ARROW_H) / 2
            params.topMargin = activity.dp(SHADOW) +
                if (spot.edge == PopoverPlacement.Edge.TOP) -activity.dp(ARROW_W) else panelH
        }
        arrow.edge = spot.edge
        arrow.layoutParams = params
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Custom content takes its own keys; Back is the dialog's either way.
        if (content != null) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            // Under the anchor, Up from the first row leaves, as Left does from a side panel.
            KeyEvent.KEYCODE_DPAD_UP -> when {
                !down -> Unit
                side == Side.BELOW && selected == 0 -> dismiss()
                else -> move(selected - 1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(selected + 1)
            // On release, so the press's other half doesn't reach the anchor's screen. A held
            // key's repeats and release follow focus into this window, so only a fresh
            // press arms it.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                down -> if (event.repeatCount == 0) pressed = true
                pressed -> {
                    pressed = false
                    choose()
                }
            }
            // Back is the dialog's own: it dismisses before this sees it. Left closes too.
            // A panel under the anchor has no side to leave through, so Left goes nowhere.
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                side == Side.BELOW -> Unit
                down -> dismiss()
            }
            // Right leaves a panel on the anchor's left, and is quiet on its right.
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                side == Side.BELOW -> Unit
                !onLeft -> return super.dispatchKeyEvent(event)
                down -> dismiss()
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    override fun dismiss() {
        main.removeCallbacks(unask)
        super.dismiss()
    }

    private fun move(index: Int) {
        if (index !in rows.indices || index == selected) return
        ask(-1)
        rows[selected].chosen = false
        selected = index
        rows[selected].chosen = true
    }

    private fun choose() {
        val item = items.getOrNull(selected) ?: return
        // A row that asks twice keeps the panel open on its first OK.
        if (item.confirm != null && asking != selected) {
            ask(selected)
            return
        }
        dismiss()
        item.action()
    }

    /** Makes row [index] ask for a second OK, or with -1 stops asking, for [CONFIRM_MS]. */
    private fun ask(index: Int) {
        main.removeCallbacks(unask)
        relabel(asking, back = true)
        asking = index
        if (index < 0) return
        relabel(index, back = false)
        main.postDelayed(unask, CONFIRM_MS)
    }

    /** Puts row [index]'s question back ([back]) or shows it in place of the row's own label. */
    private fun relabel(index: Int, back: Boolean) {
        val item = items.getOrNull(index) ?: return
        val confirm = item.confirm ?: return
        rows[index].relabel(if (back) item.label else confirm, warn = !back)
    }

    /** Runs [block] once the window is up. */
    private fun onShown(block: () -> Unit) {
        shownHook = block
    }

    companion object {
        /**
         * One-line text entry beside [anchor]. Done or OK saves the trimmed text, Back leaves it as
         * it was. The field keeps focus, so the TV's keyboard types into it and so does the phone.
         */
        fun text(activity: Activity, anchor: View, current: String, hint: String, onDone: (String) -> Unit): Popover {
            val field = TvField(activity).apply {
                setText(current)
                setSelection(text.length)
                this.hint = hint
                setHintTextColor(hintText)
                isSingleLine = true
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, FIELD_SP)
                setTextColor(Tokens.textPrimary)
                typeface = Fonts.hanken(activity, 500)
                background = Focus.ring(activity, FIELD_RADIUS, fieldFill)
                setPadding(activity.dp(FIELD_PAD), 0, activity.dp(FIELD_PAD), 0)
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            lateinit var popover: Popover
            var saved = false
            val save = {
                // Enter can arrive twice on some remotes; the answer must still be saved once.
                if (!saved) {
                    saved = true
                    popover.dismiss()
                    onDone(field.text.toString().trim())
                }
            }
            field.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) save()
                actionId == EditorInfo.IME_ACTION_DONE
            }
            field.setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN) save()
                        true
                    }
                    else -> false
                }
            }
            val note = IndexText(activity, NOTE_SP, 500, hintText).apply { setText(R.string.text_popover_hint) }
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(field, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(FIELD_H)))
                addView(note, note.stack(LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = activity.dp(NOTE_TOP)))
            }
            popover = Popover(activity, anchor, body, PANEL_W, TEXT_PAD)
            // The TV keyboard must not push the screen around; the popover stays where it was placed.
            popover.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
            )
            popover.onShown {
                field.requestFocus()
                field.setSelection(field.text.length)
            }
            return popover
        }

        /** A line beside [anchor] that never takes the remote, for a panel the screen keeps driving. */
        fun hint(activity: Activity, anchor: View, text: CharSequence): Popover {
            val line = IndexText(activity, HINT_SP, 500, hintLine).apply { this.text = text }
            return Popover(activity, anchor, emptyList(), line, HINT_PAD, HINT_W, false, Side.BESIDE)
        }
    }
}

/** Which way a popover leaves its anchor: beside it, as a menu does, or under it, as a dropdown does. */
enum class Side { BESIDE, BELOW }

/**
 * Where a popover goes, in the window's pixels: the side it takes, the panel's top left corner,
 * and where the arrow sits along the panel's edge. Kept apart from the view code so the clamping
 * rules can be tested on a plain JVM.
 */
object PopoverPlacement {
    /** The panel's edge facing the anchor, where the arrow sits. */
    enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

    /** [left] is true when the panel sits left of the anchor. */
    data class Spot(
        val left: Boolean,
        val panelLeft: Int,
        val panelTop: Int,
        val edge: Edge,
        /** Down the panel's side edge, for [Edge.LEFT] and [Edge.RIGHT]. */
        val arrowY: Int = 0,
        /** Along the panel's top or bottom edge, for [Edge.TOP] and [Edge.BOTTOM]. */
        val arrowX: Int = 0,
    )

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
        side: Side = Side.BESIDE,
        /** The screen x the arrow aims at: the anchor's content, or its middle by default. */
        anchorX: Int = anchorLeft + anchorW / 2,
    ): Spot {
        if (side == Side.BELOW) {
            // The panel lines up with the anchor's left edge, but never leaves the margins: a chip
            // near the right edge pulls the panel back instead of running off the screen.
            val panelLeft = anchorLeft.coerceIn(margin, maxOf(margin, screenW - panelW - margin))
            val under = anchorTop + anchorH + gap
            // Above the anchor when the panel would cross the bottom margin, with the arrow below.
            val below = under + panelH <= screenH - margin
            val panelTop = (if (below) under else anchorTop - gap - panelH)
                .coerceIn(margin, maxOf(margin, screenH - panelH - margin))
            // The arrow follows the anchor's content; clamped, it slides along the panel instead.
            val arrowX = (anchorX - panelLeft).coerceIn(arrowInset, maxOf(arrowInset, panelW - arrowInset))
            return Spot(
                left = false,
                panelLeft = panelLeft,
                panelTop = panelTop,
                edge = if (below) Edge.TOP else Edge.BOTTOM,
                arrowX = arrowX,
            )
        }
        val right = anchorLeft + anchorW
        val middle = anchorTop + anchorH / 2
        // Left of the anchor when the right side would push the panel past the margin.
        val left = right + gap + panelW > screenW - margin
        // A wide anchor (a settings row) can leave room on neither side: the panel then sits at the
        // margin and overlaps the anchor's edge, rather than running off the screen.
        val panelLeft = (if (left) anchorLeft - gap - panelW else right + gap)
            .coerceIn(margin, maxOf(margin, screenW - panelW - margin))
        // The anchor's middle is usually the panel's middle, but the panel never leaves the screen.
        val panelTop = (middle - panelH / 2).coerceIn(margin, maxOf(margin, screenH - panelH - margin))
        // The arrow follows the anchor's middle; clamped, it slides along the panel instead.
        val arrowY = (middle - panelTop).coerceIn(arrowInset, maxOf(arrowInset, panelH - arrowInset))
        return Spot(
            left = left,
            panelLeft = panelLeft,
            panelTop = panelTop,
            edge = if (left) Edge.RIGHT else Edge.LEFT,
            arrowY = arrowY,
        )
    }
}

/** One row: icon, label, and an optional hint at its right end; the dialog owns the keys. */
@SuppressLint("ViewConstructor")
private class PopoverRow(context: Context, item: Popover.Item) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = IndexText(context, LABEL_SP, 500, rowText).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // IndexText trims the font's line box, so the label fills the row and centres its text in
        // it: a view that only wraps the trimmed box would cut the taller glyphs.
        gravity = Gravity.CENTER_VERTICAL
    }
    private val hint = IndexText(context, HINT_SP, 500, hintText)
    private var warn = item.warn

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

    /** Shows [text] in place of the row's own label, in the warning colour when [warn]. */
    fun relabel(text: String, warn: Boolean) {
        this.warn = warn
        label.text = text
        paint()
    }

    private fun paint() {
        val labelTint = when {
            warn -> warnText
            chosen -> Color.WHITE
            else -> rowText
        }
        val iconTint = when {
            warn -> warnText
            chosen -> Color.WHITE
            else -> Tokens.textMuted
        }
        label.setTextColor(labelTint)
        icon.setColorFilter(iconTint)
        background = if (chosen) ringedRow(context) else null
    }
}

/** A focused row: the shared plate and accent marker. */
private fun ringedRow(context: Context): Drawable = Focus.plate(context, ROW_RADIUS)

/**
 * The pointer: a 10dp square turned 45°, so its tip reaches past the panel's edge. [edge] is the
 * panel edge it hangs off; the view clips the half that belongs under the panel.
 */
@SuppressLint("ViewConstructor")
private class ArrowView(context: Context) : View(context) {
    /** The panel's edge the arrow hangs off, so it points at the anchor. */
    var edge = PopoverPlacement.Edge.RIGHT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

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
        // The diamond's middle sits on the panel edge the view was laid against, so only the half
        // outside the panel shows, as a triangle pointing at the anchor.
        val x = when (edge) {
            PopoverPlacement.Edge.LEFT -> width.toFloat()
            PopoverPlacement.Edge.RIGHT -> 0f
            else -> width / 2f
        }
        val y = when (edge) {
            PopoverPlacement.Edge.TOP -> height.toFloat()
            PopoverPlacement.Edge.BOTTOM -> 0f
            else -> height / 2f
        }
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
private val hintText = Color.parseColor("#9AA4A0")
private val hintLine = Color.parseColor("#C4CCC9")
private val focusedFill = Color.parseColor("#2C3532")
private val dividerLine = Color.parseColor("#2C3532")
private val warnText = Color.parseColor("#F59E8B")
private val fieldFill = Color.parseColor("#0F1412")

/** The panel's width, and the room the arrow's tip needs outside it. */
private const val PANEL_W = 256
private const val PANEL_RADIUS = 10f
private const val PANEL_PAD = 4
private const val ROW_H = 34
private const val ROW_SIDE = 9
private const val ROW_ICON = 18
private const val ROW_GAP = 9
private const val ROW_RADIUS = 6f
private const val LABEL_SP = Type.LABEL
private const val HINT_SP = Type.CAPTION
/** A hint panel: a line of text, a little room around it. */
private const val HINT_W = 168
private const val HINT_PAD = 8
/** The text-entry panel: a field and the line that says what OK and Back do. */
private const val TEXT_PAD = 8
private const val FIELD_H = 30
private const val FIELD_RADIUS = 7f
private const val FIELD_STROKE = 1.5f
private const val FIELD_SP = Type.LABEL
private const val FIELD_PAD = 8
private const val NOTE_SP = Type.CAPTION
private const val NOTE_TOP = 6
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
/** How long a row that asks twice waits for its second OK. */
private const val CONFIRM_MS = 3_000L

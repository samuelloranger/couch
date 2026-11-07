package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import cloud.samlo.couch.R
import cloud.samlo.couch.hass.HassDevice
import cloud.samlo.couch.ui.Fonts
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dpf

/**
 * One light or plug in the Index's Home Assistant row: a bulb or a plug in a small circle, then its
 * short name over its [status] (a brightness, Off, Offline). A light that is on turns gold, a plug
 * that is on takes the accent, and one Home Assistant reports unavailable dims with an amber dot. Drawn in one view so a state change is a
 * single redraw.
 */
@SuppressLint("ViewConstructor")
class HomePill(context: Context, val entityId: String, private val actions: Actions) : View(context) {
    interface Actions {
        fun press(pill: HomePill)
        fun hold(pill: HomePill)
        fun step(pill: HomePill, direction: Int)
    }

    var label = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }
    /** The second line: what the device is doing, in [statusColor]. */
    var status = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }
    var statusColor = Tokens.textMuted
        set(value) {
            field = value
            invalidate()
        }
    /** Null until Home Assistant has answered once. */
    var device: HassDevice? = null
        set(value) {
            field = value
            invalidate()
        }
    /** False while Home Assistant can't be reached: every pill dims, without the offline dot. */
    var reachable = true
        set(value) {
            field = value
            invalidate()
        }
    /** Holding OK on a dimmable light: left and right change its brightness. */
    var dimming = false
        set(value) {
            field = value
            invalidate()
        }

    private val isLight = entityId.startsWith(HassDevice.LIGHT)
    private val off: Drawable = context.getDrawable(if (isLight) R.drawable.ic_home_bulb else R.drawable.ic_home_plug)!!.mutate()
    private val lit: Drawable = context.getDrawable(if (isLight) R.drawable.ic_home_bulb_lit else R.drawable.ic_home_plug_lit)!!.mutate()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.hanken(context, 500)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.hanken(context, 500)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val rect = RectF()
    private val padStart = context.dpf(4f)
    private val disc = context.dpf(22f)
    private val gap = context.dpf(7f)
    private val padEnd = context.dpf(12f)
    /** Widest the name may draw, in pixels; a longer one ends in an ellipsis. */
    var labelLimit = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    /** The name and status as drawn, cut to [labelLimit]. */
    private var shown: CharSequence = ""
    private var shownStatus: CharSequence = ""
    /** Set when a hold turned into dimming, so its key-up doesn't also switch the light. */
    private var held = false

    init {
        isFocusable = true
        contentDescription = entityId
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val scale = if (gainFocus) Tokens.FOCUS_SCALE else 1f
        animate().scaleX(scale).scaleY(scale)
            .setDuration(if (gainFocus) Tokens.FOCUS_IN_MS else Tokens.FOCUS_OUT_MS).setInterpolator(Tokens.ease).start()
        invalidate()
    }

    /** Width with the whole name and status, whatever [labelLimit] is. */
    fun naturalWidth(): Int = Math.ceil((padStart + disc + gap + padEnd).toDouble()).toInt() + labelWidth()

    /** The wider of the two lines. */
    fun labelWidth(): Int = Math.ceil(maxOf(text.measureText(label), small.measureText(status)).toDouble()).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = labelLimit.toFloat()
        shown = if (text.measureText(label) <= limit) label else TextUtils.ellipsize(label, text, limit, TextUtils.TruncateAt.END)
        shownStatus = if (small.measureText(status) <= limit) status else TextUtils.ellipsize(status, small, limit, TextUtils.TruncateAt.END)
        val lines = maxOf(text.measureText(shown, 0, shown.length), small.measureText(shownStatus, 0, shownStatus.length))
        val width = padStart + disc + gap + lines + padEnd
        setMeasuredDimension(Math.ceil(width.toDouble()).toInt(), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val d = device
        val offline = !reachable || d?.available == false
        val on = !offline && d?.on == true
        val h = height.toFloat()
        val state = when {
            offline -> OFFLINE
            on && isLight -> LIT
            on -> POWERED
            else -> OFF
        }
        rect.set(0f, 0f, width.toFloat(), h)
        fill.color = background(state)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        if (isFocused) {
            val w = context.dpf(2f)
            stroke.strokeWidth = w
            stroke.color = Tokens.ring
            rect.inset(w / 2, w / 2)
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke)
        }

        val cx = padStart + disc / 2
        val cy = h / 2
        fill.color = disc(state)
        canvas.drawCircle(cx, cy, disc / 2, fill)
        val glyph = if (on) lit else off
        val half = context.dpf(7.5f)
        glyph.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        glyph.setTint(icon(state))
        glyph.draw(canvas)

        // The brightness ring, while a dimmable light that is on has focus.
        if (on && d != null && d.dimmable && (isFocused || dimming)) {
            val r = disc / 2 + context.dpf(1.5f)
            stroke.strokeWidth = context.dpf(2f)
            rect.set(cx - r, cy - r, cx + r, cy + r)
            stroke.color = 0x1AFFFFFF
            canvas.drawArc(rect, 0f, 360f, false, stroke)
            stroke.color = GOLD
            canvas.drawArc(rect, -90f, 360f * d.brightness / 100f, false, stroke)
        }
        if (reachable && d?.available == false) {
            val r = context.dpf(3.5f)
            val dx = cx + disc / 2 * 0.72f
            val dy = cy - disc / 2 * 0.72f
            fill.color = OFFLINE_BG
            canvas.drawCircle(dx, dy, r + context.dpf(1.5f), fill)
            fill.color = Tokens.warning
            canvas.drawCircle(dx, dy, r, fill)
        }

        // Name above, status below, the pair centred on the pill.
        val x = padStart + disc + gap
        val nameH = text.descent() - text.ascent()
        val statusH = small.descent() - small.ascent()
        val top = cy - (nameH + statusH - context.dpf(LINE_OVERLAP)) / 2
        text.color = label(state)
        canvas.drawText(shown, 0, shown.length, x, top - text.ascent(), text)
        small.color = statusColor
        canvas.drawText(shownStatus, 0, shownStatus.length, x, top + nameH - context.dpf(LINE_OVERLAP) - small.ascent(), small)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode)) {
            if (event.repeatCount == 0) held = false
            event.startTracking()
            return true
        }
        if (dimming && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            actions.step(this, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isOk(keyCode)) return super.onKeyLongPress(keyCode, event)
        held = true
        actions.hold(this)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode)) {
            if (!held && !event.isCanceled) actions.press(this)
            held = false
            return true
        }
        if (dimming && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun background(state: Int) = when (state) {
        OFFLINE -> OFFLINE_BG
        LIT -> GOLD_BG
        POWERED -> blend(Tokens.surface, Tokens.accent, 0.22f)
        else -> IndexTokens.pill
    }

    private fun disc(state: Int) = when (state) {
        OFFLINE -> 0x0AFFFFFF
        LIT -> 0x4DFFCF7A
        POWERED -> (Tokens.accent and 0x00FFFFFF) or 0x33000000
        else -> 0x0FFFFFFF
    }

    private fun icon(state: Int) = when (state) {
        OFFLINE -> OFFLINE_FG
        LIT -> GOLD
        POWERED -> Tokens.accent
        else -> Tokens.textMuted
    }

    private fun label(state: Int) = when (state) {
        OFFLINE -> OFFLINE_FG
        LIT -> GOLD_LABEL
        POWERED -> Tokens.textPrimary
        else -> IndexTokens.pillText
    }

    private fun isOk(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    companion object {
        /** Hanken's line box is roomy: the two lines sit this much closer than it would put them. */
        private const val LINE_OVERLAP = 4f
        private const val OFF = 0
        private const val LIT = 1
        private const val POWERED = 2
        private const val OFFLINE = 3
        /** A lit bulb's colour, also for its brightness wherever it is written. */
        val GOLD = Color.parseColor("#FFD488")
        private val GOLD_LABEL = Color.parseColor("#FFE2A8")
        private val GOLD_BG = Color.parseColor("#2A2619")
        private val OFFLINE_BG = Color.parseColor("#141917")
        private val OFFLINE_FG = Color.parseColor("#5E6865")

        private fun blend(base: Int, over: Int, amount: Float): Int {
            fun mix(shift: Int) = (((base shr shift) and 0xFF) * (1 - amount) + ((over shr shift) and 0xFF) * amount).toInt()
            return Color.rgb(mix(16), mix(8), mix(0))
        }
    }
}

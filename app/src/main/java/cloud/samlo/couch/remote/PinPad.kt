package cloud.samlo.couch.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.index.IndexText
import cloud.samlo.couch.index.IndexTokens
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * Four digits along the bottom of the screen, for the PIN the TV shows while pairing. The TV's
 * PIN window covers the middle of the screen and any ordinary dialog under it, so this is an
 * accessibility overlay above everything. Up and Down change a digit, Left and Right move, number
 * keys type; OK gives the PIN to [done], Back gives null.
 */
@SuppressLint("ViewConstructor")
class PinPad private constructor(context: Context, private val done: (String?) -> Unit) : FrameLayout(context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val digits = IntArray(LENGTH)
    private var at = 0
    private val boxes = List(LENGTH) { IndexText(context, 34f, 600, Tokens.textPrimary).apply { gravity = Gravity.CENTER } }

    init {
        isFocusable = true
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = context.dp(20)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(12f)
                setColor(Tokens.background and 0x00FFFFFF or 0xF2000000.toInt())
            }
        }
        card.addView(IndexText(context, 15f, 600, Tokens.textPrimary).apply { setText(R.string.pin_title) })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        boxes.forEach { row.addView(it, LinearLayout.LayoutParams(context.dp(56), context.dp(68)).apply { marginStart = context.dp(6); marginEnd = context.dp(6) }) }
        card.addView(row, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) })
        card.addView(IndexText(context, 11f, 500, IndexTokens.hours).apply { setText(R.string.pin_hint) },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) })
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = context.dp(Tokens.OVERSCAN_V + 8)
        })
        draw()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // OK and Back act on release, so the other half of the press never reaches the screen below.
        if (event.action != KeyEvent.ACTION_DOWN) {
            return when (code) {
                KeyEvent.KEYCODE_BACK -> finish(null)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> finish(digits.joinToString(""))
                else -> code in HANDLED
            }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> digits[at] = (digits[at] + 1) % 10
            KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = (digits[at] + 9) % 10
            KeyEvent.KEYCODE_DPAD_LEFT -> at = (at - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> at = (at + 1).coerceAtMost(LENGTH - 1)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                digits[at] = code - KeyEvent.KEYCODE_0
                at = (at + 1).coerceAtMost(LENGTH - 1)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> return true
            else -> return false
        }
        draw()
        return true
    }

    // Digits read the same in every language.
    @SuppressLint("SetTextI18n")
    private fun draw() = boxes.forEachIndexed { i, box ->
        box.text = digits[i].toString()
        box.background = GradientDrawable().apply {
            cornerRadius = context.dpf(8f)
            setColor(if (i == at) Tokens.surfaceFocused else Tokens.surface)
            if (i == at) setStroke(context.dp(2), Tokens.accent)
        }
    }

    /** Closes the pad without an answer, when the service drawing it goes away. */
    fun dismiss() {
        if (isAttachedToWindow) windows.removeView(this)
    }

    private fun finish(pin: String?): Boolean {
        if (!isAttachedToWindow) return true
        windows.removeView(this)
        done(pin)
        return true
    }

    companion object {
        private const val LENGTH = 4
        private val HANDLED = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)

        /** Shows the pad; [context] must be an accessibility service, which may draw above other apps. */
        fun show(context: Context, done: (String?) -> Unit): PinPad {
            val pad = PinPad(context, done)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            context.getSystemService(WindowManager::class.java).addView(pad, params)
            pad.requestFocus()
            return pad
        }
    }
}

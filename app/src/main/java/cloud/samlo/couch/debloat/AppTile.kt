package cloud.samlo.couch.debloat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * One app in the debloat grid: its icon, or its kind drawn as a coloured glyph when it has none,
 * its name, and a tick once picked. Focus only moves render properties (scale, the ring), like
 * the home screen's cards; focusing it puts [helpText] in the [help] pane. A [locked] tile is an
 * app better kept: dimmed, still focusable so its reason can be read, never picked.
 */
@SuppressLint("ViewConstructor")
class AppTile(
    context: Context,
    name: String,
    kind: DebloatList.Why,
    helpText: String,
    help: TextView,
    val locked: Boolean = false,
) : FrameLayout(context) {
    private val icon = ImageView(context)
    private val glyph = TextView(context).apply {
        text = Kinds.glyph(kind)
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(Kinds.color(kind))
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(ICON_RADIUS)
            setColor((Kinds.color(kind) and 0x00FFFFFF) or 0x33000000)
        }
    }
    private val tick = TextView(context).apply {
        text = "✓"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Tokens.background)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Tokens.accent)
        }
        visibility = GONE
    }

    var picked = false
        set(value) {
            field = value
            tick.visibility = if (value) VISIBLE else GONE
        }

    init {
        isFocusable = true
        isClickable = true
        background = tileBackground(context)
        val size = context.dp(ICON)
        addView(glyph, LayoutParams(size, size, Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(10) })
        addView(icon, LayoutParams(size, size, Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(10) })
        addView(TextView(context).apply {
            text = name
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            // Break at spaces: the default balances lines and splits short names mid-word. Lint
            // wants Android 10's LineBreaker name for this value; Layout's is the same 0, since 6.
            @SuppressLint("WrongConstant")
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            setTextColor(Tokens.textPrimary)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(context.dp(4), 0, context.dp(4), context.dp(8))
        })
        addView(tick, LayoutParams(context.dp(20), context.dp(20), Gravity.END).apply { setMargins(0, context.dp(6), context.dp(6), 0) })
        if (locked) for (i in 0 until childCount) getChildAt(i).alpha = LOCKED_ALPHA
        setOnFocusChangeListener { _, focused ->
            if (focused) help.text = helpText
            animate().scaleX(if (focused) SCALE else 1f).scaleY(if (focused) SCALE else 1f)
                .setDuration(if (focused) Tokens.FOCUS_IN_MS else Tokens.FOCUS_OUT_MS)
                .setInterpolator(Tokens.ease).start()
        }
    }

    /** The app's own icon, already rendered at [ICON] dp; the glyph steps aside. */
    fun show(bitmap: Bitmap) {
        icon.setImageBitmap(bitmap)
        glyph.visibility = GONE
    }

    override fun hasOverlappingRendering() = false

    private fun tileBackground(context: Context) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = context.dpf(Tokens.CARD_RADIUS)
            setColor(Tokens.surfaceFocused)
            setStroke(context.dp(2), Tokens.accent)
        })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = context.dpf(Tokens.CARD_RADIUS)
            setColor(Tokens.surface)
        })
    }

    companion object {
        const val ICON = 48
        const val ICON_RADIUS = 12f
        const val WIDTH = 92
        const val HEIGHT = 108
        private const val SCALE = 1.06f
        private const val LOCKED_ALPHA = 0.4f
    }
}

/** How each kind of app looks without an icon: a colour and a glyph every TV font has. */
internal object Kinds {
    fun color(kind: DebloatList.Why): Int = Color.parseColor(
        when (kind) {
            DebloatList.Why.ADS -> "#FBBF24"
            DebloatList.Why.TRACKING -> "#FF6B81"
            DebloatList.Why.DEMO, DebloatList.Why.SETUP -> "#5AB0FF"
            DebloatList.Why.COMMERCIAL -> "#A78BFA"
            DebloatList.Why.SUPPORT, DebloatList.Why.SYSTEM_EXTRAS -> "#94A3B8"
            DebloatList.Why.VENDOR_MEDIA -> "#F472B6"
            DebloatList.Why.BROADCAST, DebloatList.Why.CASTING -> "#2DD4BF"
            DebloatList.Why.VOICE_EXTRAS -> "#60A5FA"
            DebloatList.Why.SCREENSAVER -> "#818CF8"
            DebloatList.Why.GOOGLE_EXTRAS -> "#4ADE80"
            DebloatList.Why.ACCESSIBILITY -> "#FACC15"
            DebloatList.Why.AUTOINSTALL -> "#FB923C"
        },
    )

    fun glyph(kind: DebloatList.Why): String = when (kind) {
        DebloatList.Why.ADS -> "★"
        DebloatList.Why.TRACKING -> "◉"
        DebloatList.Why.DEMO -> "▶"
        DebloatList.Why.SETUP -> "✦"
        DebloatList.Why.COMMERCIAL -> "⌂"
        DebloatList.Why.SUPPORT -> "?"
        DebloatList.Why.SYSTEM_EXTRAS -> "⚙"
        DebloatList.Why.VENDOR_MEDIA -> "♪"
        DebloatList.Why.BROADCAST -> "≋"
        DebloatList.Why.CASTING -> "⇪"
        DebloatList.Why.VOICE_EXTRAS -> "◎"
        DebloatList.Why.SCREENSAVER -> "☾"
        DebloatList.Why.GOOGLE_EXTRAS -> "G"
        DebloatList.Why.ACCESSIBILITY -> "Aa"
        DebloatList.Why.AUTOINSTALL -> "↓"
    }
}

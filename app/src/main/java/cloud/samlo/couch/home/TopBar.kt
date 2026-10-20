package cloud.samlo.couch.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.WeatherNow
import cloud.samlo.couch.ui.InputChip
import cloud.samlo.couch.ui.NowPlaying
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.UpdateChip
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.showLongDate

/**
 * The strip in the top right corner: an input that just turned on, what's playing, an update when
 * there is one, the weather, the settings gear and the clock. Reading order is right to left, so
 * the gear sits between the weather and the clock. The strip is anchored right, so the chips
 * appear without moving the weather, gear or clock.
 */
@SuppressLint("ViewConstructor")
class TopBar(context: Context, onSettings: () -> Unit, onUpdate: () -> Unit) : LinearLayout(context) {
    val nowPlaying = NowPlaying(context)
    val inputOffer = InputChip(context)
    val update = UpdateChip(context).apply { setOnClickListener { onUpdate() } }
    private val weatherBox = LinearLayout(context)
    private val weatherIcon = ImageView(context)
    private val weatherTemp = text(30f, Tokens.textPrimary, "sans-serif-light")
    private val weatherPlace = text(13f, Tokens.textMuted, null)
    private val clockTime = TextClock(context).apply {
        format12Hour = "HH:mm"
        format24Hour = "HH:mm"
        textSize = 34f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setTextColor(Tokens.textPrimary)
        includeFontPadding = false
    }
    private val clockDate = TextClock(context).apply {
        showLongDate()
        textSize = 14f
        setTextColor(Tokens.textMuted)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(inputOffer, LayoutParams(WRAP_CONTENT, context.dp(40)).apply { marginEnd = context.dp(24) })
        addView(nowPlaying, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.dp(24) })
        addView(update, LayoutParams(WRAP_CONTENT, context.dp(40)).apply { marginEnd = context.dp(24) })
        addView(buildWeather(), LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.dp(28) })
        addView(buildGear(onSettings), LayoutParams(context.dp(40), context.dp(40)).apply { marginEnd = context.dp(20) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            addView(clockTime)
            addView(clockDate)
        })
    }

    /** The clock's time format ("HH:mm" or "h:mm") and whether the date shows. */
    fun applyLook(config: Config, timeFormat: String) {
        clockTime.format12Hour = timeFormat
        clockTime.format24Hour = timeFormat
        clockDate.visibility = if (config.showDate) View.VISIBLE else View.GONE
    }

    fun showWeather(now: WeatherNow?) {
        if (now == null) {
            weatherBox.visibility = View.GONE
            return
        }
        weatherIcon.setImageResource(now.icon)
        if (animating) (weatherIcon.drawable as? Animatable)?.start()
        weatherTemp.text = context.getString(R.string.weather_temperature, now.temperature)
        weatherPlace.text = context.getString(R.string.weather_place_range, now.place, now.high, now.low)
        weatherBox.visibility = View.VISIBLE
    }

    private var animating = false

    /** The weather icon moves, like Labby's, while the home screen is showing. */
    fun animateWeather(on: Boolean) {
        animating = on
        (weatherIcon.drawable as? Animatable)?.let { if (on) it.start() else it.stop() }
    }

    private fun buildWeather() = weatherBox.apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(weatherIcon, LayoutParams(context.dp(30), context.dp(30)).apply { marginEnd = context.dp(10) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(weatherTemp)
            addView(weatherPlace)
        })
    }

    private fun buildGear(onSettings: () -> Unit) = ImageView(context).apply {
        setImageResource(R.drawable.ic_settings)
        setColorFilter(Tokens.textPrimary)
        contentDescription = context.getString(R.string.settings)
        val pad = context.dp(9)
        setPadding(pad, pad, pad, pad)
        isFocusable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
                setStroke(context.dp(2), Tokens.ring)
            })
            addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL })
        }
        setOnClickListener { onSettings() }
    }

    private fun text(size: Float, color: Int, family: String?) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (family != null) {
            typeface = Typeface.create(family, Typeface.NORMAL)
            includeFontPadding = false
        }
    }
}

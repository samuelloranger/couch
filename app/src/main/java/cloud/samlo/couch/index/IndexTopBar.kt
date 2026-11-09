package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Animatable
import android.view.Gravity
import android.widget.ImageView
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextClock
import cloud.samlo.couch.R
import cloud.samlo.couch.data.WeatherNow
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Fonts
import cloud.samlo.couch.ui.GodBadge
import cloud.samlo.couch.ui.InputChip
import cloud.samlo.couch.ui.NowPlaying
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.UpdateChip
import cloud.samlo.couch.ui.dp

/**
 * Top right: an input that just turned on, what's playing, an update when there is one, then the
 * weather and the clock, at the top row's height.
 */
@SuppressLint("ViewConstructor")
class IndexTopBar(context: Context, onUpdate: () -> Unit) : LinearLayout(context) {
    val nowPlaying = NowPlaying(context)
    val inputOffer = InputChip(context)
    val update = UpdateChip(context).apply { setOnClickListener { onUpdate() } }
    val god = GodBadge(context, 9.5f)
    private val weather = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val weatherIcon = ImageView(context)
    private val temperature = text(14f, 600, Tokens.textPrimary).apply { letterSpacing = -0.02f }
    private val range = text(8f, 500, IndexTokens.railIdle)
    private val divider = FlatView(context).apply { setBackgroundColor(0xFF2A3330.toInt()) }
    private val clock = TextClock(context).apply {
        typeface = Fonts.hanken(context, 500)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Tokens.textPrimary)
        includeFontPadding = false
        fontFeatureSettings = "tnum"
        maxLines = 1
        format12Hour = CLOCK_12
        format24Hour = CLOCK_24
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(inputOffer, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(26)).apply { marginEnd = context.dp(12) })
        addView(nowPlaying, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(12) })
        addView(update, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(26)).apply { marginEnd = context.dp(12) })
        addView(god, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(12) })
        // Meteocons leave a margin around the drawing; 30dp draws the sky at about 20dp.
        // The icon may overhang the row's height without making the bar taller.
        weather.addView(weatherIcon, LayoutParams(context.dp(30), context.dp(30)).apply {
            marginEnd = context.dp(1)
            topMargin = -context.dp(2)
            bottomMargin = -context.dp(2)
        })
        weather.addView(temperature)
        weather.addView(range, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(5) })
        // The divider belongs to the weather; with no weather the clock stands alone.
        weather.addView(divider, LayoutParams(context.dp(1), context.dp(13)).apply {
            marginStart = context.dp(8)
            marginEnd = context.dp(8)
        })
        addView(weather, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** 24 hour or 12 hour, as set in Customize. */
    fun setClock24h(on: Boolean) {
        val format = if (on) CLOCK_24 else CLOCK_12
        clock.format12Hour = format
        clock.format24Hour = format
    }

    fun showWeather(now: WeatherNow?) {
        if (now == null) {
            weather.visibility = GONE
            return
        }
        weatherIcon.setImageResource(now.icon)
        if (animating) (weatherIcon.drawable as? Animatable)?.start()
        temperature.text = context.getString(R.string.weather_temperature, now.temperature)
        range.text = context.getString(R.string.index_range, now.high, now.low)
        weather.visibility = VISIBLE
    }

    private fun text(sizeSp: Float, weight: Int, color: Int, lineHeight: Float = 1f) =
        IndexText(context, sizeSp, weight, color, lineHeight).apply { maxLines = 1 }

    private var animating = false

    /** The weather icon moves, like Labby's, while the home screen is showing. */
    fun animateWeather(on: Boolean) {
        animating = on
        (weatherIcon.drawable as? Animatable)?.let { if (on) it.start() else it.stop() }
    }

    private companion object {
        const val CLOCK_24 = "HH:mm"
        const val CLOCK_12 = "h:mm"
    }
}

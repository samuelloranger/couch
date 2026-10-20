package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.WeatherNow
import cloud.samlo.couch.ui.InputChip
import cloud.samlo.couch.ui.NowPlaying
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.UpdateChip
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * Top right: an input that just turned on, what's playing, an update when there is one, the
 * weather, then Inputs and Settings.
 */
@SuppressLint("ViewConstructor")
class IndexTopBar(context: Context, onInputs: () -> Unit, onSettings: () -> Unit, onUpdate: () -> Unit) : LinearLayout(context) {
    val nowPlaying = NowPlaying(context)
    val inputOffer = InputChip(context)
    val update = UpdateChip(context).apply { setOnClickListener { onUpdate() } }
    val inputs = pill(R.drawable.ic_pill_inputs, R.string.section_inputs, onInputs)
    val settings = pill(R.drawable.ic_pill_settings, R.string.settings, onSettings)
    private val weather = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val weatherIcon = ImageView(context)
    private val temperature = text(20f, 600, Tokens.textPrimary).apply { letterSpacing = -0.02f }
    private val place = text(9.5f, 500, Tokens.textMuted, 1.25f).apply { maxLines = 2 }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(inputOffer, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(26)).apply { marginEnd = context.dp(15) })
        addView(nowPlaying, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(15) })
        addView(update, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(26)).apply { marginEnd = context.dp(15) })
        // Meteocons leave a margin around the drawing; 30dp draws the sky at about 20dp.
        // The icon may overhang the pills' height without making the bar taller.
        weather.addView(weatherIcon, LayoutParams(context.dp(30), context.dp(30)).apply {
            marginEnd = context.dp(2)
            topMargin = -context.dp(2)
            bottomMargin = -context.dp(2)
        })
        weather.addView(temperature)
        weather.addView(place, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(7) })
        addView(weather, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(15) })
        addView(inputs, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(26)).apply { marginEnd = context.dp(6) })
        addView(settings, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(26)))
    }

    fun showWeather(now: WeatherNow?) {
        if (now == null) {
            weather.visibility = GONE
            return
        }
        weatherIcon.setImageResource(now.icon)
        if (animating) (weatherIcon.drawable as? Animatable)?.start()
        temperature.text = context.getString(R.string.weather_temperature, now.temperature)
        place.text = context.getString(R.string.index_place_range, now.place, now.high, now.low)
        weather.visibility = VISIBLE
    }

    private fun pill(icon: Int, label: Int, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(11), 0, context.dp(11), 0)
        isFocusable = true
        isClickable = true
        contentDescription = context.getString(label)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0x33FFFFFF, Tokens.ring))
            addState(intArrayOf(), shape(IndexTokens.pill, null))
        }
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(IndexTokens.pillText)
        }, LayoutParams(context.dp(12), context.dp(12)).apply { marginEnd = context.dp(5) })
        addView(text(11f, 500, IndexTokens.pillText).apply { text = context.getString(label) })
        setOnClickListener { onClick() }
    }

    private fun shape(color: Int, stroke: Int?) = GradientDrawable().apply {
        cornerRadius = context.dpf(13f)
        setColor(color)
        stroke?.let { setStroke(context.dp(2), it) }
    }

    private fun text(sizeSp: Float, weight: Int, color: Int, lineHeight: Float = 1f) =
        IndexText(context, sizeSp, weight, color, lineHeight).apply { maxLines = 1 }

    private var animating = false

    /** The weather icon moves, like Labby's, while the home screen is showing. */
    fun animateWeather(on: Boolean) {
        animating = on
        (weatherIcon.drawable as? Animatable)?.let { if (on) it.start() else it.stop() }
    }

    /** Hidden when the TV has no inputs to switch to. */
    fun setHasInputs(has: Boolean) {
        inputs.visibility = if (has) View.VISIBLE else View.GONE
    }
}

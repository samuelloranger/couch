package cloud.samlo.couch.ui

import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import cloud.samlo.couch.data.Images

/**
 * Ambient mode: the wallpaper full screen with a slow drift, a large clock and the weather.
 * Pictures rotate every 30 seconds when the source has several. Only render properties move
 * (scale and alpha), so it stays cheap on a weak GPU.
 */
@SuppressLint("ViewConstructor")
class Ambient(context: Context, private val images: Images) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val below = picture()
    private val above = picture().apply { alpha = 0f }
    private val belowDrift = drift(below)
    private val aboveDrift = drift(above)
    private val weather = TextView(context).apply {
        textSize = 20f
        setTextColor(0xE6FFFFFF.toInt())
        setShadowLayer(12f, 0f, 2f, 0x99000000.toInt())
    }
    private val time = TextClock(context).apply {
        textSize = 96f
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        setTextColor(0xFFFFFFFF.toInt())
        includeFontPadding = false
        setShadowLayer(18f, 0f, 2f, 0x99000000.toInt())
    }
    private val date = TextClock(context).apply {
        textSize = 22f
        setTextColor(0xE6FFFFFF.toInt())
        showLongDate()
        setShadowLayer(12f, 0f, 2f, 0x99000000.toInt())
    }

    private var urls: List<String> = emptyList()
    private var session = 0L

    /** Optional local rescan; the provider must return its result on the main thread. */
    var reloadPictures: (((List<String>) -> Unit) -> Unit)? = null

    @Volatile
    private var wanted: String? = null

    private val rotate = object : Runnable {
        override fun run() {
            val mine = session
            val reload = reloadPictures
            if (reload == null) {
                advance(urls)
            } else {
                reload { pictures ->
                    // A scan finishing after wake-up must not bring the overlay or timer back.
                    if (mine == session && showing) advance(pictures)
                }
            }
        }
    }

    private fun advance(pictures: List<String>) {
        urls = pictures
        nextAmbientPicture(pictures, wanted)?.takeIf { it != wanted }?.let { showPicture(it) }
        main.postDelayed(rotate, ROTATE_MS)
    }

    val showing: Boolean get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(0xFF000000.toInt())
        visibility = View.GONE
        alpha = 0f
        addView(below, fill())
        addView(above, fill())
        addView(FlatView(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(0xB3000000.toInt(), 0x33000000, 0x00000000),
            )
        }, fill())
        addView(clockColumn(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = context.dp(Tokens.OVERSCAN_H + 8)
            bottomMargin = context.dp(Tokens.OVERSCAN_V + 16)
        })
    }

    /** Full width: TextClock text changes after the first measure, and a wrap_content width
     *  measured while hidden cuts the time off. */
    private fun clockColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(time, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(date, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(weather, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
        })
    }

    fun setTimeFormat(format: String) {
        time.format12Hour = format
        time.format24Hour = format
    }

    fun show(pictures: List<String>, weatherLine: String?) {
        session++
        urls = pictures
        weather.text = weatherLine.orEmpty()
        weather.visibility = if (weatherLine.isNullOrEmpty()) View.GONE else View.VISIBLE
        visibility = View.VISIBLE
        animate().alpha(1f).setDuration(800).setInterpolator(Tokens.ease).start()
        val index = if (urls.isEmpty()) 0 else (System.currentTimeMillis() / ROTATE_MS % urls.size).toInt()
        urls.getOrNull(index)?.let { showPicture(it) }
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, ROTATE_MS)
    }

    fun hide() {
        session++
        main.removeCallbacks(rotate)
        wanted = null
        val mine = session
        animate().alpha(0f).setDuration(220).setInterpolator(Tokens.ease).withEndAction {
            visibility = View.GONE
            below.animate().cancel()
            above.animate().cancel()
            belowDrift.cancel()
            aboveDrift.cancel()
            // A show() that raced in owns the pictures now.
            if (session == mine) {
                below.setImageDrawable(null)
                above.setImageDrawable(null)
            }
        }.start()
    }

    private fun showPicture(url: String) {
        wanted = url
        images.load(url, 1920, 1080, 0f, opaque = true, isCurrent = { wanted == url }) { bitmap ->
            crossfade(bitmap)
        }
    }

    private fun crossfade(bitmap: Bitmap) {
        val incoming = if (above.alpha > 0.5f) below else above
        val zoom = if (incoming === above) aboveDrift else belowDrift
        zoom.cancel()
        incoming.setImageBitmap(bitmap)
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        // Separate from the alpha animator: a crossfade must never shorten the zoom.
        // Reverse gently for a single wallpaper instead of snapping back to its initial scale.
        zoom.start()
        above.animate().alpha(if (incoming === above) 1f else 0f).setDuration(1500).setInterpolator(Tokens.ease).start()
    }

    private fun picture() = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    private fun drift(view: ImageView) = ObjectAnimator.ofPropertyValuesHolder(
        view,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, DRIFT),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, DRIFT),
    ).apply {
        duration = DRIFT_MS
        interpolator = AccelerateDecelerateInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
    }

    private companion object {
        const val ROTATE_MS = 30_000L
        // Slower than the rotation, so each picture zooms gently.
        const val DRIFT_MS = 120_000L
        const val DRIFT = 1.08f
    }
}

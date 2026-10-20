package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.fill
import kotlin.math.roundToInt

/**
 * The focused program's art, faint, behind the right column. It only changes once focus rests.
 *
 * The darkening and the scrim (solid toward the list, fading out at the bottom) are painted into
 * the art when it's decoded, so the screen draws one opaque image: translucent layers over it
 * kept a TV GPU busy for 15 ms a frame. Like the rows layout's hero, the new image fades in over
 * the old one. Decoded at half the drawn size, which the darkening hides.
 */
@SuppressLint("ViewConstructor")
class Backdrop(context: Context, private val images: Images) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val below = image()
    private val above = image().apply { alpha = 0f }

    @Volatile
    private var pendingUrl: String? = null
    private var shownUrl: String? = null
    private val settle = Runnable { load() }

    init {
        addView(below, fill())
        addView(above, fill())
    }

    fun show(url: String?) {
        main.removeCallbacks(settle)
        pendingUrl = url
        if (url == shownUrl) return
        if (url == null) crossfade(null) else main.postDelayed(settle, Tokens.HERO_SETTLE_MS)
    }

    private fun load() {
        val url = pendingUrl ?: return
        val w = context.dp(IndexTokens.BACKDROP_W) / 2
        val h = context.dp(540) / 2
        images.load(url, w, h, 0f, opaque = true, decorate = ::shade, decorateKey = "backdrop", isCurrent = { pendingUrl == url }) {
            shownUrl = url
            crossfade(it)
        }
    }

    /** Puts [bitmap] (or nothing) on screen: under the top view as it fades out, or on top fading in. */
    private fun crossfade(bitmap: Bitmap?) {
        if (bitmap == null) shownUrl = null
        above.animate().cancel()
        if (bitmap == null && above.alpha <= 0.5f) {
            // Fading to nothing: lift what's showing to the top view so it can fade away.
            above.setImageDrawable(below.drawable)
            above.alpha = 1f
            below.setImageDrawable(null)
        }
        if (above.alpha > 0.5f) {
            below.setImageBitmap(bitmap)
            above.animate().alpha(0f).setDuration(Tokens.HERO_FADE_MS).setInterpolator(Tokens.ease).start()
        } else {
            above.setImageBitmap(bitmap)
            above.animate().alpha(1f).setDuration(Tokens.HERO_FADE_MS).setInterpolator(Tokens.ease).start()
        }
    }

    private fun image() = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    /**
     * The design's backdrop at [IndexTokens.BACKDROP_ALPHA] over the background, under its scrim:
     * across the screen solid until 30% of the width, 60% at 60%, 35% at the right edge; down,
     * clear until half the height, then solid at the bottom. This view starts at x = 260dp.
     */
    private fun shade(canvas: Canvas, w: Int, h: Int) {
        val bg = Tokens.background and 0x00FFFFFF
        canvas.drawColor(bg or (((1f - IndexTokens.BACKDROP_ALPHA) * 255).roundToInt() shl 24))
        val screen = SCREEN_W.toFloat()
        val left = screen - IndexTokens.BACKDROP_W
        val at = { x: Float -> (x - left) / IndexTokens.BACKDROP_W }
        val paint = Paint()
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(bg or SOLID, bg or SOLID, bg or 0x99000000.toInt(), bg or 0x59000000),
            floatArrayOf(0f, at(screen * 0.3f), at(screen * 0.6f), 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = LinearGradient(0f, h / 2f, 0f, h.toFloat(), bg, bg or SOLID, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h / 2f, w.toFloat(), h.toFloat(), paint)
    }

    private companion object {
        const val SCREEN_W = 960
        const val SOLID = 0xFF000000.toInt()
    }
}

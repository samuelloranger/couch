package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The panel at the top of the right column. With a channel: the focused program's art at its own
 * shape on the left, its source, title, synopsis and progress beside it. Without one: the app's
 * banner. Text changes at once; the art loads once focus rests, like the hero in the rows layout.
 */
@SuppressLint("ViewConstructor")
class Spotlight(context: Context, private val images: Images) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val caption = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(context.dp(22), context.dp(23), context.dp(22), context.dp(23))
    }
    private val source = text(10f, 600, Tokens.accent, 1f).apply { maxLines = 2 }
    private val title = text(21f, 700, Tokens.textPrimary, 1.1f).apply {
        maxLines = 3
        letterSpacing = -0.02f
    }
    private val description = text(10.5f, 400, IndexTokens.description, 1.45f).apply { maxLines = 4 }
    private val progressFill = GradientDrawable().apply { cornerRadius = context.dpf(1.5f) }
    private val progress = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(1.5f)
            setColor(0x26FFFFFF)
        }
        addView(View(context).apply { background = progressFill }, LayoutParams(0, LayoutParams.MATCH_PARENT))
    }
    private val bannerBox = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = context.dpf(250f)
            colors = intArrayOf(Tokens.surfaceFocused, Tokens.surface)
            cornerRadius = context.dpf(IndexTokens.PANEL_RADIUS)
        }
    }
    // The banner bitmap comes pre-rounded. No shadow: elevation re-renders every frame on TV GPUs.
    private val banner = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }

    @Volatile
    private var pendingUrl: String? = null
    private var shownUrl: String? = null
    private var artW = 0
    private val settle = Runnable { loadArt() }

    init {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(IndexTokens.PANEL_RADIUS)
            setColor(Tokens.surface)
        }
        // No clipToOutline: clipping to a rounded outline tripled the GPU time per frame on a
        // MediaTek TV. The panel, the banner box and the art each draw their own rounded corners.

        caption.addView(source, source.stack(LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)))
        caption.addView(title, title.stack(LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT), top = context.dp(6)))
        caption.addView(description, description.stack(LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT), top = context.dp(6)))
        caption.addView(progress, LinearLayout.LayoutParams(context.dp(150), context.dp(3)).apply { topMargin = context.dp(8) })
        addView(art, LayoutParams(0, LayoutParams.MATCH_PARENT))
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bannerBox.addView(banner, LayoutParams(context.dp(BANNER_W), context.dp(BANNER_H), Gravity.CENTER))
        addView(bannerBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** A program from [sourceLabel]'s channel. */
    fun showProgram(sourceLabel: String, tile: Tile) {
        bannerBox.visibility = GONE
        val shape = IndexTokens.shape(tile.imageRatio)
        val w = context.dp(Math.round(IndexTokens.PANEL_H * shape))
        if (w != artW) {
            artW = w
            (art.layoutParams as LayoutParams).width = w
            (caption.layoutParams as LayoutParams).apply {
                leftMargin = w
                width = context.dp(IndexTokens.PANEL_W) - w
            }
            // A narrower caption can need more lines: make every line of text measure again
            // instead of reusing what it measured at this width for an earlier program.
            for (i in 0 until caption.childCount) caption.getChildAt(i).forceLayout()
            art.requestLayout()
            caption.requestLayout()
        }
        source.text = sourceLabel
        title.text = tile.label
        val text = tile.description?.takeIf { it.isNotBlank() && it != tile.label }
        description.text = text.orEmpty()
        description.visibility = if (text == null) GONE else VISIBLE
        if (tile.progress >= 0f) {
            progress.visibility = VISIBLE
            progress.getChildAt(0).layoutParams.width = (context.dp(150) * tile.progress).toInt()
            progressFill.setColor(Tokens.accent)
            progress.getChildAt(0).requestLayout()
        } else {
            progress.visibility = GONE
        }
        // Wide art uses the program's landscape image; posters and squares their own.
        val url = if (shape > 1.5f) tile.backdropUrl ?: tile.imageUrl else tile.imageUrl ?: tile.backdropUrl
        requestArt(url)
    }

    /** An app without a channel: its banner, centred. */
    fun showApp(tile: Tile) {
        bannerBox.visibility = VISIBLE
        banner.setImageBitmap(tile.banner)
        requestArt(null)
    }

    private fun requestArt(url: String?) {
        main.removeCallbacks(settle)
        if (url == shownUrl) return
        pendingUrl = url
        art.animate().cancel()
        art.alpha = 0f
        shownUrl = null
        if (url != null) main.postDelayed(settle, ART_SETTLE_MS)
    }

    private fun loadArt() {
        val url = pendingUrl ?: return
        val h = context.dp(IndexTokens.PANEL_H)
        images.load(url, artW, h, context.dpf(IndexTokens.PANEL_RADIUS), leftCorners = true, isCurrent = { pendingUrl == url }) {
            shownUrl = url
            art.setImageBitmap(it)
            art.animate().alpha(1f).setDuration(ART_FADE_MS).setInterpolator(Tokens.ease).start()
        }
    }

    private fun text(sizeSp: Float, weight: Int, color: Int, lineHeight: Float) =
        IndexText(context, sizeSp, weight, color, lineHeight).apply { ellipsize = TextUtils.TruncateAt.END }

    companion object {
        const val BANNER_W = 260
        const val BANNER_H = 146
        const val BANNER_RADIUS = 7f
        private const val ART_SETTLE_MS = 200L
        private const val ART_FADE_MS = 200L
    }
}

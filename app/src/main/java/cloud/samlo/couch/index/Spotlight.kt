package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The head of the right column, in text only: the focused program's source, title, synopsis and
 * progress, or the app's name and why it has nothing to show. The pictures are the channel's
 * cards under it, so moving along them decodes no larger copy of their art.
 */
@SuppressLint("ViewConstructor")
class Spotlight(context: Context) : LinearLayout(context) {
    private val source = text(12f, 600, Tokens.accent, 1f).apply { maxLines = 1 }
    private val title = text(30f, 600, Tokens.textPrimary, 1.1f).apply {
        maxLines = 1
        letterSpacing = -0.02f
    }
    private val description = text(15f, 400, IndexTokens.description, 1.45f).apply { maxLines = 2 }
    private val progressFill = GradientDrawable().apply { cornerRadius = context.dpf(1.5f) }
    private val progress = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(1.5f)
            setColor(0x26FFFFFF)
        }
        addView(View(context).apply { background = progressFill }, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    init {
        orientation = VERTICAL
        addView(source, source.stack(LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)))
        addView(title, title.stack(LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT), top = context.dp(6)))
        addView(description, description.stack(LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT), top = context.dp(10)))
        addView(progress, LayoutParams(context.dp(PROGRESS_W), context.dp(3)).apply { topMargin = context.dp(12) })
    }

    /** A program from [sourceLabel]'s channel. */
    fun showProgram(sourceLabel: String, tile: Tile) {
        source.visibility = VISIBLE
        // The accent can change in Customize after this view was built.
        source.setTextColor(Tokens.accent)
        source.text = sourceLabel
        title.text = tile.label
        setDescription(tile.description?.takeIf { it.isNotBlank() && it != tile.label })
        if (tile.progress >= 0f) {
            progress.visibility = VISIBLE
            progress.getChildAt(0).layoutParams.width = (context.dp(PROGRESS_W) * tile.progress).toInt()
            progressFill.setColor(Tokens.accent)
            progress.getChildAt(0).requestLayout()
        } else {
            progress.visibility = GONE
        }
    }

    /** An app without a channel: its name, and [note] on what OK does. */
    fun showApp(tile: Tile, note: String) {
        // Invisible, not gone, so the title stays where a program's title sits.
        source.visibility = INVISIBLE
        title.text = tile.label
        setDescription(note)
        progress.visibility = GONE
    }

    private fun setDescription(text: String?) {
        description.text = text.orEmpty()
        description.visibility = if (text == null) GONE else VISIBLE
    }

    private fun text(sizeSp: Float, weight: Int, color: Int, lineHeight: Float) =
        IndexText(context, sizeSp, weight, color, lineHeight).apply { ellipsize = TextUtils.TruncateAt.END }

    private companion object {
        const val PROGRESS_W = 150
    }
}

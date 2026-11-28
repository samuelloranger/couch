package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.ui.Focus
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.Type
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The text of the right column, in text only. Over a channel's cards it is the channel line with the
 * place in the row, and under the cards the position dots, the focused program's title, synopsis
 * and the key that opens it, with its progress. Above a folder's tiles it is the folder's count,
 * name and help instead. The pictures are the channel's cards, so moving along them decodes no
 * larger copy of their art.
 *
 * Everything sits at a fixed place in a fixed-size view, so a focus move never triggers a layout
 * pass; only switching between the two arrangements moves anything.
 */
@SuppressLint("ViewConstructor")
class Spotlight(context: Context) : FrameLayout(context) {
    val channelButton = text(Type.CAPTION, 600, Tokens.accent, 1f).apply {
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        // The focus box is wider than the words; the margin puts the words back on the column's edge.
        setPadding(context.dp(CHIP_PAD), 0, context.dp(CHIP_PAD), 0)
    }
    /** "3 / 12": where the focused card sits in its channel. */
    private val counter = text(Type.CAPTION, 500, IndexTokens.hours, 1f).apply {
        maxLines = 1
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        fontFeatureSettings = "tnum"
    }
    private val dots = Dots(context)
    private val title = text(Type.TITLE, 600, Tokens.textPrimary, 1.1f).apply {
        maxLines = 1
        letterSpacing = -0.02f
    }
    private val description = text(Type.LABEL, 400, IndexTokens.description, 1.45f).apply { maxLines = 2 }
    private val keyCap = text(Type.CAPTION, 600, IndexTokens.pillText, 1f).apply {
        text = context.getString(R.string.index_key_ok)
        gravity = Gravity.CENTER
        setPadding(context.dp(6), 0, context.dp(6), 0)
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(4f)
            setStroke(context.dp(1), Tokens.graphicIdle)
        }
    }
    private val hintText = text(Type.CAPTION, 500, IndexTokens.hours, 1f)
    private val hint = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(keyCap, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(KEY_H)))
        addView(hintText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) })
    }
    private val progressFill = GradientDrawable().apply { cornerRadius = context.dpf(1.5f) }
    private val progress = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(1.5f)
            setColor(0x26FFFFFF)
        }
        addView(View(context).apply { background = progressFill }, LayoutParams(0, LayoutParams.MATCH_PARENT))
    }
    private var channelLook: Pair<Boolean, Int>? = null
    /** Which arrangement the views are placed for: the text under the cards, or above a folder's tiles. */
    private var underCards = true

    init {
        addView(channelButton, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(IndexTokens.CHANNEL_H)).apply {
            marginStart = -context.dp(CHIP_PAD)
        })
        addView(counter, LayoutParams(context.dp(COUNTER_W), context.dp(IndexTokens.CHANNEL_H), Gravity.END))
        addView(dots, LayoutParams(Dots.MAX_W.let { context.dp(it) }, context.dp(Dots.DOT)).apply { topMargin = at(IndexTokens.DOTS_Y) })
        addView(title, title.stack(LayoutParams(LayoutParams.MATCH_PARENT, context.dp(TITLE_H)), top = at(IndexTokens.TITLE_Y)))
        addView(description, description.stack(LayoutParams(LayoutParams.MATCH_PARENT, context.dp(DESC_H)), top = at(IndexTokens.DESC_Y)))
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(KEY_H)).apply { topMargin = at(IndexTokens.HINT_Y) })
        addView(progress, LayoutParams(context.dp(PROGRESS_W), context.dp(3)).apply { topMargin = at(IndexTokens.PROGRESS_Y) })
    }

    /** [screenY] as a margin inside this view, which starts at the header. */
    private fun at(screenY: Int) = context.dp(screenY - IndexTokens.HEADER_Y)

    /**
     * Program number [position] of [total] from [sourceLabel]'s channel; [appLabel] is the app OK
     * opens it in.
     */
    fun showProgram(sourceLabel: String, appLabel: String, tile: Tile, position: Int, total: Int, canChooseChannel: Boolean) {
        arrange(underCards = true)
        channelButton.visibility = VISIBLE
        // The accent can change in Customize after this view was built.
        channelButton.setTextColor(Tokens.accent)
        channelButton.text = if (canChooseChannel) "‹  $sourceLabel  ›" else sourceLabel
        channelButton.contentDescription = if (canChooseChannel) context.getString(R.string.choose_channel_label, sourceLabel) else sourceLabel
        channelButton.isFocusable = canChooseChannel
        channelButton.isClickable = canChooseChannel
        val look = canChooseChannel to Tokens.accent
        if (channelLook != look) {
            channelLook = look
            channelButton.background = if (canChooseChannel) StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Focus.ring(context, 6f, Tokens.surfaceFocused))
                addState(intArrayOf(), ColorDrawable(android.graphics.Color.TRANSPARENT))
            } else null
        }
        // One card has no place to speak of.
        val several = total > 1
        counter.visibility = if (several) VISIBLE else GONE
        dots.visibility = if (several) VISIBLE else GONE
        if (several) {
            counter.text = context.getString(R.string.index_position, position + 1, total)
            dots.show(position, total)
        }
        title.text = tile.label
        setDescription(tile.description?.takeIf { it.isNotBlank() && it != tile.label })
        hint.visibility = VISIBLE
        val open = context.getString(R.string.index_open_in, appLabel)
        if (hintText.text != open) hintText.text = open
        if (tile.progress >= 0f) {
            progress.visibility = VISIBLE
            progress.getChildAt(0).layoutParams.width = (context.dp(PROGRESS_W) * tile.progress).toInt()
            progressFill.setColor(Tokens.accent)
            progress.getChildAt(0).requestLayout()
        } else {
            progress.visibility = GONE
        }
    }

    /** A row with nothing to show (Continue watching, empty): its name, and [note] on what OK does. */
    fun showApp(tile: Tile, note: String) {
        arrange(underCards = false)
        // Invisible, not gone, so the title stays where a folder's title sits.
        channelButton.visibility = INVISIBLE
        channelButton.isFocusable = false
        channelButton.isClickable = false
        counter.visibility = GONE
        dots.visibility = GONE
        hint.visibility = GONE
        title.text = tile.label
        setDescription(note)
        progress.visibility = GONE
    }

    /** A folder: its app count where a program's channel sits, its name, and what the keys do. */
    fun showFolder(name: String, apps: Int) {
        arrange(underCards = false)
        // The line above a title is not a button here: a folder has no channel to choose.
        channelLook = null
        channelButton.visibility = VISIBLE
        channelButton.setTextColor(Tokens.accent)
        channelButton.text = context.resources.getQuantityString(R.plurals.folder_spotlight, apps, apps)
        channelButton.contentDescription = null
        channelButton.isFocusable = false
        channelButton.isClickable = false
        channelButton.background = null
        counter.visibility = GONE
        dots.visibility = GONE
        hint.visibility = GONE
        title.text = name
        setDescription(context.getString(R.string.folder_spotlight_help))
        progress.visibility = GONE
    }

    /** Moves the title and its synopsis between under the cards and above a folder's tiles. */
    private fun arrange(underCards: Boolean) {
        if (this.underCards == underCards) return
        this.underCards = underCards
        (title.layoutParams as MarginLayoutParams).topMargin =
            at(if (underCards) IndexTokens.TITLE_Y else IndexTokens.FOLDER_TITLE_Y) - title.inset
        (description.layoutParams as MarginLayoutParams).topMargin =
            at(if (underCards) IndexTokens.DESC_Y else IndexTokens.FOLDER_DESC_Y) - description.inset
        requestLayout()
    }

    private fun setDescription(text: String?) {
        description.text = text.orEmpty()
        description.visibility = if (text == null) GONE else VISIBLE
    }

    private fun text(sizeSp: Float, weight: Int, color: Int, lineHeight: Float) =
        IndexText(context, sizeSp, weight, color, lineHeight).apply { ellipsize = TextUtils.TruncateAt.END }

    private companion object {
        const val PROGRESS_W = 150
        const val CHIP_PAD = 8
        const val COUNTER_W = 72
        const val TITLE_H = 30
        const val DESC_H = 42
        const val KEY_H = 18
    }
}

/** One dot per card (at most [MAX]), the focused card's in the accent: where you are in the row, at a glance. */
@SuppressLint("ViewConstructor")
private class Dots(context: Context) : View(context) {
    private var count = 0
    private var at = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(position: Int, total: Int) {
        count = minOf(total, MAX)
        // A longer row spreads over the dots it has: the counter above gives the exact place.
        at = if (total <= MAX) position else Math.round(position * (MAX - 1f) / (total - 1))
        invalidate()
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        val r = context.dpf(DOT / 2f)
        for (i in 0 until count) {
            paint.color = if (i == at) Tokens.accent else Tokens.graphicIdle
            canvas.drawCircle(r + i * context.dpf((DOT + GAP).toFloat()), r, r, paint)
        }
    }

    companion object {
        const val DOT = 6
        const val GAP = 4
        const val MAX = 12
        const val MAX_W = MAX * (DOT + GAP) - GAP
    }
}

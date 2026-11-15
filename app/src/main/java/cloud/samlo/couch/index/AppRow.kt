package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.ui.Fonts
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * One app in the list: icon, name, and its screen time on the right. Selecting it only swaps
 * the text colours; the background and accent marker belong to [AppList], which glides them
 * between rows. No size changes, so no layout pass.
 *
 * Couch's own rows borrow it: Continue watching gets a semibold name and its program count where
 * the screen time would be, and a folder its app count.
 */
@SuppressLint("ViewConstructor")
class AppRow(context: Context, entry: IndexEntry) : FrameLayout(context) {
    var entry: IndexEntry = entry
        private set

    private val icon = ImageView(context).apply {
        this.background = GradientDrawable().apply {
            cornerRadius = context.dpf(ICON_RADIUS)
            setColor(Tokens.surface)
        }
    }
    private val name = IndexText(context, 14f, 500, Tokens.textMuted).apply {
        letterSpacing = -0.01f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val hours = IndexText(context, 8.5f, 500, IndexTokens.hours).apply {
        fontFeatureSettings = "tnum"
        maxLines = 1
    }

    /** The app the list (and the right column) is showing. */
    var chosen = false
        set(value) {
            if (field == value) return
            field = value
            restyle()
        }

    /** Outlined in the accent while a manual move has it off the list, under the remote's thumb. */
    var lifted = false
        set(value) {
            if (field == value) return
            field = value
            background = if (value) liftedOutline(context) else null
        }

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(11), 0, context.dp(10), 0)
            addView(icon, LinearLayout.LayoutParams(context.dp(IndexTokens.LIST_ICON), context.dp(IndexTokens.LIST_ICON)))
            addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(9) })
            addView(hours, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(6) })
        }
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bind(entry)
    }

    fun bind(entry: IndexEntry) {
        this.entry = entry
        contentDescription = entry.app.label
        icon.setImageBitmap(entry.icon)
        name.text = entry.app.label
        name.typeface = Fonts.hanken(context, if (entry.continues || entry.isFolder) OWN_WEIGHT else WEIGHT)
        hours.text = when {
            entry.continues -> entry.programs.size.toString()
            // A folder says how many apps it holds, where an app has its screen time.
            entry.isFolder -> context.resources.getQuantityString(R.plurals.folder_apps, entry.folderApps.size, entry.folderApps.size)
            else -> entry.usage?.let { Formats.short(context, it.weekMs) }.orEmpty()
        }
        restyle()
    }

    private fun restyle() {
        name.setTextColor(if (chosen) Tokens.textPrimary else Tokens.textMuted)
        // The counts always read as Couch's own rows, not as an app's screen time.
        hours.setTextColor(if (chosen || entry.continues || entry.isFolder) Tokens.accent else IndexTokens.hours)
    }

    companion object {
        const val ICON_RADIUS = 5f
        private const val WEIGHT = 500
        private const val OWN_WEIGHT = 600
    }
}

/** The thin accent outline a lifted row wears while a manual move carries it. */
private fun liftedOutline(context: Context) = GradientDrawable().apply {
    cornerRadius = context.dpf(LIFTED_RADIUS)
    setStroke(context.dpf(LIFTED_STROKE).toInt(), Tokens.accent)
}

private const val LIFTED_RADIUS = 6f
private const val LIFTED_STROKE = 1.5f

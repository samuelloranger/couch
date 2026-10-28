package cloud.samlo.couch.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The groups of Customize down the left of the screen, each with a line saying how it is set.
 * On the main page it is how you move between groups; on a page inside a group it only shows
 * where you are, and takes no focus.
 */
class Rail(private val ctx: SettingsContext, private val focusable: Boolean) {
    private val items = LinkedHashMap<Group, RailItem>()
    private var warning: RailItem? = null
    var selected: Group = Group.LOOK
        private set

    /** Fills the rail: an optional [warning] line on top, then every group, [selected] lit. */
    fun build(selected: Group, warning: RailItem? = null) {
        val rail = ctx.layout.rail
        rail.removeAllViews()
        items.clear()
        this.warning = warning
        warning?.let { rail.addView(it, itemParams()) }
        for (group in Group.entries) {
            val item = RailItem(ctx.activity, group.icon, ctx.str(group.title))
            item.isFocusable = focusable
            items[group] = item
            rail.addView(item, itemParams())
        }
        val all = (0 until rail.childCount).map { rail.getChildAt(it) }
        // The rail is a column of its own: up past the top or down past the bottom stays put.
        all.first().nextFocusUpId = all.first().id
        all.last().nextFocusDownId = all.last().id
        all.forEach { it.nextFocusLeftId = it.id }
        select(selected)
        refresh()
    }

    fun item(group: Group): RailItem = items.getValue(group)

    fun select(group: Group) {
        selected = group
        items.forEach { (g, item) ->
            item.isSelected = g == group
            // Inside a group's page, the other groups step back.
            item.alpha = if (focusable || g == group) 1f else DIMMED
        }
    }

    /** After the accent changed: the ring and the open group's icon take the new colour. */
    fun restyle() = (items.values + listOfNotNull(warning)).forEach { it.restyle() }

    /** The lines of [groups] again, after their options may have changed. */
    fun refresh(vararg groups: Group = Group.entries.toTypedArray()) = groups.forEach { item(it).summary.text = it.summary(ctx) }

    /** Lets [onFocus] follow the focus down the rail, and [onEnter] step into the page on the right. */
    fun listen(onFocus: (Group) -> Unit, onEnter: () -> Unit) = items.forEach { (group, item) ->
        item.setOnFocusChangeListener { _, focused -> if (focused) onFocus(group) }
        item.setOnClickListener { onEnter() }
        item.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) onEnter()
            true
        }
    }

    private fun itemParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.activity.dp(4) }

    private companion object {
        const val DIMMED = 0.45f
    }
}

/** One line of the [Rail]: an icon, the group's name, and how it is set. */
@SuppressLint("ViewConstructor")
class RailItem(context: Context, icon: Int, title: String) : LinearLayout(context) {
    private val image = ImageView(context).apply { setImageResource(icon) }
    val name = TextView(context).apply {
        text = title
        textSize = 17f
        setTextColor(Tokens.textPrimary)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val summary = TextView(context).apply {
        textSize = 13f
        setTextColor(Tokens.textMuted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        id = View.generateViewId()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        minimumHeight = context.dp(60)
        setPadding(context.dp(12), context.dp(9), context.dp(12), context.dp(9))
        background = railBackground(context)
        addView(image, LayoutParams(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(14) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(name)
            addView(summary)
        }, LayoutParams(0, WRAP_CONTENT, 1f))
        tint()
    }

    private var warning = false

    /** The warning line: its name and icon in the warning colour. */
    fun warn() {
        warning = true
        name.setTextColor(Tokens.warning)
        tint()
    }

    fun restyle() {
        background = railBackground(context)
        tint()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        tint()
    }

    /** The open group's icon takes the accent; the others stay quiet. */
    private fun tint() {
        image.imageTintList = ColorStateList.valueOf(
            when {
                warning -> Tokens.warning
                isSelected -> Tokens.accent
                else -> Tokens.textMuted
            },
        )
    }
}

/** Focused: the accent ring rows use. The open group, unfocused: a quiet fill. */
private fun railBackground(context: Context) = StateListDrawable().apply {
    val radius = context.dpf(Tokens.CARD_RADIUS)
    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
        cornerRadius = radius
        setColor(Tokens.surfaceFocused)
        setStroke(context.dp(2), Tokens.accent)
    })
    addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
        cornerRadius = radius
        setColor(Tokens.surface)
    })
    addState(intArrayOf(), GradientDrawable().apply { cornerRadius = radius })
}

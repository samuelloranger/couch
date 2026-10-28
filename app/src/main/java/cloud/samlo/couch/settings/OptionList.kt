package cloud.samlo.couch.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/** A value a [OptionList.choice] row can take: what it shows, and what is stored. */
class Option(val label: String, val value: String)

/**
 * Builds the rows of a settings page. Every change is saved at once (the home screen picks it
 * up when it comes back), and [config] is read on each use so a reloaded config is honoured.
 */
class OptionList(
    private val activity: Activity,
    private val screen: SettingsLayout,
    private val config: () -> Config,
    private val files: UserFiles,
) {
    fun rows(): List<OptionRow> = screen.items().filterIsInstance<OptionRow>()

    /** Re-applies the focus highlight and redraws the switches, e.g. after the accent changed. */
    fun restyle() = rows().forEach {
        it.background = optionBackground(activity)
        it.refresh()
    }

    fun header(text: String) {
        screen.list.addView(TextView(activity).apply {
            this.text = text
            textSize = 14f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Tokens.textMuted)
            setPadding(activity.dp(20), activity.dp(if (screen.list.childCount == 0) 0 else 22), 0, activity.dp(8))
        })
    }

    /** A quiet line under a header, for what a list says when it is empty. */
    fun note(text: String) {
        screen.list.addView(TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTextColor(Tokens.textMuted)
            setPadding(activity.dp(20), 0, 0, activity.dp(10))
        })
    }

    fun add(title: String, help: String, valueOf: () -> String): OptionRow {
        val row = OptionRow(activity, help, valueOf, screen.help)
        row.label.text = title
        screen.list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = activity.dp(4) })
        return row
    }

    fun save(row: OptionRow) {
        config().save(files.dir)
        row.refresh()
    }

    /** Left/right step through [options]; OK steps forward. Short lists show every option at once. */
    fun choice(title: String, help: String, options: List<Option>, get: () -> String, set: (String) -> Unit) {
        val labelOf = { options.firstOrNull { it.value == get() }?.label ?: options.first().label }
        val row = add(title, help) { "‹  ${labelOf()}  ›" }
        val index = { options.indexOfFirst { it.value == get() }.coerceAtLeast(0) }
        val labels = options.map { it.label }
        if (Segments.fit(labels)) row.use(Segments(activity, labels)) { it.select(index()) }
        stepper(row, options.size, index) { set(options[it].value) }
    }

    /** The accent colours as swatches; left/right and OK step through them like a choice. */
    fun swatches(title: String, help: String, colors: List<String>, get: () -> String, set: (String) -> Unit) {
        val row = add(title, help) { "" }
        val index = { colors.indexOf(get()).coerceAtLeast(0) }
        row.use(Swatches(activity, colors.map { Color.parseColor(it) })) { it.select(index()) }
        stepper(row, colors.size, index) { set(colors[it]) }
    }

    /** OK and right move to the next of [count] values, left to the one before, wrapping round. */
    private fun stepper(row: OptionRow, count: Int, index: () -> Int, set: (Int) -> Unit) {
        val step = { by: Int ->
            set((index() + by + count) % count)
            save(row)
        }
        row.setOnClickListener { step(1) }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> step(-1).let { true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> step(1).let { true }
                else -> false
            }
        }
    }

    fun toggle(title: String, help: String, get: () -> Boolean, set: (Boolean) -> Unit): OptionRow {
        val row = add(title, help) { activity.getString(if (get()) R.string.yes else R.string.no) }
        row.use(SwitchView(activity)) { it.on = get() }
        row.setOnClickListener {
            set(!get())
            save(row)
        }
        return row
    }

    /** OK opens a text field; [placeholder] is what an empty value shows and what the field hints. */
    fun text(title: String, help: String, placeholder: String, valueOf: () -> String, set: (String) -> Unit) {
        val row = add(title, help, valueOf)
        row.value.background = underline(activity)
        row.value.setPadding(0, 0, 0, activity.dp(3))
        row.setOnClickListener {
            val current = valueOf().let { if (it == placeholder) "" else it.removePrefix("r/") }
            Dialogs.text(activity, title, current, placeholder) {
                set(it)
                save(row)
            }
        }
    }

    /** Does something here, on this screen; shows [valueOf] and no arrow. */
    fun link(title: String, help: String, valueOf: () -> String = { "" }, onClick: () -> Unit): OptionRow {
        val row = add(title, help, valueOf)
        row.setOnClickListener { onClick() }
        return row
    }

    /** Opens another page of settings. */
    fun page(title: String, help: String, valueOf: () -> String = { "" }, onClick: () -> Unit) =
        link(title, help, { withMark(valueOf(), "›") }, onClick)

    /** Leaves settings: for Android's own screens, or back to the home screen. */
    fun leaves(title: String, help: String, valueOf: () -> String = { "" }, onClick: () -> Unit) =
        link(title, help, { withMark(valueOf(), "↗") }, onClick)

    private fun withMark(value: String, mark: String) = if (value.isEmpty()) mark else "$value  $mark"
}

package cloud.samlo.couch.settings

import android.app.Activity
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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

    /** Re-applies the focus highlight, e.g. after the accent changed. */
    fun restyle() = rows().forEach { it.background = optionBackground(activity) }

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

    fun add(title: String, help: String, valueOf: () -> String): OptionRow {
        val row = OptionRow(activity, help, valueOf, screen.help)
        row.label.text = title
        screen.list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, activity.dp(52)).apply { bottomMargin = activity.dp(4) })
        return row
    }

    fun save(row: OptionRow) {
        config().save(files.dir)
        row.refresh()
    }

    /** Left/right step through [options]; OK steps forward. */
    fun choice(title: String, help: String, options: List<Option>, get: () -> String, set: (String) -> Unit) {
        val labelOf = { options.firstOrNull { it.value == get() }?.label ?: options.first().label }
        val row = add(title, help) { "‹  ${labelOf()}  ›" }
        val step = { by: Int ->
            val i = options.indexOfFirst { it.value == get() }.coerceAtLeast(0)
            set(options[(i + by + options.size) % options.size].value)
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
        row.setOnClickListener {
            set(!get())
            save(row)
        }
        return row
    }

    /** OK opens a text field; [placeholder] is what an empty value shows and what the field hints. */
    fun text(title: String, help: String, placeholder: String, valueOf: () -> String, set: (String) -> Unit) {
        val row = add(title, help, valueOf)
        row.setOnClickListener {
            val current = valueOf().let { if (it == placeholder) "" else it.removePrefix("r/") }
            Dialogs.text(activity, title, current, placeholder) {
                set(it)
                save(row)
            }
        }
    }

    fun link(title: String, help: String, valueOf: () -> String = { "" }, onClick: () -> Unit): OptionRow {
        val row = add(title, help) { valueOf().ifEmpty { "›" } }
        row.setOnClickListener { onClick() }
        return row
    }
}

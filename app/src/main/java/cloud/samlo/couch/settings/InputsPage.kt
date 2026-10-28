package cloud.samlo.couch.settings

import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.InputIcon
import cloud.samlo.couch.data.InputSource
import cloud.samlo.couch.data.Tile

/**
 * Each input's name and icon (OK picks the icon, a long press renames), then whether the home
 * screen shows it at all.
 */
class InputsPage(private val ctx: SettingsContext) : SettingsPage {
    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.settings_inputs)
        ctx.layout.hint.text = ctx.str(R.string.inputs_hint)
        val inputs = InputSource.all(ctx.activity, ctx.config)
        if (inputs.isEmpty()) {
            ctx.layout.hint.text = ctx.str(R.string.inputs_empty)
            return
        }
        ctx.options.header(ctx.str(R.string.inputs_names))
        inputs.forEach(::addInput)
        ctx.options.header(ctx.str(R.string.inputs_shown))
        val config = { ctx.config }
        inputs.forEach { input ->
            ctx.options.toggle(input.label, ctx.str(R.string.input_shown_help), { input.key !in config().hiddenInputs }) {
                config().hiddenInputs = if (it) config().hiddenInputs - input.key else config().hiddenInputs + input.key
            }
        }
    }

    /** Back from the icon picker: show what was picked. */
    override fun onResume() {
        ctx.config = Config.load(ctx.files.dir)
        ctx.options.rows().forEach { it.refresh() }
    }

    private fun addInput(input: Tile) {
        val row = ctx.options.add(input.label, ctx.str(R.string.input_row_help)) { iconName(input) }
        row.setOnClickListener { ctx.activity.startActivity(IconPickerActivity.intent(ctx.activity, input.key, input.label)) }
        row.isLongClickable = true
        row.setOnLongClickListener {
            rename(input, row)
            true
        }
    }

    private fun iconName(input: Tile): String {
        val icon = InputIcon.of(ctx.config.inputIcons[input.key])
        if (icon != InputIcon.AUTO) return ctx.str(icon.label)
        val guess = InputIcon.resolve(null, input.label)
        return ctx.activity.getString(R.string.icon_auto_guess, ctx.str(guess.label))
    }

    private fun rename(input: Tile, row: OptionRow) {
        val config = ctx.config
        Dialogs.text(ctx.activity, ctx.str(R.string.menu_rename), config.names[input.key] ?: input.label, input.label) { name ->
            config.names = if (name.isEmpty()) config.names - input.key else config.names + (input.key to name)
            ctx.options.save(row)
            ctx.activity.recreate()
        }
    }
}

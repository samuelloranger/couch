package cloud.samlo.couch.settings

import cloud.samlo.couch.R

/** The accesses that turn features on, each with where Android grants it. */
class AccessPage(private val ctx: SettingsContext) : SettingsPage {
    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.group_setup)
        ctx.layout.hint.text = ctx.str(R.string.setup_hint)
        for (item in Setup.items) {
            ctx.options.link(ctx.str(item.title), ctx.str(item.help), { value(item) }) { item.open(ctx.activity) }
        }
    }

    /** Back from Android's settings: show what was just granted. */
    override fun onResume() = ctx.options.rows().forEach { it.refresh() }

    /** Granted, or how to turn it on: most of these open one of Android's screens. */
    private fun value(item: SetupItem): String {
        if (item.isOn(ctx.activity)) return ctx.str(R.string.yes)
        val turnOn = ctx.str(R.string.setup_turn_on)
        return if (item.inPlace) turnOn else "$turnOn  ↗"
    }
}

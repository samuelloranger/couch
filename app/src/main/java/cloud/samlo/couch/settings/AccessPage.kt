package cloud.samlo.couch.settings

import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.R

/** The accesses that turn features on, each with where Android grants it. */
class AccessPage(private val ctx: SettingsContext) : SettingsPage {
    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.group_setup)
        ctx.layout.hint.text = ctx.str(R.string.setup_hint)
        for (item in Setup.items) {
            ctx.options.link(ctx.str(item.title), ctx.str(item.help), { value(item) }) { item.open(ctx.activity) }
            if (item === Setup.home) longSleep()
        }
    }

    /** Back from Android's settings: show what was just granted. */
    override fun onResume() = ctx.options.rows().forEach { it.refresh() }

    /**
     * What a long sleep wakes to, under the HOME access it runs through. With that access off
     * nothing of Couch is alive to watch the screen, so the row says so and the setting waits.
     */
    private fun longSleep() {
        val c = ctx.activity
        val options = listOf(Option(ctx.str(R.string.long_sleep_off), "0")) +
            listOf(60, 120, 240, 480).map { Option(c.getString(R.string.long_sleep_hours, it / 60), "$it") }
        ctx.options.choice(ctx.str(R.string.long_sleep), ctx.str(R.string.long_sleep_help), options, { longSleepValue() }) {
            ctx.config.longSleepMinutes = it.toInt()
        }
    }

    private fun longSleepValue(): String {
        val minutes = ctx.config.longSleepMinutes
        return when {
            minutes == 0 -> "0"
            // Being Android's home isn't enough: nothing of Couch is alive to watch the screen then.
            !HomeAccessibilityService.isEnabled(ctx.activity) -> ctx.str(R.string.long_sleep_no_home)
            else -> "$minutes"
        }
    }

    /** Granted, or how to turn it on: most of these open one of Android's screens. */
    private fun value(item: SetupItem): String {
        if (item.isOn(ctx.activity)) return ctx.str(R.string.yes)
        val turnOn = ctx.str(R.string.setup_turn_on)
        return if (item.inPlace) turnOn else "$turnOn  $LEAVES"
    }
}

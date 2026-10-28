package cloud.samlo.couch.settings

import android.view.KeyEvent
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Programs
import cloud.samlo.couch.data.Sections
import cloud.samlo.couch.data.Usage

/**
 * The rows of the home screen, app channels among them: one switch each. In the Rows layout they
 * come in their home-screen order, and a long press moves one; Index only uses the channels, so
 * it lists those alone.
 */
class RowsPage(private val ctx: SettingsContext) : SettingsPage {
    private val mover = RowMover(ctx)
    private val config get() = ctx.config

    override fun build() {
        val rows = config.layout != Config.LAYOUT_INDEX
        ctx.layout.title.text = ctx.str(if (rows) R.string.settings_rows else R.string.settings_channels)
        val names = linkedMapOf<String, String>()
        if (rows) {
            names[Sections.APPS] = ctx.str(R.string.section_apps)
            // Recent needs usage access; without it the row never shows, so it isn't offered.
            if (Usage.canRead(ctx.activity)) names[Sections.RECENT] = ctx.str(R.string.section_recent)
            names[Sections.INPUTS] = ctx.str(R.string.section_inputs)
        }
        val channels = Programs.allChannels(ctx.activity, config).associateBy { Sections.channelId(it.id) }
        channels.forEach { (id, info) -> names[id] = Programs.title(ctx.activity, info.packageName, info.name, " · ") }
        ctx.layout.hint.text = ctx.str(
            when {
                rows -> R.string.rows_hint
                channels.isEmpty() -> R.string.channels_empty
                else -> R.string.channels_hint
            },
        )
        val ids = if (rows) Sections.order(names.keys.toList(), config) else names.keys.toList()
        for (id in ids) {
            val channel = channels[id]
            val row = if (channel == null) {
                ctx.options.toggle(names.getValue(id), ctx.str(R.string.rows_item_help), { id !in config.hiddenSections }) {
                    config.hiddenSections = if (it) config.hiddenSections - id else config.hiddenSections + id
                }
            } else {
                // One switch per channel. It used to have two (shown at all, and shown as a row);
                // a channel hidden only as a row reads as off here, and either answer clears that.
                ctx.options.toggle(names.getValue(id), ctx.str(if (rows) R.string.rows_item_help else R.string.channel_item_help), {
                    channel.shown(config) && (!rows || id !in config.hiddenSections)
                }) {
                    config.channels = config.channels + (channel.id.toString() to it)
                    config.hiddenSections = config.hiddenSections - id
                }
            }
            if (!rows) continue
            row.tag = id
            row.isLongClickable = true
            row.setOnLongClickListener {
                mover.start(row)
                true
            }
        }
    }

    override fun consumesKey(event: KeyEvent) = mover.consumesKey(event)
}

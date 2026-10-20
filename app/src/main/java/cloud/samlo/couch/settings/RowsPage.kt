package cloud.samlo.couch.settings

import android.view.KeyEvent
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Programs
import cloud.samlo.couch.data.Sections
import cloud.samlo.couch.data.Usage

/** Which home rows show, and in what order (long press a row to move it). */
class RowsPage(private val ctx: SettingsContext) : SettingsPage {
    private val mover = RowMover(ctx)

    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.settings_rows)
        ctx.layout.hint.text = ctx.str(R.string.rows_hint)
        val config = { ctx.config }
        val names = linkedMapOf(Sections.APPS to ctx.str(R.string.section_apps))
        // Recent needs usage access; without it the row never shows, so it isn't offered.
        if (Usage.canRead(ctx.activity)) names[Sections.RECENT] = ctx.str(R.string.section_recent)
        names[Sections.INPUTS] = ctx.str(R.string.section_inputs)
        Programs.allChannels(ctx.activity, ctx.config).filter { it.shown(ctx.config) }.forEach {
            names[Sections.channelId(it.id)] = Programs.title(ctx.activity, it.packageName, it.name, " · ")
        }
        for (id in Sections.order(names.keys.toList(), ctx.config)) {
            val row = ctx.options.toggle(names.getValue(id), ctx.str(R.string.rows_item_help), { id !in config().hiddenSections }, {
                config().hiddenSections = if (it) config().hiddenSections - id else config().hiddenSections + id
            })
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

package cloud.samlo.couch.debloat

import android.text.format.DateFormat
import cloud.samlo.couch.R
import cloud.samlo.couch.settings.OptionRow

/**
 * The scan, by what removal would buy on this TV: apps worth removing (always running, or using
 * memory now), apps that cost nothing (folded away), and apps better kept, greyed with the reason.
 * An app opened in the last 30 days counts as kept, whatever the list says.
 */
internal fun DebloatPage.scanRows() {
    val s = scan
    if (s == null) {
        o.header(ctx.str(R.string.debloat_intro))
        o.link(ctx.str(R.string.debloat_look), ctx.str(R.string.debloat_look_help), { ctx.str(R.string.scanning) }) { rescan() }
        return
    }
    val (used, free) = s.removable.partition { impacts[it.pkg]?.lastUsed != null }
    val (worth, idle) = free.partition { impacts[it.pkg]?.worth == true }

    o.header(ctx.str(R.string.section_worth))
    if (worth.isEmpty()) o.link(ctx.str(R.string.nothing_worth), ctx.str(R.string.nothing_worth_help)) {}
    pickable(worth)
    if (idle.isNotEmpty()) {
        o.header(ctx.str(R.string.section_idle))
        if (showIdle) {
            pickable(idle)
        } else {
            o.link(ctx.activity.resources.getQuantityString(R.plurals.show_idle, idle.size, idle.size), ctx.str(R.string.show_idle_help)) {
                showIdle = true
                rebuild()
            }
        }
    }
    val kept = used.map { GridItem(it.pkg, it.why, usedHelp(it.pkg), locked = true) } +
        s.kept.map { (pkg, why) -> GridItem(pkg, look(why), ctx.str(why.text) + "\n\n" + pkg, locked = true) }
    if (kept.isNotEmpty()) {
        o.header(ctx.str(R.string.section_kept))
        grid(kept)
    }
    o.header(ctx.str(R.string.level_stays))
    o.link(ctx.str(R.string.protected_row), ctx.str(R.string.why_never), { "${s.protectedCount}" }) {}
    o.header(ctx.str(R.string.group_act))
    o.link(actionTitle(), ctx.str(R.string.debloat_act_help)) { startRemoving() }.tag = ACTION_ROW
    val removed = store.entries().size
    o.page(ctx.str(R.string.undo_open), ctx.str(R.string.undo_open_help), { if (removed == 0) "" else "$removed" }) { go(DebloatPage.Step.UNDO) }
}

private const val ACTION_ROW = "debloat-action"

private fun DebloatPage.actionTitle() =
    if (picked.isEmpty()) ctx.str(R.string.act_pick)
    else ctx.activity.resources.getQuantityString(R.plurals.act_remove, picked.size, picked.size)

/** A grid OK ticks and unticks; the help pane leads with the level and what removal would buy. */
private fun DebloatPage.pickable(entries: List<DebloatList.Entry>) {
    grid(entries.map { GridItem(it.pkg, it.why, help(it)) }) { pkg, tile ->
        if (!picked.add(pkg)) picked.remove(pkg)
        tile.picked = pkg in picked
        ctx.layout.list.findViewWithTag<OptionRow>(ACTION_ROW)?.label?.text = actionTitle()
    }.forEach { it.picked = it.tag in picked }
}

private fun DebloatPage.help(e: DebloatList.Entry): String {
    val level = ctx.str(
        when (e.level) {
            DebloatList.Level.SAFE -> R.string.level_safe
            DebloatList.Level.ASK -> R.string.level_ask
            DebloatList.Level.EXPERT -> R.string.level_expert
        },
    )
    return "$level · ${impactLine(impacts[e.pkg])}\n\n${ctx.str(e.why.text)}\n\n${e.pkg}"
}

private fun DebloatPage.impactLine(i: Impact?): String {
    val mb = i?.memoryKb?.takeIf { it >= 1024 }?.let { ctx.activity.getString(R.string.mb, it / 1024) }
    return when {
        i == null -> ctx.str(R.string.impact_idle)
        i.alwaysOn && mb != null -> ctx.activity.getString(R.string.impact_always_mb, mb)
        i.alwaysOn -> ctx.str(R.string.impact_always)
        mb != null -> ctx.activity.getString(R.string.impact_running, mb)
        i.atBoot -> ctx.str(R.string.impact_boot)
        else -> ctx.str(R.string.impact_idle)
    }
}

private fun DebloatPage.usedHelp(pkg: String): String {
    val at = impacts[pkg]?.lastUsed ?: 0L
    return ctx.activity.getString(R.string.keep_used, DateFormat.getMediumDateFormat(ctx.activity).format(at)) + "\n\n" + pkg
}

/** The glyph a kept app's tile draws when it has no icon. */
private fun look(why: DebloatList.Keep) = when (why) {
    DebloatList.Keep.CASTING -> DebloatList.Why.CASTING
    DebloatList.Keep.MEDIA -> DebloatList.Why.VENDOR_MEDIA
    DebloatList.Keep.VOICE -> DebloatList.Why.VOICE_EXTRAS
    DebloatList.Keep.ACCESSIBILITY -> DebloatList.Why.ACCESSIBILITY
    DebloatList.Keep.ACCOUNT -> DebloatList.Why.SETUP
    DebloatList.Keep.NEEDED -> DebloatList.Why.GOOGLE_EXTRAS
    DebloatList.Keep.UPDATES, DebloatList.Keep.HOME, DebloatList.Keep.DISPUTED -> DebloatList.Why.SYSTEM_EXTRAS
}

/** Starts the batches: each one is saved before it runs, so a restart picks up where it stopped. */
private fun DebloatPage.startRemoving() {
    if (picked.isEmpty()) return
    store.startFlow(picked.toList().chunked(DebloatPage.BATCH))
    picked.clear()
    step = DebloatPage.Step.BATCH
    runBatch()
}

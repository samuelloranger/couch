package cloud.samlo.couch.debloat

import cloud.samlo.couch.R

/** Free memory and storage at the first scan and now, and the ledger's count. */
internal fun DebloatPage.reportRows() {
    o.header(ctx.str(R.string.report_group))
    val before = store.before
    if (before.ramFreeMb >= 0) {
        val now = ramFreeMb()
        o.link(ctx.str(R.string.report_ram), ctx.str(R.string.report_row_help), { shift(before.ramFreeMb, now) }) {}
    }
    if (before.storageFreeMb >= 0) {
        val now = storageFreeMb()
        o.link(ctx.str(R.string.report_storage), ctx.str(R.string.report_row_help), { shift(before.storageFreeMb, now) }) {}
    }
    val tally = store.tally()
    o.link(ctx.str(R.string.report_apps), ctx.str(R.string.report_apps_help), {
        ctx.activity.getString(R.string.report_apps_value, tally.active, tally.ever - tally.active)
    }) {}
    o.link(ctx.str(R.string.report_done), ctx.str(R.string.report_done_help)) { go(DebloatPage.Step.SCAN) }
}

private fun DebloatPage.shift(wasMb: Int, nowMb: Int) = "${fmt(wasMb)} → ${fmt(nowMb)}"

private fun DebloatPage.fmt(mb: Int) =
    if (mb >= 1024) ctx.activity.getString(R.string.gb, mb / 1024f) else ctx.activity.getString(R.string.mb, mb)

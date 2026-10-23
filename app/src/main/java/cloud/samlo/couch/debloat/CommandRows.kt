package cloud.samlo.couch.debloat

import cloud.samlo.couch.R
import cloud.samlo.couch.data.SystemInfo

/**
 * The computer route, for TVs that drop Couch's own connection (Fire OS 8 does): the picked apps
 * as commands to type, in batches. "I ran it" asks the package manager which apps really went,
 * so the undo list only ever holds real removals.
 */
internal fun DebloatPage.commandRows() {
    if (picked.isEmpty()) {
        o.header(ctx.str(R.string.commands_intro))
        o.link(ctx.str(R.string.undo_close), ctx.str(R.string.undo_close_help)) { go(DebloatPage.Step.SCAN) }
        return
    }
    o.header(ctx.str(R.string.commands_intro))
    val address = SystemInfo(ctx.activity).read().address ?: ctx.str(R.string.tv_address)
    o.link(ctx.str(R.string.commands_first), ctx.str(R.string.commands_line_help), { "adb connect $address:5555" }) {}
    picked.toList().chunked(DebloatPage.BATCH).forEachIndexed { n, batch ->
        o.header(ctx.activity.getString(R.string.commands_batch, n + 1))
        batch.forEach { pkg ->
            o.link(label(pkg), ctx.str(R.string.commands_line_help), { "adb shell pm uninstall -k --user 0 $pkg" }) {}
        }
        o.link(ctx.activity.getString(R.string.commands_mark, n + 1), ctx.str(R.string.commands_mark_help)) { markRan(batch) }
    }
}

private fun DebloatPage.markRan(batch: List<String>) {
    val still = ArrayList<String>()
    batch.forEach { pkg ->
        if (Packages.installed(ctx.activity, pkg)) {
            still += label(pkg)
        } else {
            store.record(pkg, present = true)
            store.removed(pkg, viaShell = false)
            picked.remove(pkg)
        }
    }
    if (still.isNotEmpty()) toast(ctx.activity.getString(R.string.still_installed_toast, still.joinToString()))
    if (picked.isEmpty()) go(DebloatPage.Step.REPORT) else rebuild()
}

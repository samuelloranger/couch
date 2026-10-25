package cloud.samlo.couch.debloat

import cloud.samlo.couch.R
import cloud.samlo.couch.adb.AdbShell

/**
 * Everything Couch removed, each one OK away from coming back. An entry leaves the list only once
 * the package manager sees the app again, whichever way it came back (the shell, a computer, the
 * TV's own settings). Where the TV refuses the shell, the rows turn into the commands to type.
 */
internal fun DebloatPage.undoRows() {
    // Apps already back by other means drop out of the list first.
    store.entries().filter { Packages.installed(ctx.activity, it) }.forEach(store::undone)
    val entries = store.entries()
    if (entries.isEmpty()) {
        o.header(ctx.str(R.string.undo_empty))
        o.link(ctx.str(R.string.undo_close), ctx.str(R.string.undo_close_help)) { go(DebloatPage.Step.SCAN) }
        return
    }
    if (restoreByHand) return byHandRows(entries)
    o.header(ctx.str(R.string.undo_title))
    grid(entries.map { GridItem(it, list.entry(it)?.why ?: DebloatList.Why.SYSTEM_EXTRAS, ctx.str(R.string.undo_row_help) + "\n\n" + it) }) { pkg, _ ->
        restore(listOf(pkg))
    }
    o.header(ctx.str(R.string.undo_all_group))
    o.link(ctx.str(R.string.undo_all), ctx.str(R.string.undo_all_help)) { restore(entries) }
    o.link(ctx.str(R.string.undo_close), ctx.str(R.string.undo_close_help)) { go(DebloatPage.Step.SCAN) }
}

private fun DebloatPage.restore(pkgs: List<String>) {
    busy = true
    work {
        when (val state = engine.connect()) {
            Engine.State.GRANTED -> {
                var back = 0
                pkgs.forEach { pkg ->
                    try {
                        engine.restore(pkg)
                        store.undone(pkg)
                        back++
                    } catch (e: Engine.Refused) {
                        toast(ctx.activity.getString(R.string.refused_toast, label(pkg)))
                    } catch (e: AdbShell.Failed) {
                        error = ctx.str(R.string.shell_no_ok)
                        return@work
                    }
                }
                if (back > 0) toast(ctx.str(R.string.undo_all_toast))
            }
            Engine.State.BLOCKED -> restoreByHand = true
            else -> error = shellError(state)
        }
    }
}

private fun DebloatPage.byHandRows(entries: List<String>) {
    o.header(ctx.str(R.string.restore_by_hand))
    entries.forEach { pkg ->
        o.link(label(pkg), ctx.str(R.string.restore_line_help), { "cmd package install-existing $pkg" }) {}
    }
    o.link(ctx.str(R.string.restore_mark), ctx.str(R.string.restore_mark_help)) {
        val still = store.entries().filterNot { Packages.installed(ctx.activity, it) }
        if (still.isNotEmpty()) toast(ctx.activity.getString(R.string.still_missing_toast, still.joinToString { label(it) }))
        rebuild()
    }
}

package cloud.samlo.couch.debloat

import cloud.samlo.couch.R
import cloud.samlo.couch.adb.AdbShell

/**
 * One batch at a time. It runs, then waits for the user's check: restart the TV (firmware that
 * brings an app back does it at boot), see that every app is still gone, then go on. The flow is
 * in [DebloatStore], so the page opens back here after the restart.
 */
internal fun DebloatPage.batchRows() {
    val flow = store.flow ?: return go(DebloatPage.Step.REPORT)
    val batch = flow.batches[flow.index]
    o.header(ctx.activity.resources.getQuantityString(R.plurals.batch_header, batch.size, flow.index + 1, flow.batches.size, batch.size))
    if (busy) {
        o.link(ctx.str(R.string.batch_removing), ctx.str(R.string.batch_removing_help), { ctx.str(R.string.step_pending) }) {}
        return
    }
    if (flow.check.isEmpty()) {
        o.link(ctx.str(R.string.run_batch), ctx.str(R.string.run_batch_help)) { runBatch() }
        o.link(ctx.str(R.string.stop_flow), ctx.str(R.string.stop_flow_help)) {
            store.stopFlow()
            go(DebloatPage.Step.SCAN)
        }
        return
    }
    val restarted = flow.boot >= 0 && bootCount() > flow.boot
    o.link(ctx.str(R.string.restart_row), ctx.str(R.string.restart_row_help), { ctx.str(if (restarted) R.string.step_done else R.string.restart_value) }) {
        if (!restarted) restart()
    }
    val back = flow.check.filter { Packages.installed(ctx.activity, it) }
    val gone = flow.check.size - back.size
    o.link(ctx.str(R.string.still_removed), ctx.str(R.string.still_removed_help), {
        if (back.isEmpty()) ctx.activity.getString(R.string.still_removed_value, gone, flow.check.size)
        else ctx.activity.resources.getQuantityString(R.plurals.came_back, back.size, back.size)
    }) {
        if (back.isNotEmpty()) removeAgain(back)
    }
    o.link(ctx.str(R.string.batch_check), ctx.str(R.string.batch_check_help)) {
        if (store.next() != null) runBatch() else go(DebloatPage.Step.REPORT)
    }
    o.link(ctx.str(R.string.undo_batch), ctx.str(R.string.undo_batch_help)) { undoBatch(flow.check) }
}

/** Removes the batch on screen; what the TV refuses is named and left installed. */
internal fun DebloatPage.runBatch() {
    val flow = store.flow ?: return
    val batch = flow.batches[flow.index]
    busy = true
    error = null
    rebuild()
    work {
        val state = engine.connect()
        if (state == Engine.State.BLOCKED) return@work handOver()
        if (state != Engine.State.GRANTED) {
            error = shellError(state)
            return@work
        }
        val done = ArrayList<String>()
        val refused = ArrayList<String>()
        for (pkg in batch) {
            store.record(pkg, present = true)
            try {
                engine.remove(pkg)
                store.removed(pkg, viaShell = true)
                done += pkg
            } catch (e: Engine.Refused) {
                refused += label(pkg)
            } catch (e: IllegalArgumentException) {
                // No longer on the list (the catalog moved on since this flow was saved): leave it.
                refused += label(pkg)
            } catch (e: AdbShell.Failed) {
                // The shell dropped before its answer arrived: the app may be gone all the same,
                // and a removal nobody wrote down could never be undone.
                if (!Packages.installed(ctx.activity, pkg)) {
                    store.removed(pkg, viaShell = true)
                    done += pkg
                }
                error = ctx.str(R.string.shell_no_ok)
                break
            }
        }
        if (refused.isNotEmpty()) toast(ctx.activity.getString(R.string.refused_toast, refused.joinToString()))
        if (done.isNotEmpty()) {
            store.ran(done, bootCount())
        } else if (error == null && store.next() == null) {
            ui { step = DebloatPage.Step.REPORT }
        }
    }
}

/** The TV refuses Couch's shell: what's left of the flow goes to the computer route. */
private fun DebloatPage.handOver() {
    val flow = store.flow ?: return
    val left = flow.batches.drop(flow.index).flatten().filter { Packages.installed(ctx.activity, it) }
    store.stopFlow()
    ui {
        picked.clear()
        picked.addAll(left)
        step = DebloatPage.Step.COMMANDS
    }
}

private fun DebloatPage.restart() {
    busy = true
    work {
        try {
            if (engine.connect() == Engine.State.GRANTED) engine.reboot() else toast(ctx.str(R.string.restart_failed))
        } catch (e: Exception) {
            // The shell often drops as the TV goes down; that's the restart working.
        }
    }
}

private fun DebloatPage.removeAgain(pkgs: List<String>) {
    busy = true
    work {
        val state = engine.connect()
        if (state != Engine.State.GRANTED) {
            error = shellError(state)
            return@work
        }
        pkgs.forEach { pkg ->
            try {
                engine.remove(pkg)
                // Back on the undo list too: an undo made while the firmware had it back took it off.
                store.removed(pkg, viaShell = true)
            } catch (e: Engine.Refused) {
                toast(ctx.activity.getString(R.string.refused_toast, label(pkg)))
            } catch (e: IllegalArgumentException) {
                toast(ctx.activity.getString(R.string.refused_toast, label(pkg)))
            } catch (e: AdbShell.Failed) {
                if (!Packages.installed(ctx.activity, pkg)) store.removed(pkg, viaShell = true)
                error = ctx.str(R.string.shell_no_ok)
            }
        }
    }
}

private fun DebloatPage.undoBatch(pkgs: List<String>) {
    busy = true
    work {
        val state = engine.connect()
        if (state != Engine.State.GRANTED) {
            error = shellError(state)
            return@work
        }
        pkgs.forEach { pkg ->
            try {
                engine.restore(pkg)
                store.undone(pkg)
            } catch (e: Engine.Refused) {
                toast(ctx.activity.getString(R.string.refused_toast, label(pkg)))
            } catch (e: AdbShell.Failed) {
                error = ctx.str(R.string.shell_no_ok)
                return@work
            }
        }
        store.stopFlow()
        ui { step = DebloatPage.Step.UNDO }
    }
}

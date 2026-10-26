package cloud.samlo.couch.settings

import android.view.KeyEvent
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.sync.Client
import cloud.samlo.couch.sync.Server
import cloud.samlo.couch.sync.Sync
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Other TVs: turn sharing on, name this TV, see and forget the paired ones, and pair a new one.
 * Pairing has no typed code. Both TVs are on Add a TV; picking the other one shows six digits on
 * both screens, and one OK on each says they match. The same digits on both is what rules out a
 * stranger in the middle.
 */
class OtherTvsPage(private val ctx: SettingsContext) : SettingsPage {
    private enum class Mode { LIST, ADD, CONFIRM }

    private class Confirm(val name: String, val code: String, val answer: ArrayBlockingQueue<Boolean>)

    private val o = ctx.options
    private val config get() = ctx.config
    private val peers get() = Sync.peers(ctx.activity)
    @Volatile private var mode = Mode.LIST
    /** An outgoing pairing is under way: a TV that knocks meanwhile is turned away. */
    @Volatile private var outgoing = false
    private val asking = java.util.concurrent.atomic.AtomicBoolean()
    private var confirm: Confirm? = null
    private var forgetting: String? = null

    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.tvs_title)
        ctx.layout.hint.text = ctx.str(R.string.tvs_hint)
        when (mode) {
            Mode.LIST -> list()
            Mode.ADD -> add()
            Mode.CONFIRM -> confirmRows()
        }
        Sync.listen { ctx.activity.runOnUiThread { if (mode != Mode.CONFIRM && !ctx.activity.isFinishing) rebuild() } }
    }

    private fun refresh() = o.rows().forEach { it.refresh() }

    private fun list() {
        o.header(ctx.str(R.string.tvs_group))
        o.toggle(ctx.str(R.string.tvs_share), ctx.str(R.string.tvs_share_help), { config.syncEnabled }) {
            config.syncEnabled = it
            config.save(ctx.files.dir)
            Sync.start(ctx.activity)
            rebuild()
        }
        if (!config.syncEnabled) return
        o.choice(
            ctx.str(R.string.tvs_scope), ctx.str(R.string.tvs_scope_help),
            listOf(Option(ctx.str(R.string.tvs_scope_this), "this"), Option(ctx.str(R.string.tvs_scope_all), "all")),
            { if (config.syncAllTvs) "all" else "this" }, { config.syncAllTvs = it == "all" },
        )
        o.text(
            ctx.str(R.string.tvs_name), ctx.str(R.string.tvs_name_help), peers.name,
            { peers.name }, { name ->
                peers.name = name
                Sync.renamed()
            },
        )
        o.header(ctx.str(R.string.tvs_paired))
        val all = peers.all()
        if (all.isEmpty()) o.note(ctx.str(R.string.tvs_none))
        all.forEach { peer ->
            o.link(peer.name, ctx.str(R.string.tvs_peer_help), { peerValue(peer.id, peer.lastSync) }) {
                if (forgetting == peer.id) {
                    peers.forget(peer.id)
                    forgetting = null
                    rebuild()
                } else {
                    forgetting = peer.id
                    refresh()
                }
            }
        }
        o.link(ctx.str(R.string.tvs_add), ctx.str(R.string.tvs_add_help)) {
            mode = Mode.ADD
            Sync.openPairing(ctx.activity, ::askFromOutside)
            rebuild()
        }
    }

    private fun peerValue(id: String, lastSync: Long): String {
        if (forgetting == id) return ctx.str(R.string.tvs_forget_again)
        val seen = Sync.found().any { it.id == id }
        return when {
            lastSync == 0L -> ctx.str(if (seen) R.string.tvs_waiting else R.string.tvs_not_seen)
            else -> ctx.activity.resources.getQuantityString(R.plurals.tvs_synced, minutes(lastSync), minutes(lastSync))
        }
    }

    private fun minutes(at: Long) = ((System.currentTimeMillis() - at) / 60_000L).coerceIn(0L, 99_999L).toInt()

    private fun add() {
        o.header(ctx.str(R.string.tvs_add_group))
        val waiting = Sync.found().filter { it.pairing }
        if (waiting.isEmpty()) o.note(ctx.str(R.string.tvs_add_none))
        waiting.forEach { tv ->
            o.link(tv.name, ctx.str(R.string.tvs_pick_help), { "›" }) { pairWith(tv.host, tv.port) }
        }
        // The row shows the example, and an untouched field opens empty (the field treats the placeholder as empty).
        o.text(ctx.str(R.string.tvs_address), ctx.str(R.string.tvs_address_help), ctx.str(R.string.tvs_address_hint), { ctx.str(R.string.tvs_address_hint) }) { host ->
            if (host.isNotBlank()) pairWith(host.trim(), Server.PORT)
        }
        o.link(ctx.str(R.string.tvs_add_cancel), ctx.str(R.string.tvs_add_cancel_help)) { leavePairing() }
    }

    private fun confirmRows() {
        val c = confirm ?: return leavePairing()
        o.header(ctx.activity.getString(R.string.tvs_confirm_group, c.name))
        o.link(ctx.str(R.string.tvs_code), ctx.str(R.string.tvs_code_help), { c.code.chunked(3).joinToString(" ") }) {}
        o.link(ctx.str(R.string.tvs_match), ctx.str(R.string.tvs_match_help)) { answer(c, true) }
        o.link(ctx.str(R.string.tvs_differ), ctx.str(R.string.tvs_differ_help)) { answer(c, false) }
        // Land on the code's own row so the choice below it isn't one stray OK away.
    }

    private fun answer(c: Confirm, matched: Boolean) {
        c.answer.offer(matched)
        mode = Mode.LIST
        confirm = null
        // Whichever side this was, the pairing is over: stop advertising and stop listening for it.
        Sync.closePairing()
        rebuild()
    }

    /** A TV that knocked: taken only while Add a TV is open and nothing else is being asked. */
    private fun askFromOutside(name: String, code: String): Boolean = mode == Mode.ADD && !outgoing && ask(name, code)

    /** Runs on a worker, from either side of a pairing; returns once the user has answered. One question at a time. */
    private fun ask(name: String, code: String): Boolean {
        if (!asking.compareAndSet(false, true)) return false
        try {
            val answer = ArrayBlockingQueue<Boolean>(1)
            ctx.activity.runOnUiThread {
                confirm = Confirm(name, code, answer)
                mode = Mode.CONFIRM
                rebuild()
            }
            return answer.poll(ANSWER_SECONDS, TimeUnit.SECONDS) ?: false
        } finally {
            asking.set(false)
        }
    }

    private fun pairWith(host: String, port: Int) {
        toast(R.string.tvs_connecting)
        outgoing = true
        Sync.pair(ctx.activity, host, port, ::ask) { result ->
            outgoing = false
            ctx.activity.runOnUiThread {
                if (ctx.activity.isFinishing) return@runOnUiThread
                when (result) {
                    is Client.Pairing.Paired -> toast(ctx.activity.getString(R.string.tvs_paired_toast, result.peer.name))
                    Client.Pairing.Declined -> toast(R.string.tvs_declined)
                    Client.Pairing.Unavailable -> toast(R.string.tvs_unavailable)
                }
                leavePairing()
            }
        }
    }

    private fun leavePairing() {
        Sync.closePairing()
        confirm?.answer?.offer(false)
        confirm = null
        mode = Mode.LIST
        rebuild()
    }

    private fun toast(id: Int) = toast(ctx.str(id))

    private fun toast(text: String) = Toast.makeText(ctx.activity, text, Toast.LENGTH_LONG).show()

    private fun rebuild() {
        forgetting = if (mode == Mode.LIST) forgetting else null
        ctx.layout.list.removeAllViews()
        build()
        ctx.layout.list.post { ctx.layout.items().firstOrNull { it.isFocusable }?.requestFocus() }
    }

    /** Back steps out of pairing instead of leaving the page. */
    override fun consumesKey(event: KeyEvent): Boolean {
        if (mode == Mode.LIST || event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (mode == Mode.CONFIRM) confirm?.let { answer(it, false) } else leavePairing()
        return true
    }

    override fun onDestroy() {
        Sync.listen(null)
        // Always: a pairing left open would keep this TV announced as waiting, with no page to answer it.
        Sync.closePairing()
        confirm?.answer?.offer(false)
    }

    private companion object {
        const val ANSWER_SECONDS = 120L
    }
}

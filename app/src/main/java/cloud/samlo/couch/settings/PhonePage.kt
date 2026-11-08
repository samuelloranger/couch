package cloud.samlo.couch.settings

import android.view.KeyEvent
import cloud.samlo.couch.R
import cloud.samlo.couch.phone.Phones
import cloud.samlo.couch.sync.Peer
import cloud.samlo.couch.sync.Sync
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Phone: pair the Couch phone app, see the phones paired, and forget one. Pairing is the same as
 * between TVs: the phone connects while this page waits, both screens show six digits, and one
 * OK on each says they match. It works with screen-time sharing off.
 */
class PhonePage(private val ctx: SettingsContext) : SettingsPage {
    private enum class Mode { LIST, WAIT, CONFIRM }

    private class Confirm(val name: String, val code: String, val answer: ArrayBlockingQueue<Boolean>)

    private val o = ctx.options
    private val peers get() = Sync.peers(ctx.activity)
    @Volatile private var mode = Mode.LIST
    private val asking = java.util.concurrent.atomic.AtomicBoolean()
    private var confirm: Confirm? = null
    private var forgetting: String? = null
    private var pairingOpen = false

    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.phone_title)
        ctx.layout.hint.text = ctx.str(R.string.phone_hint)
        when (mode) {
            Mode.LIST -> list()
            Mode.WAIT -> waiting()
            Mode.CONFIRM -> confirmRows()
        }
        Sync.listen { ctx.activity.runOnUiThread { if (mode != Mode.CONFIRM && !ctx.activity.isFinishing) rebuild() } }
    }

    private fun list() {
        o.header(ctx.str(R.string.phone_paired))
        val phones = peers.phones()
        if (phones.isEmpty()) o.note(ctx.str(R.string.phone_none))
        phones.forEach { peer ->
            o.link(peer.name, ctx.str(R.string.phone_peer_help), { peerValue(peer.id) }) {
                if (forgetting == peer.id) {
                    forgetting = null
                    Sync.forgetPhone(ctx.activity, peer.id)
                    rebuild()
                } else {
                    forgetting = peer.id
                    o.rows().forEach { it.refresh() }
                }
            }
        }
        o.page(ctx.str(R.string.phone_add), ctx.str(R.string.phone_add_help)) {
            mode = Mode.WAIT
            pairingOpen = true
            Sync.openPairing(ctx.activity, Peer.PHONE, ::aborted, ::askFromOutside)
            rebuild()
        }
    }

    private fun peerValue(id: String) = when {
        forgetting == id -> ctx.str(R.string.tvs_forget_again)
        Phones.isConnected(id) -> ctx.str(R.string.phone_connected)
        else -> ctx.str(R.string.phone_not_connected)
    }

    private fun waiting() {
        o.header(ctx.str(R.string.phone_wait_group))
        o.note(ctx.str(R.string.phone_wait_help))
        o.link(ctx.str(R.string.tvs_add_cancel), ctx.str(R.string.tvs_add_cancel_help)) { leavePairing() }
    }

    private fun confirmRows() {
        val c = confirm ?: return leavePairing()
        o.header(ctx.activity.getString(R.string.tvs_confirm_group, c.name))
        o.link(ctx.str(R.string.tvs_code), ctx.str(R.string.phone_code_help), { c.code.chunked(3).joinToString(" ") }) {}
        o.link(ctx.str(R.string.tvs_match), ctx.str(R.string.tvs_match_help)) { answer(c, true) }
        o.link(ctx.str(R.string.tvs_differ), ctx.str(R.string.tvs_differ_help)) { answer(c, false) }
    }

    private fun answer(c: Confirm, matched: Boolean) {
        c.answer.offer(matched)
        mode = Mode.LIST
        confirm = null
        closePairing()
        rebuild()
    }

    /** A phone that knocked: taken only while this page waits for one. */
    private fun askFromOutside(name: String, code: String): Boolean = mode == Mode.WAIT && ask(name, code)

    /** Runs on a worker; returns once the user has answered. One question at a time. */
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

    private fun leavePairing() {
        closePairing()
        confirm?.answer?.offer(false)
        confirm = null
        mode = Mode.LIST
        rebuild()
    }

    /** Closes pairing once, however many ways out (Back, answering, leaving) get here. */
    private fun closePairing() {
        if (!pairingOpen) return
        pairingOpen = false
        Sync.closePairing()
    }

    /** The other side ended the pairing while the question was up: drop it and wait for the next try. */
    private fun aborted(declined: Boolean) = ctx.activity.runOnUiThread {
        val c = confirm ?: return@runOnUiThread
        if (mode != Mode.CONFIRM || ctx.activity.isFinishing) return@runOnUiThread
        c.answer.offer(false)
        confirm = null
        mode = Mode.WAIT
        toast(if (declined) R.string.pair_aborted_differ else R.string.pair_aborted_gone)
        rebuild()
    }

    private fun toast(id: Int) = android.widget.Toast.makeText(ctx.activity, ctx.str(id), android.widget.Toast.LENGTH_LONG).show()

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
        closePairing()
        confirm?.answer?.offer(false)
    }

    private companion object {
        const val ANSWER_SECONDS = 120L
    }
}

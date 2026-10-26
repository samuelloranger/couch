package cloud.samlo.couch.sync

import android.content.Context
import android.os.Build
import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.data.UserFiles
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Screen time shared between this TV and the ones it is paired with, on the home network only.
 * One instance of everything per process: the server answers while the launcher's process lives,
 * and a TV is asked again for its snapshot when it comes into view and the last answer is stale.
 */
object Sync {
    private const val STALE_MS = 15 * 60_000L

    private var app: Context? = null
    private var peers: Peers? = null
    private var server: Server? = null
    private var discovery: Discovery? = null
    private var listener: (() -> Unit)? = null
    private val pulling = ConcurrentHashMap.newKeySet<String>()

    /** Called on a worker when a TV appears or goes, or a pairing needs the user. */
    @Synchronized fun listen(onChange: (() -> Unit)?) {
        listener = onChange
    }

    private val peersLock = Any()

    /** Its own lock: the settings page asks on the main thread, and must never wait on network setup. */
    fun peers(context: Context): Peers = synchronized(peersLock) {
        peers ?: Peers.load(File(context.applicationContext.filesDir, "sync.json"), Build.MODEL ?: "TV").also { peers = it }
    }

    fun found(): List<Discovery.Found> = discovery?.found().orEmpty().filter { it.id != peers?.id }

    /** Brings sync up when it is turned on, and takes it down when it is off. Cheap to call again. */
    fun start(context: Context) {
        val context = context.applicationContext
        thread(name = "couch-sync-start") {
            val config = Config.load(UserFiles(context).dir)
            if (config.syncEnabled) up(context) else down()
            if (config.syncEnabled) pullStale()
        }
    }

    @Synchronized private fun up(context: Context) {
        app = context
        val me = peers(context)
        val srv = server ?: Server(me) { local(context, me) }.also { it.onPaired = { listener?.invoke() }; server = it }
        val port = srv.start()
        val disc = discovery ?: Discovery(context).also { discovery = it }
        disc.announce(me.id, me.name, port, srv.onPair != null)
        disc.start { listener?.invoke(); pullStale() }
    }

    @Synchronized private fun down() {
        server?.stop()
        server = null
        discovery?.stop()
        discovery = null
    }

    /** This TV's own last week, labelled so another TV can match the same service under another package. */
    private fun local(context: Context, me: Peers): Snapshot {
        val usage = Usage.lastWeek(context).orEmpty()
        return Snapshot(me.id, me.name, System.currentTimeMillis(), LocalDate.now(), usage, Labels.all(context, usage.keys))
    }

    /** Asks every paired TV that is in sight and whose last answer is old. */
    fun pullStale() {
        val me = peers ?: return
        val now = System.currentTimeMillis()
        for (peer in me.all()) {
            if (now - peer.lastSync < STALE_MS) continue
            val at = found().firstOrNull { it.id == peer.id } ?: continue
            if (!pulling.add(peer.id)) continue
            thread(name = "couch-sync-pull") {
                try {
                    Client.pull(at.host, at.port, me, peer)?.let { me.store(peer.id, it, System.currentTimeMillis()) }
                } finally {
                    pulling.remove(peer.id)
                }
            }
        }
    }

    /** This TV's usage with the paired TVs' added, for the apps this TV has. */
    fun withOtherTvs(context: Context, own: Map<String, AppUsage>): Map<String, AppUsage> {
        val snapshots = peers(context).snapshots()
        if (snapshots.isEmpty()) return own
        return Merge.all(own, Labels.all(context, own.keys), LocalDate.now(), snapshots)
    }

    /** Opening and closing pairing happen in the order they were asked, however fast the user backs out. */
    private val pairingOps = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "couch-sync-pairing").apply { isDaemon = true }
    }

    /**
     * Opens this TV for pairing until [closePairing]; [ask] is called with the other TV's name and
     * the code to compare, and blocks until the user answers.
     */
    fun openPairing(context: Context, ask: (String, String) -> Boolean) {
        val context = context.applicationContext
        pairingOps.execute {
            up(context)
            server?.onPair = ask
            announceAgain()
        }
    }

    fun closePairing() {
        server?.onPair = null
        pairingOps.execute {
            server?.onPair = null
            announceAgain()
        }
    }

    @Synchronized private fun announceAgain() {
        val me = peers ?: return
        val port = server?.start() ?: return
        discovery?.announce(me.id, me.name, port, server?.onPair != null)
    }

    /** Pairs with a TV found on the network, or one typed in by address. */
    fun pair(context: Context, host: String, port: Int, ask: (String, String) -> Boolean, done: (Client.Pairing) -> Unit) {
        val me = peers(context)
        thread(name = "couch-sync-pair") { done(Client.pair(host, port, me, ask)) }
    }

    /** Called after the TV's name changes, so other TVs see the new one. */
    fun renamed() = announceAgain()
}

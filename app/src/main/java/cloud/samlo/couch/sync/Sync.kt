package cloud.samlo.couch.sync

import android.content.Context
import android.os.Build
import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.phone.Phones
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Screen time shared between this TV and the ones it is paired with, on the home network only,
 * and the server the phone app connects to. One instance of everything per process: the server
 * answers while the launcher's process lives, and a TV is asked again for its snapshot when it
 * comes into view and the last answer is stale. The server runs while sharing is on, a phone is
 * paired, or pairing is open.
 */
object Sync {
    private const val STALE_MS = 15 * 60_000L

    private var app: Context? = null
    private var peers: Peers? = null
    private var server: Server? = null
    private var discovery: Discovery? = null
    private var listener: (() -> Unit)? = null
    private val pulling = ConcurrentHashMap.newKeySet<String>()
    /** Whether screen time is shared right now; the server also runs for phones with this off. */
    @Volatile private var sharing = false

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

    /** Brings the server up while it is wanted (sharing, a phone, open pairing), and down when it isn't. Cheap to call again. */
    fun start(context: Context) {
        val context = context.applicationContext
        thread(name = "couch-sync-start") {
            val config = Config.load(UserFiles(context).dir)
            sharing = config.syncEnabled
            reconcile(context)
            if (config.syncEnabled) pullStale()
        }
    }

    @Synchronized private fun reconcile(context: Context) {
        if (sharing || peers(context).phones().isNotEmpty() || wantedAsk != null) up(context) else down()
    }

    @Synchronized private fun up(context: Context) {
        app = context
        val me = peers(context)
        Phones.attach(context)
        val srv = server ?: Server(me) { local(context, me) }.also {
            it.onPaired = {
                listener?.invoke()
                // Pairing may have closed first and stopped the server for want of a phone; now there is one.
                thread(name = "couch-sync-paired") { reconcile(context) }
            }
            it.phones = Phones
            it.shares = { sharing }
            server = it
        }
        applyPairing()
        val port = srv.start()
        val disc = discovery ?: Discovery(context).also { discovery = it }
        disc.announce(me.id, me.name, port, wantedAsk != null)
        disc.start { listener?.invoke(); pullStale() }
    }

    @Synchronized private fun down() {
        server?.stop()
        server = null
        discovery?.stop()
        discovery = null
    }

    /** This TV's own screen time as a snapshot, for the phone app. */
    fun snapshotNow(context: Context): Snapshot = local(context, peers(context))

    /** This TV's own last week, labelled so another TV can match the same service under another package. */
    private fun local(context: Context, me: Peers): Snapshot {
        val usage = Usage.lastWeek(context).orEmpty()
        return Snapshot(me.id, me.name, System.currentTimeMillis(), LocalDate.now(), usage, Labels.all(context, usage.keys))
    }

    /** Asks every paired TV that is in sight and whose last answer is old. */
    fun pullStale() {
        val me = peers ?: return
        val now = System.currentTimeMillis()
        for (peer in me.tvs()) {
            if (now - peer.lastSync < STALE_MS) continue
            val at = found().firstOrNull { it.id == peer.id } ?: continue
            if (!pulling.add(peer.id)) continue
            thread(name = "couch-sync-pull") {
                try {
                    Client.pull(at.host, at.port, me, peer)?.let { me.store(peer.id, it, System.currentTimeMillis()) }
                } catch (e: Exception) {
                    // A failed pull or save just waits for the next one.
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

    /** What the page that wants pairing asked for; the server follows the latest request, whatever order the work runs in. */
    @Volatile private var wantedAsk: ((String, String) -> Boolean)? = null
    @Volatile private var wantedKind = Peer.TV
    @Volatile private var wantedAborted: ((Boolean) -> Unit)? = null

    private fun applyPairing() {
        server?.pairKind = wantedKind
        server?.onAborted = wantedAborted
        server?.onPair = wantedAsk
    }

    /**
     * Opens this TV for [kind] pairing until [closePairing]; [ask] is called with the other side's
     * name and the code to compare, and blocks until the user answers. [aborted] hears when the
     * other side ended it first (true: it said the codes differ).
     */
    fun openPairing(context: Context, kind: String, aborted: (Boolean) -> Unit, ask: (String, String) -> Boolean) {
        val context = context.applicationContext
        wantedKind = kind
        wantedAborted = aborted
        wantedAsk = ask
        pairingOps.execute {
            up(context)
            applyPairing()
            announceAgain()
        }
    }

    fun closePairing() {
        wantedAsk = null
        wantedAborted = null
        server?.onPair = null
        pairingOps.execute {
            applyPairing()
            announceAgain()
            // With sharing off and no phone paired, nothing else wants the server any more.
            app?.let { reconcile(it) }
        }
    }

    /** Forgets a paired phone and ends its session at once; the server stops if it was the last reason to run. */
    fun forgetPhone(context: Context, id: String) {
        peers(context).forget(id)
        Phones.cutOff(id)
        val context = context.applicationContext
        thread(name = "couch-sync-forget") { reconcile(context) }
    }

    @Synchronized private fun announceAgain() {
        val me = peers ?: return
        val port = server?.start() ?: return
        discovery?.announce(me.id, me.name, port, wantedAsk != null)
    }

    /** Pairs with a TV found on the network, or one typed in by address. */
    fun pair(context: Context, host: String, port: Int, ask: (String, String) -> Boolean, done: (Client.Pairing) -> Unit) {
        val me = peers(context)
        thread(name = "couch-sync-pair") { done(Client.pair(host, port, me, ask)) }
    }

    /** Called after the TV's name changes, so other TVs see the new one. */
    fun renamed() = announceAgain()
}

package cloud.samlo.couch.phone

import android.content.Context
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.sync.Peer
import org.json.JSONObject
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Every connected phone, one per process. Takes sessions from the sync server (at most [MAX_SESSIONS],
 * apart from the server's short-connection budget), sends them what changed on the TV, and shuts
 * the picture and the key connection down when the last one leaves.
 */
object Phones : PhoneHost, Session.Listener {
    private const val MAX_SESSIONS = 4
    private const val PULSE_MS = 2_000L

    private var app: Context? = null
    private val sessions = CopyOnWriteArrayList<Session>()
    private var control: Control? = null
    private var video: Video? = null
    private var messages: Messages? = null
    private var pulse: ScheduledFuture<*>? = null
    private var lastState: String? = null
    private var lastConfig: String? = null
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "couch-phone-pulse").apply { isDaemon = true } }

    @Synchronized fun attach(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        val ctl = Control(c)
        val vid = Video(c)
        control = ctl
        video = vid
        messages = Messages(c, ctl, vid, this)
        PhoneAdb.onChange = { nudge() }
    }

    override fun adopt(peer: Peer, client: Socket, input: DataInputStream, keys: SessionKeys): Boolean {
        synchronized(this) {
            if (app == null || sessions.size >= MAX_SESSIONS) return false
            val session = Session(peer, client, input, keys, this)
            sessions.add(session)
            if (pulse == null) {
                lastState = null
                lastConfig = Config.load(UserFiles(app!!).dir).toJson()
                pulse = ticker.scheduleWithFixedDelay({ tick() }, PULSE_MS, PULSE_MS, TimeUnit.MILLISECONDS)
            }
            session.start()
        }
        return true
    }

    override fun onMessage(session: Session, message: JSONObject) {
        messages?.handle(session, message)
    }

    override fun onClosed(session: Session) {
        video?.stop(session)
        synchronized(this) {
            sessions.remove(session)
            if (sessions.isNotEmpty()) return
            pulse?.cancel(false)
            pulse = null
        }
        control?.release()
    }

    fun isConnected(id: String) = sessions.any { it.peer.id == id && it.isOpen }

    /** A phone was forgotten: it is cut off at once. */
    fun cutOff(id: String) = sessions.filter { it.peer.id == id }.forEach(Session::close)

    fun broadcast(message: JSONObject) = sessions.forEach { it.send(message) }

    /** Tells every phone the config is now [config], and remembers it so the poll doesn't repeat it. */
    fun configChanged(config: Config) {
        lastConfig = config.toJson()
        broadcast(JSONObject().put("t", "config").put("config", JSONObject(lastConfig!!)))
    }

    /** Soon, and once for a burst: state changes (debugging allowed, a screen change) go out together. */
    private fun nudge() {
        ticker.schedule({ tick() }, 500, TimeUnit.MILLISECONDS)
    }

    /** Sends what changed since the last look: this TV's state, and the config if it was edited on the TV. */
    private fun tick() {
        val c = app ?: return
        if (sessions.isEmpty()) return
        try {
            val state = State.build(c).toString()
            if (state != lastState) {
                lastState = state
                broadcast(JSONObject(state))
            }
            val config = Config.load(UserFiles(c).dir)
            if (config.toJson() != lastConfig) configChanged(config)
        } catch (e: Exception) {
            // A failed look is retried on the next one.
        }
    }
}

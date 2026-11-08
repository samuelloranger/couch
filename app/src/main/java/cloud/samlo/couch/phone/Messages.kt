package cloud.samlo.couch.phone

import android.content.Context
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.sync.Sync
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * What a phone's messages do. Pings, keys and text answer straight from the reader thread or the
 * key worker; everything that reads the TV's apps, files or usage runs on one slow worker, in
 * order, so config writes never overlap and a burst of icon requests can't hold up a key.
 */
class Messages(private val context: Context, private val control: Control, private val video: Video, private val phones: Phones) {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE),
        { r -> Thread(r, "couch-phone-work").apply { isDaemon = true } },
    )

    fun handle(session: Session, message: JSONObject) {
        val t = message.getString("t")
        when (t) {
            "ping" -> session.send(JSONObject().put("t", "pong"))
            "key" -> key(session, message)
            "text" -> control.text(message.optString("text").take(Keys.MAX_TEXT)) { it?.let { m -> fail(session, "text", m) } }
            "stream.start" -> {
                control.probe()
                video.start(session, Video.Quality.of(message.optString("quality")))
            }
            "stream.stop" -> video.stop(session)
            "hello", "config.set", "launch", "input", "usage", "icon" -> slow(session, t) { run(session, t, message) }
            else -> fail(session, t, "Unknown message")
        }
    }

    private fun key(session: Session, message: JSONObject) {
        val key = Keys.named(message.optString("key")) ?: return fail(session, "key", "Unknown key")
        val action = message.optString("action", "tap").takeIf { it == "down" || it == "up" } ?: "tap"
        key.linux?.let { if (action == "down") session.held.down(it) else if (action == "up") session.held.up(it) }
        control.key(key, action) { it?.let { m -> fail(session, "key", m) } }
    }

    private fun slow(session: Session, t: String, work: () -> Unit) {
        try {
            worker.execute {
                try {
                    work()
                } catch (e: JSONException) {
                    fail(session, t, "Malformed message")
                } catch (e: Exception) {
                    fail(session, t, "Failed")
                }
            }
        } catch (e: RejectedExecutionException) {
            fail(session, t, "The TV is busy")
        }
    }

    private fun run(session: Session, t: String, message: JSONObject) {
        val dir = UserFiles(context).dir
        val config = Config.load(dir)
        when (t) {
            "hello" -> {
                control.probe()
                session.send(State.build(context))
                session.send(configMessage(config))
                session.send(Schema.build(context))
                session.send(JSONObject().put("t", "apps").put("apps", Catalog.apps(context, config)))
                session.send(JSONObject().put("t", "inputs").put("inputs", Catalog.inputs(context, config)))
            }
            "config.set" -> set(session, message.getJSONObject("patch"), config)
            "launch" -> if (!Catalog.launch(context, message.getString("package"))) fail(session, t, "Can't start that app")
            "input" -> if (!Catalog.switchInput(context, config, message.getString("id"))) fail(session, t, "No such input")
            "usage" -> session.send(State.usage(context))
            "icon" -> {
                val key = message.getString("key")
                session.send(JSONObject().put("t", "icon").put("key", key).apply { Catalog.icon(context, config, key)?.let { put("png", it) } })
            }
        }
    }

    /** Applies a patch, saves it, reloads home if it is showing, and tells every phone the result. */
    private fun set(session: Session, patch: JSONObject, current: Config) {
        val dir = UserFiles(context).dir ?: return fail(session, "config.set", "The TV can't save settings")
        val next = ConfigPatch.apply(current, patch)
        next.save(dir)
        if (next.syncEnabled != current.syncEnabled) Sync.start(context)
        HomeReload.listener?.invoke()
        phones.configChanged(next)
    }

    private fun configMessage(config: Config) = JSONObject().put("t", "config").put("config", JSONObject(config.toJson()))

    private fun fail(session: Session, about: String, text: String) =
        session.send(JSONObject().put("t", "error").put("about", about).put("message", text))

    private companion object {
        const val QUEUE = 64
    }
}

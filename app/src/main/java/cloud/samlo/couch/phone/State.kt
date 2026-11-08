package cloud.samlo.couch.phone

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Build
import cloud.samlo.couch.MediaListener
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.sync.Sync
import cloud.samlo.couch.update.Versions
import org.json.JSONArray
import org.json.JSONObject

/** The `state` message: who this TV is and what it is doing, as far as Couch can tell. */
object State {
    fun build(context: Context): JSONObject {
        val me = Sync.peers(context)
        val out = JSONObject().put("t", "state").put("id", me.id).put("name", me.name)
            .put("model", Build.MODEL ?: "TV").put("android", Build.VERSION.RELEASE ?: "")
            .put("version", Versions.installed(context)).put("adb", PhoneAdb.state.wire)
        runCatching { Usage.front(context) }.getOrNull()?.takeIf { it != context.packageName }?.let { out.put("foreground", it) }
        nowPlaying(context)?.let { out.put("nowPlaying", it) }
        return out
    }

    private fun nowPlaying(context: Context): JSONObject? = try {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val controller = manager?.getActiveSessions(ComponentName(context, MediaListener::class.java))?.firstOrNull()
        val meta = controller?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        if (controller == null || title == null) null else JSONObject().put("title", title).put("app", controller.packageName)
            .apply { meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { put("artist", it) } }
    } catch (e: SecurityException) {
        // Notification access isn't granted: nothing to report.
        null
    }

    /** The `usage` message: this TV's last week and what paired TVs last handed over. */
    fun usage(context: Context): JSONObject {
        val peers = JSONArray()
        Sync.peers(context).snapshots().forEach { peers.put(JSONObject(it.toJson())) }
        return JSONObject().put("t", "usage").put("own", JSONObject(Sync.snapshotNow(context).toJson())).put("peers", peers)
    }

}

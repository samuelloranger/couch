package cloud.samlo.couch.phone

import android.content.Context
import android.os.Build
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
        return out
    }

    /** The `usage` message: this TV's last week and what paired TVs last handed over. */
    fun usage(context: Context): JSONObject {
        val peers = JSONArray()
        Sync.peers(context).snapshots().forEach { peers.put(JSONObject(it.toJson())) }
        return JSONObject().put("t", "usage").put("own", JSONObject(Sync.snapshotNow(context).toJson())).put("peers", peers)
    }

}

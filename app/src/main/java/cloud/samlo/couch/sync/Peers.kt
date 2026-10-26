package cloud.samlo.couch.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A TV this one is paired with: the key they share, and the latest snapshot it handed over. */
class Peer(val id: String, val name: String, val key: ByteArray, val lastSync: Long = 0L, val snapshot: String? = null)

/**
 * This TV's identity and its paired TVs, in one private file: the keys must not sit where other
 * apps or ADB can read them, unlike `config.json`. Every change is saved at once.
 */
class Peers private constructor(private val file: File?, val id: String, name: String, peers: List<Peer>) {
    private val byId = LinkedHashMap<String, Peer>().also { map -> peers.forEach { map[it.id] = it } }

    /** What other TVs see this one as. */
    @get:Synchronized
    var name: String = name
        @Synchronized set(value) {
            field = value.trim().take(Snapshot.MAX_NAME).ifEmpty { field }
            save()
        }

    @Synchronized fun all(): List<Peer> = byId.values.toList()

    @Synchronized fun get(id: String): Peer? = byId[id]

    /** Pairing again with a TV already known replaces its key and keeps nothing else of the old one. */
    @Synchronized fun add(peer: Peer) {
        byId[peer.id] = peer
        save()
    }

    @Synchronized fun forget(id: String) {
        if (byId.remove(id) != null) save()
    }

    /** Keeps what a peer just sent. Ignored for a TV forgotten while the answer was on its way. */
    @Synchronized fun store(id: String, snapshot: Snapshot, at: Long) {
        val peer = byId[id] ?: return
        byId[id] = Peer(peer.id, peer.name, peer.key, at, snapshot.toJson())
        save()
    }

    /** The latest snapshot of every paired TV that has sent one. */
    @Synchronized fun snapshots(): List<Snapshot> = byId.values.mapNotNull { p -> p.snapshot?.let(Snapshot::fromJson) }

    private fun save() {
        val target = file ?: return
        val rows = JSONArray()
        byId.values.forEach { p ->
            rows.put(
                JSONObject().put("id", p.id).put("name", p.name).put("key", Wire.b64(p.key)).put("lastSync", p.lastSync)
                    .apply { p.snapshot?.let { put("snapshot", it) } },
            )
        }
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(JSONObject().put("id", id).put("name", name).put("peers", rows).toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        /** Reads [file], or starts a new identity under [defaultName]; a damaged file starts afresh rather than failing. */
        fun load(file: File?, defaultName: String): Peers {
            val json = runCatching { file?.takeIf { it.isFile }?.readText()?.let(::JSONObject) }.getOrNull()
            // A file that is there but unreadable is kept aside, not written over: it holds every pairing.
            if (json == null && file?.isFile == true) file.renameTo(File(file.parentFile, file.name + ".bad"))
            if (json == null) return Peers(file, Crypto.random(8).joinToString("") { "%02x".format(it) }, defaultName, emptyList())
                .also { it.save() }
            val rows = json.optJSONArray("peers") ?: JSONArray()
            val peers = (0 until rows.length()).mapNotNull { i ->
                runCatching {
                    val r = rows.getJSONObject(i)
                    Peer(r.getString("id"), r.getString("name"), Wire.unb64(r.getString("key")), r.optLong("lastSync", 0L), r.optString("snapshot").ifEmpty { null })
                }.getOrNull()
            }
            return Peers(file, json.optString("id").ifEmpty { Crypto.random(8).joinToString("") { "%02x".format(it) } }, json.optString("name").ifEmpty { defaultName }, peers)
        }
    }
}

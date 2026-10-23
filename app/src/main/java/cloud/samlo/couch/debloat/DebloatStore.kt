package cloud.samlo.couch.debloat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What Couch did, in `debloat.json` next to `config.json`: each package's original state, recorded
 * before Couch first touched it, each removal, so the undo list survives restarts, and the batch
 * flow in progress, so restarting the TV between batches picks up where it stopped. Sits in its
 * own file because config.json is the user's hand-edited setup, and this is Couch's ledger.
 */
class DebloatStore private constructor(private val file: File?) {
    /** original pkg -> "present" | "absent", whatever the TV had before Couch came along. */
    private val originals = LinkedHashMap<String, String>()
    private val applied = LinkedHashMap<String, Removal>()
    val before = Snapshot()

    /** Storage as Couch first found it, for the after-report. Readings may be missing. */
    class Snapshot(var ramFreeMb: Int = -1, var storageFreeMb: Int = -1)

    private data class Removal(val at: Long, val viaShell: Boolean, var undone: Boolean)

    /**
     * Batches still to run: [index] is the batch on screen, [check] what it removed, waiting for
     * the user's look (and ideally a restart), [boot] the TV's boot count when it ran.
     */
    class Flow(val batches: List<List<String>>, var index: Int = 0, var check: List<String> = emptyList(), var boot: Int = -1)

    var flow: Flow? = null
        private set

    fun startFlow(batches: List<List<String>>) {
        flow = Flow(batches)
        save()
    }

    /** The batch on screen ran: [removed] is what actually went, [boot] the boot count then. */
    fun ran(removed: List<String>, boot: Int) {
        flow?.let {
            it.check = removed
            it.boot = boot
            save()
        }
    }

    /** Moves past the checked batch; null once none are left, and the flow is over. */
    fun next(): List<String>? {
        val f = flow ?: return null
        f.index++
        f.check = emptyList()
        f.boot = -1
        if (f.index >= f.batches.size) flow = null
        save()
        return flow?.batches?.get(f.index)
    }

    fun stopFlow() {
        flow = null
        save()
    }

    /** Records what the TV had; later calls for the same package change nothing. */
    fun record(pkg: String, present: Boolean) {
        originals.putIfAbsent(pkg, if (present) "present" else "absent")
        save()
    }

    fun removed(pkg: String, viaShell: Boolean) {
        applied[pkg] = Removal(System.currentTimeMillis(), viaShell, false)
        save()
    }

    /** Marks one removal undone; restoring an untouched package is a no-op. */
    fun undone(pkg: String) {
        if (applied[pkg]?.undone == false) {
            applied[pkg]!!.undone = true
            save()
        }
    }

    fun tally(): Tally {
        val done = applied.filterValues { !it.undone }
        return Tally(done.count { it.value.viaShell }, done.size, applied.size)
    }

    data class Tally(val fromShell: Int, val active: Int, val ever: Int)

    /** Packages Couch removed and has not undone, the undo list's rows. */
    fun entries(): List<String> = applied.keys.filter { applied[it]?.undone != true }

    /** Records storage as Couch first found it; once set, later scans change nothing. */
    fun saw(ramFreeMb: Int, storageFreeMb: Int) {
        if (before.ramFreeMb < 0) {
            before.ramFreeMb = ramFreeMb
            before.storageFreeMb = storageFreeMb
            save()
        }
    }

    private fun save() {
        file ?: return
        val out = JSONObject()
            .put("originals", JSONObject(originals))
            .put("before", JSONObject()
                .put("ramFreeMb", before.ramFreeMb)
                .put("storageFreeMb", before.storageFreeMb))
        val rows = JSONArray()
        applied.forEach { (pkg, r) ->
            rows.put(JSONObject().put("pkg", pkg).put("at", r.at).put("shell", r.viaShell).put("undone", r.undone))
        }
        out.put("removed", rows)
        flow?.let { f ->
            out.put("flow", JSONObject()
                .put("batches", JSONArray(f.batches.map { JSONArray(it) }))
                .put("index", f.index)
                .put("check", JSONArray(f.check))
                .put("boot", f.boot))
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(out.toString(2))
        tmp.renameTo(file)
    }

    companion object {
        private fun file(dir: File?) = dir?.let { File(it, FILE) }

        fun load(dir: File?): DebloatStore = DebloatStore(file(dir)).also { s ->
            val json = runCatching { file(dir)?.takeIf { it.isFile }?.readText() }.getOrNull()
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return s
            json.optJSONObject("originals")?.let { o ->
                o.keys().asSequence().forEach { s.originals[it] = o.getString(it) }
            }
            json.optJSONObject("before")?.let {
                s.before.ramFreeMb = it.optInt("ramFreeMb", -1)
                s.before.storageFreeMb = it.optInt("storageFreeMb", -1)
            }
            json.optJSONObject("flow")?.let { f ->
                val batches = f.optJSONArray("batches")?.let { b -> (0 until b.length()).map { strings(b.getJSONArray(it)) } }
                if (!batches.isNullOrEmpty()) {
                    s.flow = Flow(batches, f.optInt("index", 0).coerceIn(0, batches.size - 1), strings(f.optJSONArray("check")), f.optInt("boot", -1))
                }
            }
            json.optJSONArray("removed")?.let { rows ->
                (0 until rows.length()).forEach {
                    val r = rows.getJSONObject(it)
                    s.applied[r.getString("pkg")] =
                        Removal(r.optLong("at", 0), r.optBoolean("shell", true), r.optBoolean("undone", false))
                }
            }
        }

        private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map(it::getString) }.orEmpty()

        const val FILE = "debloat.json"
    }
}

package cloud.samlo.couch.debloat

import android.content.Context
import cloud.samlo.couch.debloat.DebloatList.Entry
import cloud.samlo.couch.debloat.DebloatList.Keep
import cloud.samlo.couch.debloat.DebloatList.Level
import cloud.samlo.couch.debloat.DebloatList.Why

/**
 * The data behind [DebloatList], read from `assets/debloat/lists.txt`, which says where it comes
 * from and how it's laid out. It lives in assets because the APK compresses them: as code, the
 * same lists would be stored uncompressed in the dex, and the APK has a size budget.
 */
class Catalog(text: String) {
    /** Never offered, never removed: removing one can leave a TV that won't start. */
    val protected = HashSet<String>()

    /** Removable, but something you'd miss goes with them; shown greyed, never offered. */
    val keep = HashMap<String, Keep>()

    /** Google's extras (no manufacturer), then each brand's list keyed by `Build.MANUFACTURER` prefixes. */
    val lists = ArrayList<Pair<List<String>, ArrayList<Entry>>>()

    /** Every package any section names: what the scan looks up. */
    val known: Set<String> by lazy { protected + keep.keys + lists.flatMap { (_, entries) -> entries.map { it.pkg } } }

    init {
        var section = ""
        text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            when {
                line.startsWith("[") -> section = line
                section == "[protected]" -> protected += line
                section == "[keep]" -> line.split(' ').let { keep[it[0]] = Keep.valueOf(it[1]) }
                line.startsWith("@") ->
                    lists += line.removePrefix("@").split(',').map(String::trim).filter { it.isNotEmpty() } to ArrayList()
                else -> line.split(' ').let { (pkg, level, why) -> lists.last().second += Entry(pkg, Level.valueOf(level), Why.valueOf(why)) }
            }
        }
    }

    companion object {
        private const val ASSET = "debloat/lists.txt"
        @Volatile private var loaded: Catalog? = null

        /** Read once per process; small enough (about 16 KB of text) to parse on the scan's worker. */
        fun of(context: Context): Catalog = loaded ?: synchronized(this) {
            loaded ?: Catalog(context.assets.open(ASSET).bufferedReader().use { it.readText() }).also { loaded = it }
        }
    }
}

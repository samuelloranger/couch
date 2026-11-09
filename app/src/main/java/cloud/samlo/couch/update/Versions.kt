package cloud.samlo.couch.update

import android.content.Context

/** `X.Y.Z` versions, as the release tags and `versionName` write them. */
object Versions {
    /**
     * `v1.2.3` or `1.2` as numbers; null for anything else. A suffix like `-beta.1` is ignored here
     * (see [isNewer] for how it orders).
     */
    fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").substringBefore('-')
        val parts = core.split('.')
        if (core.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { part ->
            if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
            part.toIntOrNull() ?: return null
        }
        return numbers + List(3 - numbers.size) { 0 }
    }

    /** True only when both parse and [candidate] is strictly newer, so `0.10.0` beats `0.2.0`. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(installed) ?: return false
        val diff = a.zip(b).firstOrNull { (x, y) -> x != y }
        if (diff != null) return diff.first > diff.second
        // Same X.Y.Z: a final release beats its pre-releases, a higher beta number beats a lower one.
        // shortcut: the installed versionName carries no suffix, so a suffix-free installed build ties with its own beta and final; carry the tag into versionName to decide those.
        val x = preOf(candidate)
        val y = preOf(installed)
        return when {
            x == y -> false
            y == null -> false
            x == null -> true
            else -> x.zip(y).firstOrNull { (p, q) -> p != q }?.let { (p, q) ->
                val m = p.toIntOrNull()
                val n = q.toIntOrNull()
                if (m != null && n != null) m > n else p > q
            } ?: (x.size > y.size)
        }
    }

    private fun preOf(version: String): List<String>? =
        version.trim().substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.')

    fun installed(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

package cloud.samlo.couch.update

import android.content.Context

/** `X.Y.Z` versions, as the release tags and `versionName` write them. */
object Versions {
    /**
     * `v1.2.3` or `1.2` as numbers; null for anything else. A suffix like `-beta.1` is ignored, the
     * way `versionName` drops it, so a pre-release tag counts as its `X.Y.Z`.
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
        val diff = a.zip(b).firstOrNull { (x, y) -> x != y } ?: return false
        return diff.first > diff.second
    }

    fun installed(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

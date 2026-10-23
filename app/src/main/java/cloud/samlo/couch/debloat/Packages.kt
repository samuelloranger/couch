package cloud.samlo.couch.debloat

import android.content.Context
import android.content.pm.PackageManager

/**
 * What the package manager says, without the shell: whether an app is installed for this user,
 * and its name. A `pm uninstall --user 0` makes an app not installed here, so the same check
 * confirms a removal and a restore. The manifest's QUERY_ALL_PACKAGES keeps Android 11+ from
 * hiding the system packages asked about.
 */
object Packages {
    fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun installedOf(context: Context, pkgs: Collection<String>): Set<String> =
        pkgs.filterTo(HashSet()) { installed(context, it) }

    /**
     * The app's own name; the last segment of the package when it's gone or nameless. Some system
     * apps are "named" after a Java class, which shortens to its last segment too.
     */
    fun label(context: Context, pkg: String): String {
        val name = try {
            val pm = context.packageManager
            pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm).toString().trim()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
        return when {
            name.isEmpty() || name == pkg -> pkg.substringAfterLast('.')
            ' ' !in name && name.count { it == '.' } >= 2 -> name.substringAfterLast('.')
            else -> name
        }
    }
}

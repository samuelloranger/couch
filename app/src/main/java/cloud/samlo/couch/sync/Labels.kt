package cloud.samlo.couch.sync

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Every name an installed app has, one per language, for matching it with the same service under
 * another package on another TV. The label the launcher shows depends on the TV's language, so
 * the system's own languages plus English and French are all read.
 */
object Labels {
    private val extra = listOf(Locale.ENGLISH, Locale.FRENCH)

    /** Distinct names, the one shown on this TV first; empty when the package can't be read. */
    fun of(context: Context, pkg: String): List<String> {
        val info = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        val shown = info.loadLabel(context.packageManager).toString()
        if (info.labelRes == 0) return listOf(shown)
        val system = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it] } }
        val names = (system + extra).distinct().mapNotNull { locale ->
            try {
                val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
                context.createPackageContext(pkg, 0).createConfigurationContext(config).resources.getString(info.labelRes)
            } catch (e: Exception) {
                // A package that vanished mid-read, or a string it doesn't have in that language.
                null
            }
        }
        return (listOf(shown) + names).filter { it.isNotBlank() }.distinct()
    }

    /** Names never change while the process lives, and reading them is slow: once per package. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    fun all(context: Context, pkgs: Collection<String>): Map<String, List<String>> =
        pkgs.associateWith { pkg -> known.getOrPut(pkg) { of(context, pkg) } }.filterValues { it.isNotEmpty() }
}

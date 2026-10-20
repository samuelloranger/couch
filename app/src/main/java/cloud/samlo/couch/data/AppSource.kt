package cloud.samlo.couch.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf
import java.io.File
import java.text.Collator
import java.util.Locale

/** The Apps row: every TV-launchable app but Couch itself, pinned ones first, then by name. */
object AppSource {
    fun tiles(
        context: Context,
        config: Config,
        iconsDir: File?,
        w: Int = context.dp(Tokens.APP_W),
        h: Int = context.dp(Tokens.APP_H),
        radius: Float = context.dpf(Tokens.CARD_RADIUS),
    ): List<Tile> {
        val pm = context.packageManager
        val collator = Collator.getInstance(Locale.getDefault())
        return launchable(pm)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.packageName !in config.hiddenApps }
            .map { it to (config.names[it.activityInfo.packageName] ?: it.loadLabel(pm).toString()) }
            .sortedWith { (a, aLabel), (b, bLabel) ->
                val ai = rank(config, a.activityInfo.packageName)
                val bi = rank(config, b.activityInfo.packageName)
                if (ai != bi) ai.compareTo(bi) else collator.compare(aLabel, bLabel)
            }
            .map { (info, label) -> tile(context, info, label, iconsDir, w, h, radius) }
    }

    /** Every app that shows up in a TV launcher, one entry per package. */
    fun launchable(pm: PackageManager): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }

    /** Position in the user's pinned list; unpinned apps sort after every pinned one. */
    private fun rank(config: Config, pkg: String) = config.pinnedApps.indexOf(pkg).let { if (it < 0) Int.MAX_VALUE else it }

    private fun tile(context: Context, info: ResolveInfo, label: String, iconsDir: File?, w: Int, h: Int, radius: Float): Tile {
        val pm = context.packageManager
        val pkg = info.activityInfo.packageName
        val updated = try {
            pm.getPackageInfo(pkg, 0).lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            0L
        }
        val banner = Banners.memoized("app|$pkg|$w|$radius|$updated|${Banners.mtime(iconsDir, pkg)}") {
            Banners.custom(iconsDir, pkg, w, h, context, radius)
                ?: (info.activityInfo.loadBanner(pm) ?: info.activityInfo.applicationInfo.loadBanner(pm))
                    ?.let { Banners.render(it, w, h, radius) }
        }
        val intent = (pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).setClassName(pkg, info.activityInfo.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return Tile(
            key = pkg,
            kind = Tile.Kind.APP,
            label = label,
            banner = banner,
            icon = if (banner == null) info.loadIcon(pm) else null,
            packageName = pkg,
            intent = intent,
        )
    }
}

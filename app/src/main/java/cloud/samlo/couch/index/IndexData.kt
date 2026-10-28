package cloud.samlo.couch.index

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import cloud.samlo.couch.data.AppSource
import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Banners
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.InputSource
import cloud.samlo.couch.data.Programs
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.sync.Sync
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf
import java.io.File

/**
 * One app in the list: its launch [app] tile (banner rendered at card size, shown where the
 * channel would be when it has none), a small rounded [icon], the first channel it publishes,
 * and its last week of use.
 */
class IndexEntry(
    val app: Tile,
    val icon: Bitmap?,
    /** "Netflix", or "Jellyfin, Recently added" when the channel name doesn't say the app. */
    val channelTitle: String?,
    val programs: List<Tile>,
    val usage: AppUsage?,
) {
    val key: String get() = app.key
    val signature: String = "${app.signature}|${icon?.generationId}|$channelTitle|" +
        "${usage?.perDayMs?.joinToString(",")}|${usage?.opens}|${usage?.lastUsed}|" + programs.joinToString(";") { it.signature }
}

class IndexData(
    val entries: List<IndexEntry>,
    /** False without usage access: no screen time, apps in the user's order. */
    val usageKnown: Boolean,
    val inputs: List<Tile>,
)

/** Builds [IndexData] on the loader thread. */
object IndexBuilder {
    fun build(context: Context, config: Config, iconsDir: File?): IndexData {
        val apps = AppSource.tiles(
            context, config, iconsDir,
            context.dp(IndexTokens.cardWidth(-1)), context.dp(IndexTokens.CARD_H), context.dpf(IndexTokens.CARD_RADIUS),
        )
        val usage = Usage.lastWeek(context)?.let { if (config.syncEnabled && config.syncAllTvs) Sync.withOtherTvs(context, it) else it }
        val channels = Programs.channels(context, config).groupBy { it.packageName }
        // Apps keep the user's order among equals (pinned first, then by name).
        val ordered = if (usage == null) apps else apps.sortedByDescending { usage[it.key]?.weekMs ?: 0L }
        val entries = ordered.map { tile ->
            val channel = channels[tile.key]?.firstOrNull()
            IndexEntry(
                app = tile,
                icon = icon(context, tile.key),
                channelTitle = channel?.let { Programs.title(context, it.packageName, it.name, ", ") },
                programs = channel?.tiles.orEmpty(),
                usage = usage?.get(tile.key),
            )
        }
        return IndexData(entries, usage != null, InputSource.tiles(context, config, iconsDir))
    }

    /** The app's launcher icon as a small rounded square, rendered once per app version. */
    private fun icon(context: Context, pkg: String): Bitmap? {
        val pm = context.packageManager
        val updated = try {
            pm.getPackageInfo(pkg, 0).lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val size = context.dp(IndexTokens.ICON)
        return Banners.memoized("icon|$pkg|$size|$updated") {
            try {
                Banners.render(pm.getApplicationIcon(pkg), size, size, context.dpf(AppRow.ICON_RADIUS))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}

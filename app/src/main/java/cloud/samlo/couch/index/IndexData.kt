package cloud.samlo.couch.index

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import cloud.samlo.couch.R
import cloud.samlo.couch.data.AppSource
import cloud.samlo.couch.data.AppOrder
import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Banners
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.InputSource
import cloud.samlo.couch.data.Programs
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.sync.Sync
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf
import java.io.File

/**
 * One app in the list: its launch [app] tile (banner rendered at card size, shown where the
 * channel would be when it has none), a small rounded [icon], its available channels,
 * and its last week of use.
 *
 * The Continue watching row is the one entry that is not an app: its key is [CONTINUE] and its
 * single channel holds Watch Next programs from every app.
 */
class IndexChannel(val id: Long, val title: String, val programs: List<Tile>)

class IndexEntry(
    val app: Tile,
    val icon: Bitmap?,
    val channels: List<IndexChannel>,
    preferredChannel: String?,
    val usage: AppUsage?,
) {
    val channel = channels.firstOrNull { it.id.toString() == preferredChannel } ?: channels.firstOrNull()
    val channelTitle get() = channel?.title
    val programs get() = channel?.programs.orEmpty()
    val key: String get() = app.key
    val stripKey: String get() = "$key|${channel?.id}"
    val signature: String = "${app.signature}|${icon?.generationId}|${channel?.id}|" +
        "${usage?.perDayMs?.joinToString(",")}|${usage?.opens}|${usage?.lastUsed}|" +
        channels.joinToString(";") { "${it.id}|${it.title}|" + it.programs.joinToString(";") { tile -> tile.signature } }

    /** True for the Continue watching row, which stands for no app: OK resumes its first program. */
    val continues: Boolean get() = key == CONTINUE

    companion object {
        /** The Continue watching row's key: never an app, so nothing that lists apps may keep it. */
        const val CONTINUE = "couch:continue"
    }
}

class IndexData(
    val entries: List<IndexEntry>,
    /** False without usage access: no screen time, apps in the user's order. */
    val usageKnown: Boolean,
    val inputs: List<Tile>,
    /** False while the channels are still being read: the column waits rather than say an app has none. */
    val channelsKnown: Boolean = true,
    /** Watch Next programs from every app, shown as the Continue watching row while the setting is on. */
    val watchNext: List<Tile> = emptyList(),
)

/** Builds [IndexData] on the loader thread. */
object IndexBuilder {
    /** [withChannels] false skips the preview-channel queries and the other-TV merge, for callers that list only apps and inputs. */
    fun build(context: Context, config: Config, iconsDir: File?, withChannels: Boolean = true): IndexData {
        val apps = apps(context, config, iconsDir, otherTvs = withChannels)
        return if (withChannels) withChannels(context, config, apps) else apps
    }

    /** The apps in order, with icons and screen time but no channels yet: the quick part on a cold start. */
    fun apps(context: Context, config: Config, iconsDir: File?, otherTvs: Boolean = true): IndexData {
        val apps = AppSource.tiles(
            context, config, iconsDir,
            context.dp(IndexTokens.cardWidth(-1)), context.dp(IndexTokens.CARD_H), context.dpf(IndexTokens.CARD_RADIUS),
        )
        val usage = Usage.lastWeek(context)?.let { if (otherTvs && config.syncEnabled && config.syncAllTvs) Sync.withOtherTvs(context, it) else it }
        val byKey = apps.associateBy { it.key }
        val ordered = AppOrder.sorted(apps.map { it.key }, config, usage?.mapValues { it.value.weekMs })
        val entries = ordered.map { key ->
            val tile = byKey.getValue(key)
            IndexEntry(app = tile, icon = icon(context, tile.key), channels = emptyList(), preferredChannel = null, usage = usage?.get(tile.key))
        }
        return IndexData(entries, usage != null, InputSource.tiles(context, config, iconsDir), channelsKnown = false)
    }

    /** [data] with each app's preview channels: the slow part, about a second on a weak TV. */
    fun withChannels(context: Context, config: Config, data: IndexData): IndexData {
        val channels = Programs.channels(context, config).groupBy { it.packageName }
        val entries = data.entries.map { e ->
            IndexEntry(
                app = e.app,
                icon = e.icon,
                channels = channels[e.key].orEmpty().map { IndexChannel(it.id, Programs.title(context, it.packageName, it.name, ", "), it.tiles) },
                preferredChannel = config.preferredChannels[e.key],
                usage = e.usage,
            )
        }
        // Watch Next changed since the quick part: it is read here, with the channels, off the main thread.
        val watchNext = if (config.continueWatching) Programs.watchNext(context, config) else emptyList()
        val all = if (watchNext.isEmpty()) entries else listOf(continueEntry(context, watchNext)) + entries
        return IndexData(all, data.usageKnown, data.inputs, watchNext = watchNext)
    }

    /** The row above the apps: Watch Next programs as one channel, under a play mark rather than an app's icon. */
    private fun continueEntry(context: Context, watchNext: List<Tile>): IndexEntry {
        val label = context.getString(R.string.continue_watching)
        return IndexEntry(
            app = Tile(key = IndexEntry.CONTINUE, kind = Tile.Kind.APP, label = label, intent = Intent()),
            icon = continueIcon(context),
            channels = listOf(IndexChannel(CONTINUE_CHANNEL, label, watchNext)),
            preferredChannel = null,
            usage = null,
        )
    }

    /** The Continue watching icon: the accent at 20% over the surface, with a play mark on it. */
    private fun continueIcon(context: Context): Bitmap? {
        val size = context.dp(IndexTokens.LIST_ICON)
        // Part of the key, so Customize's new accent renders again.
        return Banners.memoized("continue|$size|${Tokens.accent}") {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val radius = context.dpf(AppRow.ICON_RADIUS)
            val square = RectF(0f, 0f, size.toFloat(), size.toFloat())
            canvas.drawRoundRect(square, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.surface })
            canvas.drawRoundRect(square, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (Tokens.accent and 0x00FFFFFF) or 0x33000000
            })
            val triangle = context.getDrawable(R.drawable.ic_continue)?.mutate() ?: return@memoized bitmap
            triangle.setTint(Tokens.accent)
            val art = size * 55 / 100
            val top = (size - art) / 2
            triangle.setBounds(top, top, top + art, top + art)
            triangle.draw(canvas)
            bitmap
        }
    }

    /** The app's launcher icon as a small rounded square, rendered once per app version. */
    private fun icon(context: Context, pkg: String): Bitmap? {
        val pm = context.packageManager
        val updated = try {
            pm.getPackageInfo(pkg, 0).lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val size = context.dp(IndexTokens.LIST_ICON)
        return Banners.memoized("icon|$pkg|$size|$updated") {
            try {
                Banners.render(pm.getApplicationIcon(pkg), size, size, context.dpf(AppRow.ICON_RADIUS))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /** Continue watching is one channel, numbered below every app channel and recommendation channel. */
    private const val CONTINUE_CHANNEL = -2L
}

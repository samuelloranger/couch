package cloud.samlo.couch.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cloud.samlo.couch.MediaListener
import cloud.samlo.couch.R
import kotlin.math.abs

/**
 * Fire TV's stand-in for preview channels: apps there post what they recommend as notifications
 * of category "recommendation", with a title, a synopsis, a background image (an https or
 * content URI), watch progress and a launch intent. Apps sort them into notification groups
 * ("continueWatching", "trendingNow"); each group makes one channel. Read through [MediaListener],
 * so they need notification access.
 */
object Recommendations {
    private const val EXTRA_RANK = "com.amazon.extra.RANK"

    class Item(
        val key: String,
        val packageName: String,
        /** The notification group, or null when the app doesn't group its recommendations. */
        val group: String?,
        val title: String,
        val text: String?,
        val image: String?,
        val progress: Float,
        val rank: Int,
        val launch: PendingIntent?,
    )

    /** One app's recommendations in one notification group, best ranked first. */
    class Group(val packageName: String, val key: String?, val items: List<Item>) {
        val id: Long = channelId(packageName, key)
    }

    /** What [groups] last returned, for screens that shouldn't wait on Android (settings pages). */
    @Volatile
    var lastKnown: List<Group> = emptyList()
        private set

    /**
     * Every app's recommendation groups, each app's best ranked group first. Empty without
     * notification access. Call off the main thread.
     */
    fun groups(): List<Group> = group(read()).also { lastKnown = it }

    fun group(items: List<Item>): List<Group> = items
        .groupBy { it.packageName to it.group }
        .map { (key, items) -> Group(key.first, key.second, items.sortedBy { it.rank }) }
        // Ranks run across an app's groups, so its best group comes first.
        .sortedWith(compareBy({ it.packageName }, { it.items.first().rank }))

    private fun read(): List<Item> {
        val listener = MediaListener.connected ?: return emptyList()
        val active = try {
            listener.activeNotifications
        } catch (e: Exception) {
            // Access revoked between the check and the call: no recommendations.
            null
        } ?: return emptyList()
        return active.mapNotNull { item(it.key, it.packageName, it.notification) }
    }

    /**
     * A stable negative id per app and group, so its channel can be shown, hidden and ordered like
     * the others. Ungrouped recommendations keep the id the app's single channel had before groups.
     */
    fun channelId(packageName: String, group: String? = null): Long =
        -(abs((if (group == null) packageName else "$packageName|$group").hashCode().toLong()) + 1)

    /** The channel name: the group key in words ("trendingNow" is "Trending now"), translated when known. */
    fun name(context: Context, group: String?): String = when (group) {
        null -> context.getString(R.string.channel_recommended)
        "continueWatching" -> context.getString(R.string.channel_continue_watching)
        "trendingNow" -> context.getString(R.string.channel_trending_now)
        else -> words(group)
    }

    /** "trendingNow", "trending_now" and "trending-now" all read "Trending now". */
    fun words(key: String): String = key
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[a-zA-Z])(?=[0-9])"), " ")
        .replace(Regex("[_\\-]+"), " ")
        .trim()
        .lowercase()
        .replaceFirstChar { it.uppercase() }

    fun tiles(context: Context, items: List<Item>): List<Tile> = items.map { item ->
        val fallback = context.packageManager.getLeanbackLaunchIntentForPackage(item.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(item.packageName)
            ?: Intent()
        Tile(
            key = "rec:" + item.key,
            kind = Tile.Kind.PROGRAM,
            label = item.title,
            packageName = item.packageName,
            imageUrl = item.image,
            backdropUrl = item.image,
            meta = AppLabels.of(context, item.packageName),
            description = item.text,
            progress = item.progress,
            intent = fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingIntent = item.launch,
        )
    }

    private fun item(key: String, packageName: String, n: Notification): Item? {
        if (n.category != Notification.CATEGORY_RECOMMENDATION) return null
        val launch = n.contentIntent ?: return null
        val x = n.extras ?: return null
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val max = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val done = x.getInt(Notification.EXTRA_PROGRESS, 0)
        return Item(
            key = key,
            packageName = packageName,
            group = n.group?.takeIf { it.isNotBlank() },
            title = title,
            text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() && it != title },
            image = x.getString(Notification.EXTRA_BACKGROUND_IMAGE_URI),
            progress = if (max > 0 && done > 0) (done.toFloat() / max).coerceIn(0f, 1f) else -1f,
            rank = x.getInt(EXTRA_RANK, Int.MAX_VALUE),
            launch = launch,
        )
    }
}

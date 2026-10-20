package cloud.samlo.couch.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cloud.samlo.couch.MediaListener
import kotlin.math.abs

/**
 * Fire TV's stand-in for preview channels: apps there post what they recommend as notifications
 * of category "recommendation", with a title, a synopsis, a background image (an https or
 * content URI), watch progress and a launch intent. Each app's recommendations make one channel.
 * Read through [MediaListener], so they need notification access.
 */
object Recommendations {
    private const val EXTRA_RANK = "com.amazon.extra.RANK"

    class Item(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String?,
        val image: String?,
        val progress: Float,
        val rank: Int,
        val launch: PendingIntent,
    )

    /** What [byPackage] last returned, for screens that shouldn't wait on Android (settings pages). */
    @Volatile
    var lastKnown: Map<String, List<Item>> = emptyMap()
        private set

    /** Every app's recommendations, best ranked first. Empty without notification access. Call off the main thread. */
    fun byPackage(): Map<String, List<Item>> = read().also { lastKnown = it }

    private fun read(): Map<String, List<Item>> {
        val listener = MediaListener.connected ?: return emptyMap()
        val active = try {
            listener.activeNotifications
        } catch (e: Exception) {
            // Access revoked between the check and the call: no recommendations.
            null
        } ?: return emptyMap()
        return active.mapNotNull { item(it.key, it.packageName, it.notification) }
            .groupBy { it.packageName }
            .mapValues { (_, items) -> items.sortedBy { it.rank } }
    }

    /** A stable negative id per app, so its channel can be shown, hidden and ordered like the others. */
    fun channelId(packageName: String): Long = -(abs(packageName.hashCode().toLong()) + 1)

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
            title = title,
            text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() && it != title },
            image = x.getString(Notification.EXTRA_BACKGROUND_IMAGE_URI),
            progress = if (max > 0 && done > 0) (done.toFloat() / max).coerceIn(0f, 1f) else -1f,
            rank = x.getInt(EXTRA_RANK, Int.MAX_VALUE),
            launch = launch,
        )
    }
}

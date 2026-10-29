package cloud.samlo.couch.data

import android.content.Context
import android.content.Intent
import android.database.Cursor

/** Turns one preview-program row into a card. */
internal object ProgramTile {
    fun from(context: Context, c: Cursor, pkg: String): Tile? {
        val title = c.string("title")?.takeIf { it.isNotBlank() } ?: return null
        val intent = launchIntent(context, c, pkg) ?: return null

        val poster = c.string("poster_art_uri")
        val thumb = c.string("thumbnail_uri")
        val ratio = c.int("poster_art_aspect_ratio", -1)
        val position = c.long("last_playback_position_millis")
        val duration = c.long("duration_millis")
        val started = duration > 0 && position > 0

        return Tile(
            key = "pp:" + c.long("_id"),
            kind = Tile.Kind.PROGRAM,
            label = title,
            packageName = pkg,
            imageUrl = poster ?: thumb,
            imageRatio = ratio,
            description = c.string("short_description") ?: c.string("long_description"),
            progress = if (started) (position.toFloat() / duration).coerceIn(0f, 1f) else -1f,
            intent = intent,
        )
    }

    /** The program's own deep link, else the app's launcher entry. */
    private fun launchIntent(context: Context, c: Cursor, pkg: String): Intent? {
        val intent = c.string("intent_uri")?.let {
            try {
                Intent.parseUri(it, Intent.URI_INTENT_SCHEME)
            } catch (e: Exception) {
                null
            }
        } ?: context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: return null
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

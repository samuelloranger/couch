package cloud.samlo.couch.data

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.Toast
import cloud.samlo.couch.R

/** One card's data. Bitmaps are rendered at the card's exact pixel size before they reach the UI. */
class Tile(
    val key: String,
    val kind: Kind,
    val label: String,
    private val intent: Intent,
    val subtitle: String? = null,
    val banner: Bitmap? = null,
    val imageUrl: String? = null,
    /** A TvContract poster aspect ratio constant, or -1 for 16:9. */
    val imageRatio: Int = -1,
    val icon: Drawable? = null,
    val iconRes: Int = 0,
    val dimmed: Boolean = false,
    val inputId: String? = null,
    val packageName: String? = null,
    val backdropUrl: String? = null,
    val meta: String? = null,
    val description: String? = null,
    val progress: Float = -1f,
    /** Solid card colour behind the icon, for brand marks. */
    val cardColor: Int? = null,
    val iconWide: Boolean = false,
    /** Launches instead of [intent] when set (Fire TV recommendations); [intent] stays the fallback. */
    private val pendingIntent: PendingIntent? = null,
) {
    enum class Kind { APP, INPUT, PROGRAM }

    /** Changes when the card would look different, so unchanged rows are not rebuilt. */
    val signature: String get() = "$key|$label|$subtitle|$imageUrl|$dimmed|$iconRes|$cardColor|${(progress * 100).toInt()}|${banner?.generationId}"

    fun launch(context: Context) {
        if (pendingIntent != null) {
            try {
                pendingIntent.send()
                return
            } catch (e: PendingIntent.CanceledException) {
                // The app withdrew it: open the app instead.
            }
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            failed(context)
        } catch (e: SecurityException) {
            failed(context)
        }
    }

    private fun failed(context: Context) =
        Toast.makeText(context, context.getString(R.string.launch_failed, label), Toast.LENGTH_SHORT).show()
}

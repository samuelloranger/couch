package cloud.samlo.couch.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import cloud.samlo.couch.R

/** Starts [intent]; TVs (Fire TV among them) lack some system screens, so a missing one is a toast, not a crash. */
fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.screen_unavailable, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(this, R.string.screen_unavailable, Toast.LENGTH_SHORT).show()
    }
}

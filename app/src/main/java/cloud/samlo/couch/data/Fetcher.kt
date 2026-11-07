package cloud.samlo.couch.data

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens https URLs and content URIs. Blocking: call from a worker thread. Plain http is refused:
 * the app allows cleartext only for devices on the home network, never for internet content.
 */
class Fetcher(private val context: Context) {
    fun open(url: String): InputStream = when (Uri.parse(url).scheme) {
        "http" -> throw java.io.IOException("Plain HTTP refused: $url")
        "https" -> {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.inputStream
        }
        else -> context.contentResolver.openInputStream(Uri.parse(url))
            ?: throw IllegalStateException("No stream for $url")
    }

    /** The body as text, or null on any failure: callers fall back to their cache. */
    fun text(url: String): String? = try {
        open(url).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TIMEOUT_MS = 8000
    }
}

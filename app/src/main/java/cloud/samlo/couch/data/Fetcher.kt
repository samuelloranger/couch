package cloud.samlo.couch.data

import android.content.Context
import android.net.Uri
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Opens https URLs and content URIs. Blocking: call from a worker thread. Plain http is refused:
 * the app allows cleartext only for devices on the home network, never for internet content.
 */
class Fetcher(context: Context) {
    // The application context: a Fetcher outlives the Activity that made it while its pool drains.
    private val context = context.applicationContext

    fun open(url: String): InputStream = when (Uri.parse(url).scheme) {
        "http" -> throw java.io.IOException("Plain HTTP refused: $url")
        "https" -> {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw java.io.IOException("HTTP ${response.code}: $url")
            }
            response.body.byteStream()
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
        val client = Http.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

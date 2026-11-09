package cloud.samlo.couch.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Local copies of remote art, trimmed to a size budget, oldest first. */
class DiskCache(context: Context, private val fetcher: Fetcher) {
    private val dir = File(context.cacheDir, "img").apply { mkdirs() }
    private var lastTrim = 0L

    /** Local copy of [url]: remote art goes through the cache, local paths are used as they are. */
    fun file(url: String): File? {
        if (url.startsWith("/")) return File(url).takeIf { it.isFile }
        val cached = File(dir, sha1(url))
        if (cached.isFile && cached.length() > 0) return cached
        // Its own temp file: the same URL can be fetched at two sizes at once.
        val tmp = File.createTempFile(cached.name, ".tmp", dir)
        try {
            fetcher.open(url).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(cached)) return null
        } finally {
            tmp.delete()
        }
        trim()
        return cached
    }

    private fun trim() {
        val now = System.currentTimeMillis()
        if (now - lastTrim < TRIM_EVERY_MS) return
        lastTrim = now
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > BUDGET) file.delete()
        }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUDGET = 60L * 1024 * 1024
        const val TRIM_EVERY_MS = 60_000L
    }
}

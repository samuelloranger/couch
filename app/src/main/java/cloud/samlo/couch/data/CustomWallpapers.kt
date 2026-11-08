package cloud.samlo.couch.data

import java.io.File
import java.util.Locale

/** User-supplied slideshow, with the original single-file setup kept as a fallback. */
object CustomWallpapers {
    fun paths(userDir: File?): List<String> {
        userDir ?: return emptyList()
        val pictures = File(userDir, "wallpapers").listFiles().orEmpty()
            .filter {
                it.isFile && it.length() > 0 && !it.name.startsWith(".") &&
                    it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")
            }
            .sortedWith(compareBy<File> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
            .map { it.path }
        if (pictures.isNotEmpty()) return pictures
        return listOfNotNull(File(userDir, "wallpaper.jpg").takeIf { it.isFile && it.length() > 0 }?.path)
    }
}

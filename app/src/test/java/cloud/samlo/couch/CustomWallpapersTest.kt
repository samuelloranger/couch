package cloud.samlo.couch

import cloud.samlo.couch.data.CustomWallpapers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomWallpapersTest {
    @get:Rule val temp = TemporaryFolder()

    private fun picture(path: String) = File(temp.root, path).apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(1))
    }

    @Test fun returnsAllSupportedImagesInStableFilenameOrder() {
        picture("wallpapers/30-marvel.WEBP")
        picture("wallpapers/10-bond.JPG")
        picture("wallpapers/20-star-wars.png")
        picture("wallpapers/40-jurassic.jpeg")
        assertEquals(
            listOf("10-bond.JPG", "20-star-wars.png", "30-marvel.WEBP", "40-jurassic.jpeg"),
            CustomWallpapers.paths(temp.root).map { File(it).name },
        )
    }

    @Test fun ignoresHiddenUnsupportedEmptyAndNestedEntries() {
        val kept = picture("wallpapers/kept.jpg")
        picture("wallpapers/.upload.png")
        picture("wallpapers/notes.txt")
        picture("wallpapers/pending.jpg.part")
        picture("wallpapers/empty.png").writeBytes(byteArrayOf())
        picture("wallpapers/nested/another.jpg")
        File(temp.root, "wallpapers/folder.jpg").mkdir()
        assertEquals(listOf(kept.path), CustomWallpapers.paths(temp.root))
    }

    @Test fun galleryTakesPrecedenceWithoutDeletingLegacyWallpaper() {
        val legacy = picture("wallpaper.jpg")
        val gallery = picture("wallpapers/one.jpg")
        assertEquals(listOf(gallery.path), CustomWallpapers.paths(temp.root))
        assertTrue(legacy.isFile)
        gallery.delete()
        assertEquals(listOf(legacy.path), CustomWallpapers.paths(temp.root))
    }

    @Test fun preservesTheSingleWallpaperSetupWithAMissingOrEmptyGallery() {
        val legacy = picture("wallpaper.jpg")
        assertEquals(listOf(legacy.path), CustomWallpapers.paths(temp.root))
        File(temp.root, "wallpapers").mkdir()
        assertEquals(listOf(legacy.path), CustomWallpapers.paths(temp.root))
    }

    @Test fun noUsableLocalImagesAllowsTheDailyFallback() {
        assertTrue(CustomWallpapers.paths(null).isEmpty())
        assertTrue(CustomWallpapers.paths(temp.root).isEmpty())
        picture("wallpapers/unsupported.gif")
        picture("wallpaper.jpg").writeBytes(byteArrayOf())
        assertTrue(CustomWallpapers.paths(temp.root).isEmpty())
    }
}

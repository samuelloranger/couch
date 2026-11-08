package cloud.samlo.couch

import cloud.samlo.couch.data.WallpaperLibrary
import cloud.samlo.couch.data.WallpaperUpload
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WallpaperLibraryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun root() = temp.newFolder()
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun uploadIsInvisibleUntilValidatedAndCommitted() {
        val dir = root(); val lib = WallpaperLibrary(dir); val upload = WallpaperUpload()
        upload.begin(dir, "one", "Trip.jpg", 3)
        assertTrue(lib.entries().isEmpty())
        assertEquals(2, upload.append("one", 0, byteArrayOf(1, 2)))
        assertTrue(lib.entries().isEmpty())
        assertEquals(3, upload.append("one", 2, byteArrayOf(3)))
        val file = upload.finish("one") { "jpg" }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(listOf(file), lib.entries().map { it.file })
        assertEquals(listOf(file), lib.folder.listFiles()!!.toList())
    }

    @Test fun wrongOffsetsTransferIdsAndOverflowAreRejected() {
        val dir = root(); val upload = WallpaperUpload()
        upload.begin(dir, "one", "Photo", 3)
        rejected { upload.append("other", 0, byteArrayOf(1)) }
        rejected { upload.append("one", 1, byteArrayOf(1)) }
        rejected { upload.append("one", 0, byteArrayOf(1, 2, 3, 4)) }
        rejected { upload.finish("one") { "jpg" } }
        upload.close()
        assertTrue(WallpaperLibrary(dir).folder.listFiles()!!.isEmpty())
    }

    @Test fun rejectsLargeUploadsAndLargeChunksBeforeWriting() {
        val upload = WallpaperUpload(); val dir = root()
        rejected { upload.begin(dir, "one", "Photo", 0) }
        rejected { upload.begin(dir, "one", "Photo", WallpaperLibrary.MAX_BYTES + 1) }
        upload.begin(dir, "one", "Photo", WallpaperLibrary.CHUNK_BYTES + 1)
        rejected { upload.append("one", 0, ByteArray(WallpaperLibrary.CHUNK_BYTES + 1)) }
        upload.close()
    }

    @Test fun invalidImageNeverEntersTheGallery() {
        val dir = root(); val upload = WallpaperUpload()
        upload.begin(dir, "one", "Photo", 1); upload.append("one", 0, byteArrayOf(0))
        rejected { upload.finish("one") { null } }
        assertTrue(WallpaperLibrary(dir).folder.listFiles()!!.isEmpty())
    }

    @Test fun disconnectCleansPartialUploadAndPreventsQueuedChunks() {
        val dir = root(); val upload = WallpaperUpload()
        upload.begin(dir, "one", "Photo", 2); upload.append("one", 0, byteArrayOf(1))
        upload.close()
        rejected { upload.append("one", 1, byteArrayOf(2)) }
        rejected { upload.begin(dir, "two", "Photo", 2) }
        assertTrue(WallpaperLibrary(dir).folder.listFiles()!!.isEmpty())
    }

    @Test fun beginningANewTransferRemovesTheOldTemporaryFile() {
        val dir = root(); val upload = WallpaperUpload()
        upload.begin(dir, "one", "Photo", 1)
        upload.begin(dir, "two", "Photo", 1)
        assertEquals(1, WallpaperLibrary(dir).folder.listFiles()!!.size)
        upload.close()
    }

    @Test fun namesNeverControlTheDestinationAndDuplicateNamesAreUnique() {
        val dir = root(); val upload = WallpaperUpload()
        val files = (1..2).map {
            upload.begin(dir, "$it", "../../Trip.jpg", 1); upload.append("$it", 0, byteArrayOf(1))
            upload.finish("$it") { "jpg" }
        }
        assertNotEquals(files[0], files[1])
        files.forEach { assertEquals(WallpaperLibrary(dir).folder, it.parentFile) }
    }

    @Test fun deletionAndPreviewLookupRejectTraversalHiddenAndOutsideSymlinks() {
        val dir = root(); val lib = WallpaperLibrary(dir); lib.folder.mkdirs()
        val outside = File(root(), "secret.jpg").apply { writeText("secret") }
        java.nio.file.Files.createSymbolicLink(File(lib.folder, "link.jpg").toPath(), outside.toPath())
        listOf("../secret.jpg", "nested/a.jpg", "nested\\a.jpg", ".upload.tmp", "link.jpg").forEach {
            rejected { lib.resolve(it) }
        }
        assertTrue(outside.isFile)
    }

    @Test fun legacyImageIsVisibleAndDeletableWithoutRemovingOtherFiles() {
        val dir = root(); val legacy = File(dir, "wallpaper.jpg").apply { writeText("image") }
        val config = File(dir, "config.json").apply { writeText("{}") }
        val lib = WallpaperLibrary(dir)
        assertEquals(WallpaperLibrary.LEGACY, lib.entries().single().id)
        lib.delete(WallpaperLibrary.LEGACY)
        assertFalse(legacy.exists()); assertTrue(config.exists())
    }

    @Test fun galleryFolderCannotPointOutsideAppFiles() {
        val dir = root()
        java.nio.file.Files.createSymbolicLink(File(dir, "wallpapers").toPath(), root().toPath())
        rejected { WallpaperLibrary(dir).folder }
    }

    @Test fun oldPartialsFromAKilledProcessAreReapedButRecentUploadsAreKept() {
        val dir = root(); val lib = WallpaperLibrary(dir); lib.folder.mkdirs()
        val stale = File(lib.folder, ".upload-old.tmp").apply { writeText("partial"); setLastModified(1) }
        val recent = File(lib.folder, ".upload-recent.tmp").apply { writeText("partial") }
        val unrelated = File(lib.folder, ".other.tmp").apply { writeText("keep"); setLastModified(1) }
        WallpaperUpload().use { it.begin(dir, "one", "Photo", 1) }
        assertFalse(stale.exists()); assertTrue(recent.exists()); assertTrue(unrelated.exists())
    }
}

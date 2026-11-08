package cloud.samlo.couch.data

import java.io.File
import java.util.UUID

/** Files exposed by the gallery; callers never get to choose a path outside it. */
class WallpaperLibrary(private val root: File) {
    data class Entry(val id: String, val file: File)
    val folder: File get() = File(root, "wallpapers").also {
        require(!java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile)
    }

    fun entries(): List<Entry> = CustomWallpapers.paths(root).map { path ->
        val file = File(path)
        Entry(if (file.parentFile == root) LEGACY else file.name, file)
    }.filter { runCatching { resolve(it.id) }.isSuccess }.take(MAX_FILES)

    fun resolve(id: String): File {
        require(id.isNotEmpty() && id.length <= 255 && !id.startsWith(".") && '/' !in id && '\\' !in id && '\u0000' !in id)
        val parent = if (id == LEGACY) root else folder
        val file = File(parent, if (id == LEGACY) "wallpaper.jpg" else id)
        require(file.canonicalFile.parentFile == parent.canonicalFile && file.isFile)
        require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
        return file
    }

    fun delete(id: String) { check(resolve(id).delete()) }

    companion object {
        const val LEGACY = "@legacy"
        const val MAX_FILES = 200
        const val MAX_BYTES = 20 * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
        internal val commitLock = Any()
    }
}

/** One bounded, ordered upload per connection. Only a fully validated image becomes visible. */
class WallpaperUpload : AutoCloseable {
    private var temp: File? = null
    private var library: WallpaperLibrary? = null
    private var title = "Photo"
    private var expected = 0
    private var received = 0
    private var transfer: String? = null
    private var closed = false

    @Synchronized fun begin(root: File, id: String, name: String, size: Int) {
        require(!closed && id.length in 1..80 && size in 1..WallpaperLibrary.MAX_BYTES)
        cancel()
        val lib = WallpaperLibrary(root)
        check(lib.folder.mkdirs() || lib.folder.isDirectory)
        // A process killed by Android cannot run Session.close; reap only our old partial files.
        val staleBefore = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        lib.folder.listFiles().orEmpty().filter {
            it.name.startsWith(".upload-") && it.name.endsWith(".tmp") && it.lastModified() < staleBefore
        }.forEach { it.delete() }
        check(lib.folder.listFiles().orEmpty().count { it.isFile && !it.name.startsWith(".") } < WallpaperLibrary.MAX_FILES)
        library = lib
        title = name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(60).ifBlank { "Photo" }
        temp = File.createTempFile(".upload-", ".tmp", lib.folder)
        transfer = id
        expected = size
        received = 0
    }

    @Synchronized fun append(id: String, offset: Int, bytes: ByteArray): Int {
        require(!closed && id == transfer && temp != null && offset == received)
        require(bytes.size in 1..WallpaperLibrary.CHUNK_BYTES && bytes.size <= expected - received)
        temp!!.appendBytes(bytes)
        received += bytes.size
        return received
    }

    @Synchronized fun finish(id: String, validate: (File) -> String?): File {
        require(!closed && id == transfer && received == expected)
        val source = checkNotNull(temp)
        try {
            val extension = validate(source)
            require(extension in setOf("jpg", "png", "webp"))
            val lib = checkNotNull(library)
            return synchronized(WallpaperLibrary.commitLock) {
                check(lib.folder.listFiles().orEmpty().count { it.isFile && !it.name.startsWith(".") } < WallpaperLibrary.MAX_FILES)
                val target = File(lib.folder, "$title-${UUID.randomUUID()}.$extension")
                check(source.renameTo(target))
                target
            }
        } finally { cancel() }
    }

    @Synchronized fun cancel() {
        temp?.delete()
        temp = null
        library = null
        transfer = null
        expected = 0
        received = 0
    }

    @Synchronized override fun close() { cancel(); closed = true }
}

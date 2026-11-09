package cloud.samlo.couch.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Writes [text] to [file] so a power cut leaves the old or the new content, never half: temp file, fsync, rename. Throws [IOException] on failure. */
fun writeAtomically(file: File, text: String) {
    val tmp = File.createTempFile(file.name, ".tmp", file.absoluteFile.parentFile)
    try {
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("cannot replace ${file.name}")
    } finally {
        tmp.delete()
    }
}

/** Moves an unreadable [file] to `<name>.bad` so the next save cannot overwrite what the user may still recover. */
fun keepAside(file: File) {
    file.renameTo(File(file.parentFile, file.name + ".bad"))
}

package cloud.samlo.couch.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** Bounds every decode, including untrusted phone uploads, before allocating pixels. */
object WallpaperImages {
    fun format(file: File): String? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth !in 1..8192 || b.outHeight !in 1..8192 || b.outWidth.toLong() * b.outHeight > 16_777_216) return null
        return when (b.outMimeType) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> null }
    }

    fun jpeg(file: File, preview: Boolean): ByteArray? {
        if (format(file) == null) return null
        val w = if (preview) 1280 else 320
        val h = if (preview) 720 else 180
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        var sample = 1
        while (b.outWidth / (sample * 2) >= w && b.outHeight / (sample * 2) >= h) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, w.toFloat() / raw.width, h.toFloat() / raw.height)
        val image = Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
        return try { ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray() }
        finally { if (image !== raw) image.recycle(); raw.recycle() }
    }
}

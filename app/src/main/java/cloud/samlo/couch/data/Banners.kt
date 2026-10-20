package cloud.samlo.couch.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dpf
import java.io.File
import java.util.Collections

/** Card banners, rendered once at the card's pixel size and reused across reloads. */
object Banners {
    /**
     * Reused until the app updates or its custom image changes. Bounded, so switching layouts
     * (each renders its own size) or a long app list can't keep piling up bitmaps.
     */
    private val memo = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Keys that rendered nothing (no banner), so they aren't tried again. */
    private val none: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun memoized(key: String, make: () -> Bitmap?): Bitmap? {
        memo.get(key)?.let { return it }
        if (key in none) return null
        return make().also { if (it == null) none.add(key) else memo.put(key, it) }
    }

    /** Part of a memo key, so replacing `icons/<key>.png` invalidates the cached render. */
    fun mtime(dir: File?, key: String) = dir?.let { File(it, "$key.png").lastModified() } ?: 0L

    /** The user's `icons/<key>.png`, if any. */
    fun custom(dir: File?, key: String, w: Int, h: Int, context: Context, radius: Float = context.dpf(Tokens.CARD_RADIUS)): Bitmap? {
        val file = dir?.let { File(it, "$key.png") }?.takeIf { it.isFile } ?: return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        return render(BitmapDrawable(context.resources, bitmap), w, h, radius)
    }

    /** Draws [drawable] center-cropped into a w x h bitmap with rounded corners of [radius] px. */
    fun render(drawable: Drawable, w: Int, h: Int, radius: Float): Bitmap {
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = maxOf(w.toFloat() / iw, h.toFloat() / ih)
        val dw = (iw * scale).toInt()
        val dh = (ih * scale).toInt()
        drawable.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        drawable.draw(Canvas(flat))

        val rounded = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(rounded).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        flat.recycle()
        return rounded
    }
}

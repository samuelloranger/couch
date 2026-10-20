package cloud.samlo.couch.ui

import android.content.Context
import android.graphics.Typeface

/**
 * Hanken Grotesk, the Index layout's typeface: one variable font (Latin subset, OFL) in
 * assets, instanced per weight on first use. Glyphs it lacks fall back to the system font.
 */
object Fonts {
    private const val PATH = "fonts/hanken_grotesk.ttf"
    private val cache = HashMap<Int, Typeface>()

    /** [weight] is a CSS-style weight: 400 regular, 500 medium, 600 semibold, 700 bold. */
    fun hanken(context: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        try {
            Typeface.Builder(context.assets, PATH).setFontVariationSettings("'wght' $weight").setWeight(weight).build()
        } catch (e: Exception) {
            null
        } ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
    }
}

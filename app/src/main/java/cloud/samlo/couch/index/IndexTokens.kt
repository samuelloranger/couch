package cloud.samlo.couch.index

import android.graphics.Color

/**
 * The Index layout's measurements, in dp on Couch's 960x540dp canvas, and the few colours it
 * adds to [cloud.samlo.couch.ui.Tokens]. Everything is placed at fixed positions, so focus moves
 * never trigger a layout pass.
 */
object IndexTokens {
    // The app list, left.
    const val LIST_X = 24
    const val LIST_Y = 27
    const val LIST_W = 330
    const val LIST_H = 460
    /** Where the list starts fading out, from its top. */
    const val LIST_FADE_Y = 377
    const val ROW_H = 46
    const val ROW_PITCH = 49
    /** Rows kept above the selected one before the list glides; the last rows never pass the fade. */
    const val ROWS_ABOVE = 3
    const val ROWS_SHOWN = 8
    const val ICON = 29

    // The right column.
    const val COLUMN_X = 380
    const val PANEL_Y = 61
    const val PANEL_W = 556
    const val PANEL_H = 225
    const val PANEL_RADIUS = 8f
    const val STRIP_Y = 306
    const val CARD_H = 75
    const val CARD_GAP = 8
    const val CARD_RADIUS = 6f
    const val EMPTY_Y = 315
    const val STATS_Y = 415
    const val FOOTER_BOTTOM = 17
    const val BACKDROP_W = 700
    const val BACKDROP_ALPHA = 0.22f

    val hours = Color.parseColor("#5E6865")
    val barOff = Color.parseColor("#2C3532")
    val markerIdle = Color.parseColor("#3D4A45")
    val description = Color.parseColor("#B9C2BF")
    val footerValue = Color.parseColor("#BFC7C4")
    val pillText = Color.parseColor("#C4CCC9")
    /** Tokens.surface at 70%. */
    val pill = Color.parseColor("#B31A201E")

    /** Card widths by TvContract poster aspect ratio, at [CARD_H] tall. */
    fun cardWidth(ratio: Int): Int = when (ratio) {
        1 -> 112 // 3:2
        2 -> 100 // 4:3
        3 -> 75 // 1:1
        4, 5 -> 50 // 2:3 posters
        else -> 133 // 16:9
    }

    /** Width-to-height of the spotlight image for a poster ratio. */
    fun shape(ratio: Int): Float = when (ratio) {
        1 -> 1.5f
        2 -> 4 / 3f
        3 -> 1f
        4, 5 -> 2 / 3f
        else -> 16 / 9f
    }
}

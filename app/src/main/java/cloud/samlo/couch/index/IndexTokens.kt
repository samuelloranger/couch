package cloud.samlo.couch.index

import android.graphics.Color

/**
 * The Index layout's measurements, in dp on Couch's 960x540dp canvas, and the few colours it
 * adds to [cloud.samlo.couch.ui.Tokens]. Everything is placed at fixed positions, so focus moves
 * never trigger a layout pass.
 */
object IndexTokens {
    // The rail: Home, Inputs and Settings, a strip down the left edge.
    const val RAIL_W = 60
    const val RAIL_ITEM_W = 48
    const val RAIL_ITEM_H = 48
    const val RAIL_ICON = 17
    /** Home marks the current screen; Inputs sits one item below it. */
    const val RAIL_HOME_Y = 75
    const val RAIL_INPUTS_Y = 131
    const val RAIL_SETTINGS_Y = 450
    const val RAIL_BAR_W = 2
    const val RAIL_BAR_H = 18

    // The app list, right of the rail. Ten rows show, the last ones fading out.
    const val LIST_X = 84
    const val LIST_Y = 70
    const val LIST_W = 280
    const val LIST_ROW_H = 32
    const val LIST_ROW_PITCH = 37
    const val LIST_ICON = 22
    const val LIST_H = 10 * LIST_ROW_PITCH
    /** Where the list starts fading out, from its top. */
    const val LIST_FADE_Y = LIST_H * 85 / 100
    /** Rows kept above the selected one before the list glides; the last rows never pass the fade. */
    const val ROWS_ABOVE = 3
    const val ROWS_SHOWN = 10
    const val LIST_MARKER_W = 2
    const val LIST_MARKER_H = 14

    // The menus and panels that share the list's look (Inputs, the remote's panels).
    const val ROW_H = 46
    const val ROW_PITCH = 49
    const val ICON = 29

    // The right column: Home Assistant pills, the program's text, its channel's cards, then the week.
    const val COLUMN_X = 400
    const val COLUMN_W = 532
    /** The top row (pills and the weather/clock cluster) and the screen's right margin. */
    const val TOP_Y = 20
    const val TOP_H = 28
    const val RIGHT_MARGIN = 28
    const val HOME_ROW_Y = TOP_Y
    const val HOME_ROW_H = TOP_H
    const val HEADER_Y = 85
    const val STRIP_Y = 210
    const val CARD_H = 84
    const val CARD_GAP = 10
    const val CARD_RADIUS = 6f
    const val STATS_Y = 345
    const val FOOTER_Y = 505

    val hours = Color.parseColor("#5E6865")
    val railIdle = Color.parseColor("#7D8784")
    val railActive = Color.parseColor("#F4F6F5")
    val barOff = Color.parseColor("#2C3532")
    val markerIdle = Color.parseColor("#3D4A45")
    val description = Color.parseColor("#8E9894")
    val footerValue = Color.parseColor("#BFC7C4")
    val pillText = Color.parseColor("#C4CCC9")
    /** Tokens.surface at 70%. */
    val pill = Color.parseColor("#B31A201E")

    /** Card widths by TvContract poster aspect ratio, at [CARD_H] tall. */
    fun cardWidth(ratio: Int): Int = when (ratio) {
        1 -> 126 // 3:2
        2 -> 112 // 4:3
        3 -> 84 // 1:1
        4, 5 -> 56 // 2:3 posters
        else -> 149 // 16:9
    }
}

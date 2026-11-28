package cloud.samlo.couch.index

import cloud.samlo.couch.ui.Focus
import android.graphics.Color
import cloud.samlo.couch.ui.Tokens

/**
 * The Index layout's measurements, in dp on Couch's 960x540dp canvas, and the few colours it
 * adds to [cloud.samlo.couch.ui.Tokens]. Everything is placed at fixed positions, so focus moves
 * never trigger a layout pass.
 */
object IndexTokens {
    // The rail: Search, Home, Inputs and Settings, a strip down the left edge.
    const val RAIL_W = 80
    const val RAIL_ITEM_W = 68
    const val RAIL_ITEM_H = 60
    const val RAIL_ICON = 22
    /** Search takes the list's place; Home marks the current screen, Inputs sits one item below it. */
    const val RAIL_SEARCH_Y = 60
    const val RAIL_HOME_Y = 128
    const val RAIL_INPUTS_Y = 196
    const val RAIL_SETTINGS_Y = 448
    /** The bar for the current page, flush with the rail's edge: wider and taller than a row's marker. */
    const val RAIL_BAR_W = 3
    const val RAIL_BAR_H = 26

    // The app list, right of the rail. Ten rows show, the last ones fading out.
    const val LIST_X = 100
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
    const val LIST_MARKER_W = Focus.MARKER_W
    const val LIST_MARKER_H = Focus.MARKER_H

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
    /** The Now Playing dock at the foot of the right column, in the space the week leaves. */
    const val DOCK_Y = 420
    const val DOCK_H = 52
    const val FOOTER_Y = 505

    /** Meta text (hours, hints, section labels, state words): 6.6:1 on the background, 5.6:1 on a surface. */
    val hours = Color.parseColor("#8E9894")
    /** The 2x2 icon of a folder row: a shade darker than an app's own icon square. */
    val folderIcon = Color.parseColor("#2A3330")
    val railIdle = Color.parseColor("#7D8784")
    val railActive = Color.parseColor("#F4F6F5")
    /** An empty chart bar: a graphic that carries meaning, so 3:1 or better. */
    val barOff = Tokens.graphicIdle
    val description = hours
    /** The footer's quiet text on the window background: 6.6:1 for labels, 8.5:1 for values. */
    val footerLabel = hours
    val footerValue = Color.parseColor("#A3ADAA")
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

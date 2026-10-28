package cloud.samlo.couch.remote

import android.content.Context
import android.graphics.Bitmap
import cloud.samlo.couch.data.Tile

/**
 * One line of a panel the remote's buttons open: an input, an app or a setting, under its
 * [section] title. [detail] is read each time the row is drawn, so a value (the sleep timer's
 * minutes) stays current. A [keepOpen] item changes that value in place instead of closing, and
 * an item with [adjust] is stepped back and forth with Left and Right (OK steps forward).
 */
class PanelItem(
    val key: String,
    val section: String,
    val label: String,
    val icon: Icon,
    val dimmed: Boolean = false,
    val keepOpen: Boolean = false,
    val detail: () -> String? = { null },
    val adjust: ((Context, Int) -> Unit)? = null,
    val action: (Context) -> Unit = { adjust?.invoke(it, 1) },
) {
    /** What fills the icon slot: an input's card, an app's launcher icon, or a drawn glyph. */
    sealed class Icon {
        class Input(val tile: Tile) : Icon()
        class App(val bitmap: Bitmap?) : Icon()
        class Glyph(val res: Int) : Icon()
    }

    companion object {
        /** An input or an app from the Index list, opened as the home screen opens it. */
        fun of(tile: Tile, section: String, appIcon: Bitmap?) = PanelItem(
            key = tile.key,
            section = section,
            label = tile.label,
            icon = if (tile.kind == Tile.Kind.INPUT) Icon.Input(tile) else Icon.App(appIcon),
            dimmed = tile.dimmed,
            detail = { tile.subtitle },
            action = tile::launch,
        )
    }
}

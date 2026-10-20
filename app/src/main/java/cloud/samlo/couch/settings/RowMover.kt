package cloud.samlo.couch.settings

import android.view.KeyEvent
import cloud.samlo.couch.R
import cloud.samlo.couch.ui.glideToNewPlaces

/**
 * Move mode on the rows page: up/down shifts the held row, OK or Back puts it down and saves
 * the order. While a row is held it swallows every key so nothing else reacts.
 */
class RowMover(private val ctx: SettingsContext) {
    private var moving: OptionRow? = null

    /** The OK release that ends the long press which started a move must not end it too. */
    private var swallowNextRelease = false

    fun start(row: OptionRow) {
        moving = row
        swallowNextRelease = true
        row.value.text = "↕"
        ctx.layout.hint.text = ctx.str(R.string.rows_moving_hint)
    }

    fun consumesKey(event: KeyEvent): Boolean {
        val row = moving ?: return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                if (event.action == KeyEvent.ACTION_DOWN) shift(row, if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK ->
                if (event.action == KeyEvent.ACTION_UP) {
                    if (swallowNextRelease && event.keyCode != KeyEvent.KEYCODE_BACK) {
                        swallowNextRelease = false
                    } else {
                        drop(row)
                    }
                }
        }
        return true
    }

    private fun shift(row: OptionRow, by: Int) {
        val list = ctx.layout.list
        val from = list.indexOfChild(row)
        val neighbour = list.getChildAt(from + by) as? OptionRow ?: return
        // Move the neighbour so the focused row never detaches.
        glideToNewPlaces(row, neighbour)
        list.removeView(neighbour)
        list.addView(neighbour, from)
    }

    private fun drop(row: OptionRow) {
        moving = null
        row.refresh()
        ctx.layout.hint.text = ctx.str(R.string.rows_hint)
        ctx.config.sectionOrder = ctx.layout.items().mapNotNull { it.tag as? String }
        ctx.config.save(ctx.files.dir)
    }
}

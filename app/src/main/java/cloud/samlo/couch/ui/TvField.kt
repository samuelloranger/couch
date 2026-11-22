package cloud.samlo.couch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * A text field whose keyboard always closes with Back. Fire TV's keyboard keeps Back for itself and
 * never hides, so a plain field traps the remote; Back is handled here, before the keyboard sees it.
 * With no keyboard up, Back goes on as usual and closes whatever holds the field.
 */
@SuppressLint("ViewConstructor")
class TvField(context: Context) : EditText(context) {
    /** The current Back press closed the keyboard, so its release must not close the panel too. */
    private var backHidKeyboard = false

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backHidKeyboard = hideKeyboard()
            if (backHidKeyboard) {
                if (event.action == KeyEvent.ACTION_UP) backHidKeyboard = false
                return true
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    /** True when a keyboard was up and is now hidden. */
    fun hideKeyboard(): Boolean =
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0) == true
}

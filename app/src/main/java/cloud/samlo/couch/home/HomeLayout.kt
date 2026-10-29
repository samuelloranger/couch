package cloud.samlo.couch.home

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.index.IndexScreen
import cloud.samlo.couch.ui.Ambient
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Tokens

/** The home screen's view tree, bottom to top: the Index screen, then the ambient overlay. */
class HomeViews(
    activity: Activity,
    images: Images,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
    onAppMenu: (Tile) -> Unit,
    onProgramMenu: (Card) -> Unit,
) {
    val index = IndexScreen(activity, images, onSettings, onUpdate, onAppMenu, onProgramMenu)
    val ambient = Ambient(activity, images)

    val root = FrameLayout(activity).apply {
        addView(index, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(ambient, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    init {
        // Index sits on a flat colour; painting it as the window background avoids a full-screen layer.
        activity.window.setBackgroundDrawable(ColorDrawable(Tokens.background))
    }
}

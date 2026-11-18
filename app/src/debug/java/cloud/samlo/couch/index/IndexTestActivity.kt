package cloud.samlo.couch.index

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile

/**
 * An [IndexScreen] bound to crafted data, so its D-pad map can be tested without a TV's apps,
 * channels or usage figures. The test sets [data] before launching; the screen binds it in
 * `onCreate` the way [cloud.samlo.couch.home.HomeScreen] would.
 *
 * Keys that go through the framework (the arrows) are sent with
 * `Instrumentation.sendKeyDownUpSync`, which exercises the real focus search. Keys that the
 * activity-level wiring would route through [IndexScreen.consumesKey] (the search key, Back, the
 * move's keys) are handed to it directly, as `MainActivity.dispatchKeyEvent` would.
 */
class IndexTestActivity : Activity() {
    lateinit var index: IndexScreen
        private set

    /** What [IndexScreen]'s onMoved callback received: the visible order, the key and its place. */
    val moves = mutableListOf<Triple<List<String>, String, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = IndexScreen(
            this, Images(this, Fetcher(this)),
            onSettings = {}, onUpdate = {},
            onAppMenu = { _, _ -> }, onFolderMenu = { _, _ -> }, onProgramMenu = {},
            onChannelSelected = { _, _ -> },
            onMoved = { order, key, at -> moves.add(Triple(order, key, at)) },
        )
        index.bind(data ?: defaultData(), Config())
        setContentView(index)
    }

    /** The key the search key and Back are routed through, as MainActivity routes them. */
    fun consumesKey(keyCode: Int, action: Int): Boolean =
        index.consumesKey(KeyEvent(action, keyCode))

    companion object {
        /** What the launched activity binds; the test sets it before launching. */
        var data: IndexData? = null

        /** Apps a to e, d carrying two channels; a folder of two between b and c. */
        fun defaultData(): IndexData {
            val apps = ('a'..'e').map { key ->
                val channels = if (key == 'd') 1..2 else 1..1
                IndexEntry(
                    app = Tile("$key", Tile.Kind.APP, label = "App $key", intent = Intent()),
                    icon = null,
                    channels = channels.map { c ->
                        IndexChannel(c.toLong(), "Channel $c", (1..3).map { p ->
                            Tile("$key-$c-$p", Tile.Kind.PROGRAM, "Program $p", Intent())
                        })
                    },
                    preferredChannel = null,
                    usage = null,
                )
            }
            val folder = IndexEntry(
                app = Tile(IndexEntry.FOLDER + "f1", Tile.Kind.APP, label = "Folder", intent = Intent()),
                icon = null,
                channels = emptyList(),
                preferredChannel = null,
                usage = null,
                folderApps = listOf(
                    Tile("x", Tile.Kind.APP, "App X", Intent()),
                    Tile("y", Tile.Kind.APP, "App Y", Intent()),
                ).map { tile ->
                    IndexEntry(app = tile, icon = null, channels = emptyList(), preferredChannel = null, usage = null)
                },
            )
            return IndexData(
                listOf(apps[0], apps[1], folder, apps[2], apps[3], apps[4]),
                usageKnown = false,
                inputs = emptyList(),
            )
        }
    }
}

package cloud.samlo.couch.remote

import android.content.Context
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.index.IndexBuilder
import cloud.samlo.couch.ui.Tokens

/** What the Input button lists: the TV's inputs, then the apps in the Index list's order. */
object InputItems {
    /** Reads the apps and inputs; slow, so call it from a worker. */
    fun load(context: Context): List<PanelItem> {
        val files = UserFiles(context)
        val config = Config.load(files.dir)
        Tokens.applyAccent(config.accent)
        val data = IndexBuilder.build(context, config, files.icons)
        val inputs = context.getString(R.string.section_inputs)
        val apps = context.getString(R.string.section_apps)
        return data.inputs.map { PanelItem.of(it, inputs, null) } + data.entries.map { PanelItem.of(it.app, apps, it.icon) }
    }
}

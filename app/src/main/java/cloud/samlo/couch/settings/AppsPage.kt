package cloud.samlo.couch.settings

import cloud.samlo.couch.R
import cloud.samlo.couch.data.AppSource

/** Every app the TV can open, each with a switch for whether the home screen shows it. */
class AppsPage(private val ctx: SettingsContext) : SettingsPage {
    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.settings_visibility)
        ctx.layout.hint.text = ctx.str(R.string.apps_hint)
        val pm = ctx.activity.packageManager
        val config = { ctx.config }
        AppSource.launchable(pm)
            .map { it.activityInfo.packageName to (ctx.config.names[it.activityInfo.packageName] ?: it.loadLabel(pm).toString()) }
            .filter { it.first != ctx.activity.packageName }
            .sortedBy { it.second.lowercase() }
            .forEach { (pkg, label) ->
                // Hidden apps that are no longer installed stay in the set, so they stay hidden if they come back.
                ctx.options.toggle(label, "", { pkg !in config().hiddenApps }) {
                    config().hiddenApps = if (it) config().hiddenApps - pkg else config().hiddenApps + pkg
                }
            }
    }
}

package cloud.samlo.couch.home

import android.content.Context
import cloud.samlo.couch.R
import cloud.samlo.couch.data.AppSource
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.InputSource
import cloud.samlo.couch.data.Programs
import cloud.samlo.couch.data.Sections
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import java.io.File

/** One row's worth of data, built on the loader thread. Card sizes are in dp. */
class SectionData(val id: String, val title: String, val tiles: List<Tile>, val w: Int, val h: Int, look: String) {
    /** Changes when the row would look different, so unchanged rows are left alone. */
    val signature = "$title|$w|$h|$look|" + tiles.joinToString(";") { it.signature }
}

/** Assembles the rows (apps, recent apps, inputs, app channels) in the user's order, minus hidden ones. */
object SectionBuilder {
    fun build(context: Context, config: Config, iconsDir: File?): List<SectionData> {
        val scale = config.cardScale
        val size = { w: Int, h: Int -> (w * scale).toInt() to (h * scale).toInt() }
        val byId = LinkedHashMap<String, SectionData>()

        val (appW, appH) = size(Tokens.APP_W, Tokens.APP_H)
        val apps = AppSource.tiles(context, config, iconsDir, context.dp(appW), context.dp(appH))
        byId[Sections.APPS] = SectionData(Sections.APPS, context.getString(R.string.section_apps), apps, appW, appH, config.look)

        // The same cards as the apps row, so they share its banners; empty without usage access.
        val byKey = apps.associateBy { it.key }
        val recent = Sections.recent(apps.map { it.key }, Usage.lastWeek(context)).map { byKey.getValue(it) }
        if (recent.isNotEmpty()) {
            byId[Sections.RECENT] = SectionData(Sections.RECENT, context.getString(R.string.section_recent), recent, appW, appH, config.look)
        }

        val (inputW, inputH) = size(Tokens.INPUT_W, Tokens.INPUT_H)
        val inputs = InputSource.tiles(context, config, iconsDir, context.dp(inputW), context.dp(inputH))
        if (inputs.isNotEmpty()) {
            byId[Sections.INPUTS] = SectionData(Sections.INPUTS, context.getString(R.string.section_inputs), inputs, inputW, inputH, config.look)
        }

        for (channel in Programs.channels(context, config)) {
            val (w, h) = Tokens.programSize(channel.tiles.first().imageRatio).let { size(it.first, it.second) }
            val id = Sections.channelId(channel.id)
            val title = Programs.title(context, channel.packageName, channel.name, "  ·  ")
            byId[id] = SectionData(id, title, channel.tiles, w, h, config.look)
        }

        return Sections.order(byId.keys.toList(), config)
            .filter { it !in config.hiddenSections }
            .map { byId.getValue(it) }
    }
}

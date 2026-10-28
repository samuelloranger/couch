package cloud.samlo.couch.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import cloud.samlo.couch.R
import cloud.samlo.couch.bravia.Bravia
import cloud.samlo.couch.bravia.TvSettings
import java.util.concurrent.Executors

/**
 * The TV's picture and sound settings as quick-settings rows, on a Bravia that Couch is paired
 * with. Values are read on a worker of its own (so a change never waits behind the relay starting)
 * and kept here, so rows draw at once; Left and Right show the
 * new value right away and write it behind, putting the old one back if the TV refuses.
 */
class TvQuick(private val context: Context, private val bluetoothOutput: () -> String?, private val changed: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val bravia = Bravia(context)
    private val tv = TvSettings(bravia)
    @Volatile private var values: Map<String, TvSettings.Setting> = emptyMap()

    /** Reads the values again; [changed] runs on the main thread once they're in. */
    fun refresh() {
        if (!bravia.paired || worker.isShutdown) return
        worker.execute {
            val read = tv.read()
            main.post {
                values = read
                changed()
            }
        }
    }

    fun release() = worker.shutdownNow()

    fun items(): List<PanelItem> {
        if (values.isEmpty()) return emptyList()
        val section = context.getString(R.string.quick_section_picture)
        val items = mutableListOf<PanelItem>()
        fun row(target: String, label: Int, glyph: Int) {
            if (target !in values) return
            items += PanelItem(
                "tv:$target", section, context.getString(label), PanelItem.Icon.Glyph(glyph),
                detail = { values[target]?.let { name(target, it.value) } },
                adjust = { _, by -> step(target, by) },
            )
        }
        row(TvSettings.PICTURE_MODE, R.string.quick_picture_mode, R.drawable.qs_picture)
        row(TvSettings.BRIGHTNESS, R.string.quick_brightness, R.drawable.qs_brightness)
        row(TvSettings.LIGHT_SENSOR, R.string.quick_light_sensor, R.drawable.qs_sensor)
        row(TvSettings.OUTPUT, R.string.quick_output, R.drawable.ic_speaker)
        row(TvSettings.POWER_SAVING, R.string.quick_power_saving, R.drawable.qs_eco)
        items += PanelItem("tv:pictureOff", section, context.getString(R.string.quick_picture_off), PanelItem.Icon.Glyph(R.drawable.qs_picture_off)) {
            worker.execute { runCatching { tv.pictureOff() } }
        }
        return items
    }

    private fun step(target: String, by: Int) {
        val now = values[target] ?: return
        val next = now.next(by) ?: return
        values = values + (target to TvSettings.Setting(target, next, now.choices, now.min, now.max))
        worker.execute {
            val ok = runCatching { tv.write(target, next) }.isSuccess
            if (!ok) main.post {
                values = values + (target to now)
                changed()
            }
        }
    }

    private fun name(target: String, value: String): String {
        // Sound goes to a connected Bluetooth device whatever the TV's own output is set to.
        if (target == TvSettings.OUTPUT) bluetoothOutput()?.let { return it }
        val res = when (target) {
            TvSettings.PICTURE_MODE -> PICTURE_MODES[value]
            TvSettings.OUTPUT -> OUTPUTS[value]
            TvSettings.POWER_SAVING -> POWER_SAVING[value]
            TvSettings.LIGHT_SENSOR -> if (value == "on") R.string.yes else R.string.no
            else -> null
        }
        // A value Couch has no name for (a new picture mode) still shows, as the TV calls it.
        return res?.let(context::getString) ?: value
    }

    private companion object {
        val PICTURE_MODES = mapOf(
            "vivid" to R.string.picture_vivid, "standard" to R.string.picture_standard, "cinema" to R.string.picture_cinema,
            "game" to R.string.picture_game, "graphics" to R.string.picture_graphics, "photo" to R.string.picture_photo,
            "custom" to R.string.picture_custom,
        )
        val OUTPUTS = mapOf("speaker" to R.string.output_speaker, "audioSystem" to R.string.output_audio_system)
        val POWER_SAVING = mapOf("off" to R.string.no, "low" to R.string.power_low, "high" to R.string.power_high, "pictureOff" to R.string.quick_picture_off)
    }
}

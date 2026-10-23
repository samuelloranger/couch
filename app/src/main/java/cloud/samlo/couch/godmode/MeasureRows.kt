package cloud.samlo.couch.godmode

import android.content.Intent
import cloud.samlo.couch.MainActivity
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.measure.Benchmark
import cloud.samlo.couch.measure.fps
import cloud.samlo.couch.settings.Option

/** The frame HUD on the home screen, and the benchmark that runs there. */
internal fun GodmodePage.measureRows() {
    o.header(ctx.str(R.string.measure_group))
    val units = listOf(
        Option(ctx.str(R.string.hud_off), Config.HUD_OFF),
        Option(ctx.str(R.string.hud_ms), Config.HUD_MS),
        Option(ctx.str(R.string.hud_fps), Config.HUD_FPS),
    )
    o.choice(ctx.str(R.string.hud), ctx.str(R.string.hud_help), units, { ctx.config.hud }) { ctx.config.hud = it }
    o.link(ctx.str(R.string.bench), ctx.str(R.string.bench_help), { lastRun() }) {
        // Back to the home screen, which runs the script; the settings screens above it close.
        ctx.activity.startActivity(
            Intent(ctx.activity, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_BENCHMARK, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }
}

/** The last run's p90, in whichever unit the HUD is set to. */
private fun GodmodePage.lastRun(): String {
    val p90 = Benchmark.last(ctx.activity, ctx.config.layout)?.optInt("p90") ?: return ""
    if (ctx.config.hud != Config.HUD_FPS) return ctx.activity.getString(R.string.bench_value, p90)
    val hz = ctx.activity.windowManager.defaultDisplay?.refreshRate?.takeIf { it > 1f } ?: 60f
    return ctx.activity.getString(R.string.bench_value_fps, fps(p90.toFloat(), hz))
}

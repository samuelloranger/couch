package cloud.samlo.couch.home

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.measure.Benchmark
import cloud.samlo.couch.measure.BenchmarkCard
import cloud.samlo.couch.measure.FrameHud

/**
 * Godmode's frame HUD and benchmark, in debug builds only. Release builds get the empty twin in
 * src/release, so nothing there reaches the measure package and R8 leaves it out.
 */
class Measuring(private val activity: Activity) {
    private val hud = FrameHud(activity)
    private val benchmark = Benchmark(activity)

    val running: Boolean get() = benchmark.running

    fun enable(unit: String) = hud.enable(unit)

    fun onKey(event: KeyEvent) = hud.onKey(event)

    fun abortOn(event: KeyEvent): Boolean = benchmark.abortOn(event)

    fun cancel() = benchmark.cancel()

    /** Runs the script; the result card goes to [onCard], which shows over home. */
    fun run(fps: Boolean, onCard: (View) -> Unit) {
        benchmark.run { result ->
            if (result == null) return@run
            // The HUD's panel sits where the card's corner lands; it comes back with the card gone.
            hud.enable(Config.HUD_OFF)
            onCard(BenchmarkCard.show(activity, result, Benchmark.last(activity), fps))
            Benchmark.save(activity, result)
        }
    }

    companion object {
        /** Whether this build can measure at all: a HUD left on by a debug build means nothing in a release. */
        const val BUILT = true
    }
}

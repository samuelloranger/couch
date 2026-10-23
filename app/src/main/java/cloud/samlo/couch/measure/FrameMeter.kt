package cloud.samlo.couch.measure

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Android's own timing of every frame Couch's window draws, delivered on a background thread.
 * Registered only while something listens (the HUD, a benchmark), so it costs nothing otherwise.
 */
class FrameMeter(private val window: Window) {
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** [onFrame] runs on the meter's thread. A second start replaces the first listener. */
    fun start(onFrame: (Frame) -> Unit) {
        stop()
        val t = HandlerThread("frame-meter").apply { start() }
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            // The first frame after a layout change includes building the window; it isn't a press.
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            onFrame(frame(metrics))
        }
        window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
        thread = t
        listener = l
    }

    fun stop() {
        listener?.let { runCatching { window.removeOnFrameMetricsAvailableListener(it) } }
        listener = null
        thread?.quitSafely()
        thread = null
    }

    private fun frame(m: FrameMetrics): Frame {
        val stages = listOf(
            Stage.INPUT to m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
            Stage.ANIMATION to m.getMetric(FrameMetrics.ANIMATION_DURATION),
            Stage.LAYOUT to m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
            Stage.DRAW to m.getMetric(FrameMetrics.DRAW_DURATION),
            Stage.UPLOAD to m.getMetric(FrameMetrics.SYNC_DURATION),
            Stage.GPU to if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else 0L,
        )
        return Frame(
            m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
            m.getMetric(FrameMetrics.TOTAL_DURATION),
            stages.maxByOrNull { it.second }?.first ?: Stage.DRAW,
        )
    }
}

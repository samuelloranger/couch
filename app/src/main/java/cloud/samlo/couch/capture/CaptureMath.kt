package cloud.samlo.couch.capture

/** The pure parts of the capture helper: what it is asked for and how big the picture is. */
object CaptureMath {
    /** The helper's arguments, `<width> <height> <bitrate> [maxFps]`; null when they don't parse. */
    class Args(val width: Int, val height: Int, val bitRate: Int, val maxFps: Int?)

    fun parse(args: Array<String>): Args? {
        if (args.size !in 3..4) return null
        val n = args.map { it.toIntOrNull() ?: return null }
        if (n.any { it <= 0 }) return null
        return Args(n[0], n[1], n[2], n.getOrNull(3))
    }

    /** The nearest multiple of 16 (some encoders need it), never below 16. */
    fun round16(v: Int): Int = maxOf(16, (v + 8) / 16 * 16)

    /**
     * The encoded size for a screen of [screenW]x[screenH] inside at most [maxW]x[maxH]: the screen's
     * aspect ratio kept, then each side rounded to a multiple of 16. Never scales up.
     */
    fun fit(screenW: Int, screenH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        val scale = minOf(1.0, maxW.toDouble() / screenW, maxH.toDouble() / screenH)
        return round16((screenW * scale).toInt()) to round16((screenH * scale).toInt())
    }

    /**
     * The `app_process` command line that runs the helper from the APK at [apk], as one shell command
     * (`env` sets the class path so the root wrapper's `exec` still works).
     */
    fun command(apk: String, width: Int, height: Int, bitRate: Int, maxFps: Int?): String =
        "env CLASSPATH='${apk.replace("'", "'\\''")}' app_process / cloud.samlo.couch.capture.Capture " +
            "$width $height $bitRate" + (maxFps?.let { " $it" } ?: "")

    /**
     * Whether to give up on the helper for screenrecord: it ended without ever giving a picture
     * config, ended within [EARLY_MS], or has run [CONFIG_MS] without one.
     */
    fun fallBack(elapsedMs: Long, ended: Boolean, gotConfig: Boolean): Boolean = when {
        ended && !gotConfig -> true
        ended -> elapsedMs < EARLY_MS
        else -> !gotConfig && elapsedMs >= CONFIG_MS
    }

    const val EARLY_MS = 2_000L
    const val CONFIG_MS = 5_000L
}

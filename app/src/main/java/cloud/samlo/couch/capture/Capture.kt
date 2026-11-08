package cloud.samlo.couch.capture

import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import kotlin.system.exitProcess

/**
 * Low-latency screen capture, run as its own process with shell or root rights:
 * `CLASSPATH=<apk> app_process / cloud.samlo.couch.capture.Capture <width> <height> <bitrate> [maxFps]`.
 * It mirrors display 0 into a hardware H.264 encoder and writes the raw Annex-B stream to stdout,
 * each encoder buffer as soon as it is ready. It uses no Context: it runs outside any app. Anything
 * it cannot do ends it with a message on stderr and a non-zero exit, so the caller can fall back.
 */
object Capture {
    @Volatile private var running = true
    private var display: Display? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val a = CaptureMath.parse(args) ?: fail("usage: Capture <width> <height> <bitrate> [maxFps]", 2)
        Runtime.getRuntime().addShutdownHook(Thread { running = false; cleanup() })
        val code = try {
            run(a)
            0
        } catch (e: IOException) {
            0 // stdout closed: the reader is gone, which is how it stops us
        } catch (e: Throwable) {
            System.err.println("capture: ${e.javaClass.simpleName}: ${e.message}")
            1
        } finally {
            cleanup()
        }
        exitProcess(code)
    }

    private fun fail(message: String, code: Int): Nothing {
        System.err.println("capture: $message")
        exitProcess(code)
    }

    private fun run(a: CaptureMath.Args) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val (screenW, screenH, rotation, layerStack) = Display.info()
        val (w, h) = CaptureMath.fit(screenW, screenH, a.width, a.height)
        val encoder = configure(w, h, a)
        codec = encoder
        val input = encoder.createInputSurface()
        surface = input
        encoder.start()
        display = Display.create(input, screenW, screenH, rotation, layerStack, w, h)
        drain(encoder)
    }

    /** Optional settings are listed most dispensable last; a refused configure drops one and retries. */
    private fun configure(w: Int, h: Int, a: CaptureMath.Args): MediaCodec {
        val optional = ArrayList<(MediaFormat) -> Unit>()
        optional += { f -> f.setInteger(MediaFormat.KEY_PRIORITY, 0) }
        if (Build.VERSION.SDK_INT >= 30) optional += { f -> f.setInteger(MediaFormat.KEY_LATENCY, 1) }
        a.maxFps?.let { fps -> optional += { f -> f.setInteger("max-fps-to-encoder", fps) } }
        optional += { f -> f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) }
        optional += { f ->
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger("max-bframes", 0)
        }
        optional += { f -> f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }
        var last: Exception? = null
        for (keep in optional.size downTo 0) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, a.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                optional.take(keep).forEach { it(this) }
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return c
            } catch (e: Exception) {
                last = e
                runCatching { c.release() }
            }
        }
        throw last ?: IllegalStateException("configure failed")
    }

    private fun drain(encoder: MediaCodec) {
        val out = FileOutputStream(FileDescriptor.out) // unbuffered: every write goes straight to the pipe
        val info = MediaCodec.BufferInfo()
        var bytes = ByteArray(256 * 1024)
        while (running) {
            val index = encoder.dequeueOutputBuffer(info, 10_000L)
            if (index < 0) continue
            try {
                if (info.size > 0) {
                    val buffer = encoder.getOutputBuffer(index) ?: continue
                    if (bytes.size < info.size) bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(bytes, 0, info.size)
                    out.write(bytes, 0, info.size)
                }
            } finally {
                runCatching { encoder.releaseOutputBuffer(index, false) }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    @Synchronized private fun cleanup() {
        running = false
        runCatching { display?.destroy() }
        display = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { surface?.release() }
        surface = null
    }

    /** What a mirrored display is: the screen's size, rotation and layer stack. */
    data class Info(val width: Int, val height: Int, val rotation: Int, val layerStack: Int)

    /** A mirror of display 0 drawing into the encoder's surface, by whichever hidden API this Android has. */
    class Display private constructor(private val token: IBinder?, private val virtual: Any?) {
        fun destroy() {
            if (token != null) Reflect.call(Reflect.cls("android.view.SurfaceControl"), "destroyDisplay", token)
            virtual?.let { Reflect.call(it, "release") }
        }

        companion object {
            /** The size and rotation of display 0 from the display manager's own records. */
            fun info(): Info {
                val global = Reflect.call(Reflect.cls("android.hardware.display.DisplayManagerGlobal"), "getInstance")
                    ?: throw IllegalStateException("no display manager")
                val di = Reflect.call(global, "getDisplayInfo", 0) ?: throw IllegalStateException("no display info")
                fun int(name: String) = di.javaClass.getField(name).getInt(di)
                val rotation = int("rotation")
                // logicalWidth/Height are already rotated; the layer stack rect is the unrotated panel.
                val lw = int("logicalWidth")
                val lh = int("logicalHeight")
                val (pw, ph) = if (rotation == 1 || rotation == 3) lh to lw else lw to lh
                return Info(pw, ph, rotation, int("layerStack"))
            }

            fun create(input: Surface, screenW: Int, screenH: Int, rotation: Int, layerStack: Int, w: Int, h: Int): Display {
                val first = try {
                    surfaceControl(input, screenW, screenH, rotation, layerStack, w, h)
                } catch (e: Throwable) {
                    e
                }
                if (first is Display) return first
                try {
                    return displayManager(input, w, h)
                } catch (e: Throwable) {
                    throw IllegalStateException("no virtual display: ${(first as Throwable).message}; ${e.message}")
                }
            }

            private fun surfaceControl(input: Surface, screenW: Int, screenH: Int, rotation: Int, layerStack: Int, w: Int, h: Int): Display {
                val sc = Reflect.cls("android.view.SurfaceControl")
                val token = Reflect.call(sc, "createDisplay", "couch", false) as? IBinder
                    ?: throw IllegalStateException("createDisplay returned nothing")
                val layerRect = Rect(0, 0, screenW, screenH)
                val displayRect = Rect(0, 0, w, h)
                // The display is not rotated: the layer stack's own rotation is part of what is mirrored.
                try {
                    Reflect.call(sc, "openTransaction")
                    try {
                        Reflect.call(sc, "setDisplaySurface", token, input)
                        Reflect.call(sc, "setDisplayProjection", token, 0, layerRect, displayRect)
                        Reflect.call(sc, "setDisplayLayerStack", token, layerStack)
                    } finally {
                        Reflect.call(sc, "closeTransaction")
                    }
                } catch (e: NoSuchMethodException) {
                    val t = Reflect.cls("android.view.SurfaceControl\$Transaction").getConstructor().newInstance()
                    Reflect.call(t, "setDisplaySurface", token, input)
                    Reflect.call(t, "setDisplayProjection", token, 0, layerRect, displayRect)
                    Reflect.call(t, "setDisplayLayerStack", token, layerStack)
                    Reflect.call(t, "apply")
                }
                return Display(token, null)
            }

            /** Newer Androids: a public, auto-mirroring virtual display made through the system context. */
            @android.annotation.SuppressLint("WrongConstant")
            private fun displayManager(input: Surface, w: Int, h: Int): Display {
                val thread = Reflect.cls("android.app.ActivityThread")
                val at = Reflect.call(thread, "systemMain") ?: throw IllegalStateException("no activity thread")
                val context = Reflect.call(at, "getSystemContext") as android.content.Context
                val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                // PUBLIC | PRESENTATION | AUTO_MIRROR | TRUSTED (hidden, ignored where unknown)
                val flags = 1 or 2 or (1 shl 4) or (1 shl 10)
                val vd = dm.createVirtualDisplay("couch", w, h, 160, input, flags)
                    ?: throw IllegalStateException("createVirtualDisplay returned nothing")
                return Display(null, vd)
            }
        }
    }
}

/** Hidden-API calls by name, matching on argument count and the types given (primitives for boxed values). */
internal object Reflect {
    fun cls(name: String): Class<*> = Class.forName(name)

    /** Calls the static method of [target] when it is a Class, else the instance method. */
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val isStatic = target is Class<*>
        val type = if (target is Class<*>) target else target.javaClass
        val m = type.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size && fits(it.parameterTypes, args) }
            ?: type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == args.size && fits(it.parameterTypes, args) }
            ?: throw NoSuchMethodException("${type.name}.$name/${args.size}")
        m.isAccessible = true
        return try {
            m.invoke(if (isStatic) null else target, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun fits(params: Array<Class<*>>, args: Array<out Any?>) = params.indices.all { i ->
        val a = args[i]
        val p = params[i]
        when {
            a == null -> !p.isPrimitive
            p == Int::class.javaPrimitiveType -> a is Int
            p == Boolean::class.javaPrimitiveType -> a is Boolean
            p == Long::class.javaPrimitiveType -> a is Long
            else -> p.isInstance(a)
        }
    }
}

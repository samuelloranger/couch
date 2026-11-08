package cloud.samlo.couch.phone

/**
 * The keys a phone can press, each as the Linux code the native relay emits through uinput (null
 * when Android has no stock mapping for one worth trusting) and as the Android key code
 * `input keyevent` takes. One table, so the two paths can't drift apart.
 */
object Keys {
    class Key(val name: String, val linux: Int?, val android: Int)

    val all: List<Key> = listOf(
        Key("UP", 103, 19),
        Key("DOWN", 108, 20),
        Key("LEFT", 105, 21),
        Key("RIGHT", 106, 22),
        Key("OK", 353, 23),
        Key("BACK", 158, 4),
        Key("HOME", 172, 3),
        Key("MENU", 139, 82),
        Key("PLAY_PAUSE", 164, 85),
        Key("REWIND", 168, 89),
        Key("FAST_FORWARD", 208, 90),
        Key("VOLUME_UP", 115, 24),
        Key("VOLUME_DOWN", 114, 25),
        Key("MUTE", 113, 164),
        Key("POWER", 116, 26),
        Key("INPUT", null, 178),
        Key("SETTINGS", null, 176),
        Key("CHANNEL_UP", 402, 166),
        Key("CHANNEL_DOWN", 403, 167),
    )

    private val byName = all.associateBy { it.name }

    fun named(name: String): Key? = byName[name]

    /** The longest text one `text` message may carry. */
    const val MAX_TEXT = 500

    /**
     * The shell command that types [text] into the focused field. `input text` takes one argument
     * where a space is `%s`, so the text goes in single quotes with spaces swapped, and a quote
     * inside is closed, escaped and reopened. Control characters (a newline would end the command)
     * are dropped, and the length is capped. Null when nothing is left to type.
     */
    fun textCommand(text: String): String? {
        val clean = text.take(MAX_TEXT).filter { it >= ' ' && it != '\u007f' }
        if (clean.isEmpty()) return null
        val quoted = clean.replace(" ", "%s").replace("'", "'\\''")
        return "input text '$quoted'"
    }
}

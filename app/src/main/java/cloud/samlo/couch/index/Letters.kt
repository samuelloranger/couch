package cloud.samlo.couch.index

import java.text.Normalizer

/**
 * The A to Z strip's rules, pure so they are tested without a screen: which cell an app label
 * belongs to, which cells have apps, and where a Left or Right press lands.
 */
object Letters {
    /** The cells in reading order: A to Z, then one for everything that starts otherwise. */
    val ALL: List<Char> = ('A'..'Z').toList() + '#'

    /** The cell for [label]: its first letter with accents folded away, or '#' for a digit or symbol. */
    fun initial(label: String): Char {
        val first = label.trimStart().firstOrNull() ?: return '#'
        val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return '#'
        return if (base in 'A'..'Z') base else '#'
    }

    /** The cells that have at least one of [labels]. */
    fun present(labels: Iterable<String>): Set<Char> = labels.mapTo(HashSet()) { initial(it) }

    /**
     * The cell one press from [from] ([delta] is -1 or +1) that has apps, or null at either end
     * (the press then leaves the strip, or stays). A null [from] enters at the first such cell.
     */
    fun step(present: Set<Char>, from: Char?, delta: Int): Char? {
        if (from == null) return ALL.firstOrNull { it in present }
        var i = ALL.indexOf(from) + delta
        while (i in ALL.indices) {
            if (ALL[i] in present) return ALL[i]
            i += delta
        }
        return null
    }
}

/** Where Up and Down lead in Search: the field and its mic, the letter strip, then the result rows. */
object SearchRoute {
    enum class Stop { FIELD, MIC, STRIP, ROW }

    /** A stop, and which row when it is [Stop.ROW]. */
    data class At(val stop: Stop, val row: Int = 0)

    /**
     * One Down or Up from [from]. Typing a query skips the strip, which only filters an empty one;
     * Up from the first row returns to the strip while a letter is chosen, else to the field.
     * Pressing past either end stays where it is.
     */
    fun vertical(from: At, down: Boolean, rows: Int, hasQuery: Boolean, letterChosen: Boolean): At = when (from.stop) {
        Stop.FIELD, Stop.MIC -> when {
            !down -> from
            hasQuery && rows > 0 -> At(Stop.ROW, 0)
            hasQuery -> from
            else -> At(Stop.STRIP)
        }
        Stop.STRIP -> if (down) (if (rows > 0) At(Stop.ROW, 0) else from) else At(Stop.FIELD)
        Stop.ROW -> when {
            down -> At(Stop.ROW, minOf(from.row + 1, rows - 1))
            from.row > 0 -> At(Stop.ROW, from.row - 1)
            letterChosen && !hasQuery -> At(Stop.STRIP)
            else -> At(Stop.FIELD)
        }
    }
}

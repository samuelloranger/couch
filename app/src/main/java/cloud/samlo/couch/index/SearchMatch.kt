package cloud.samlo.couch.index

import java.text.Normalizer

/**
 * How a query matches a label: case and accents don't count, and a match at the start of a word
 * ranks above one inside a word. Pure, so the rule is tested without a screen.
 */
object SearchMatch {
    /** Where [query] sits in a label, and whether it starts a word there. */
    class Match(val start: Int, val end: Int, val wordStart: Boolean)

    /** 0 when [query] starts a word of [label], 1 when it sits inside one, null when it is absent. */
    fun rank(label: String, query: String): Int? = find(label, query)?.let { if (it.wordStart) 0 else 1 }

    /** [query]'s place in [label], in the label's own characters so the match can be coloured. */
    fun find(label: String, query: String): Match? {
        val wanted = folded(query).first
        if (wanted.isEmpty()) return null
        val (text, starts) = folded(label)
        val at = text.indexOf(wanted)
        if (at < 0) return null
        // The folded text can be shorter than the label (a combining mark folds away) or longer
        // (a character unfolds into several): map both ends back through the label's own starts.
        val start = starts.indexOfLast { it <= at }
        val end = starts.indexOfFirst { it >= at + wanted.length }.let { if (it < 0) label.length else it }
        val wordStart = at == 0 || !text[at - 1].isLetterOrDigit()
        return Match(start, end, wordStart)
    }

    /** [text] folded for matching, with where each of its own characters begins in the folded copy. */
    private fun folded(text: String): Pair<String, IntArray> {
        val out = StringBuilder(text.length)
        val starts = IntArray(text.length + 1)
        for (i in text.indices) {
            starts[i] = out.length
            out.append(fold(text[i].toString()))
        }
        starts[text.length] = out.length
        return out.toString() to starts
    }

    private fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            // Accents decompose to combining marks: dropping them folds é to e.
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) out.append(c.lowercaseChar())
        }
        return out.toString()
    }
}


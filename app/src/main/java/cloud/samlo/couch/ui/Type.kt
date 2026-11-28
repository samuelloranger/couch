package cloud.samlo.couch.ui

/**
 * The type roles, in sp on the 960x540dp canvas. Every text size comes from here, and nothing
 * sits below [CAPTION]: a sofa-distance floor, not a style choice.
 */
object Type {
    /** Status lines, meta, chart labels: the smallest text there is. */
    const val CAPTION = 12f

    /** Anything read: list rows, menu rows, state words, hints. */
    const val LABEL = 14f

    /** Values and primary text of a settings row. */
    const val BODY = 16f

    /** A card or panel's own heading. */
    const val HEADING = 20f

    /** A page or program title. */
    const val TITLE = 24f

    /** The few large numbers and page names. */
    const val DISPLAY = 32f
}

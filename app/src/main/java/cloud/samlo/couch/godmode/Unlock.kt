package cloud.samlo.couch.godmode

/** How many OK presses on About open godmode. */
const val PRESSES = 7

/**
 * The About-row counter, pure so tests can press quickly or wait out the 3-second gap. Seven OKs
 * unlock; from the third press a toast counts the presses left with seven dots filling in.
 *
 * Presses only count while no update is pending: OK on About installs a pending update, and an
 * update must never be installed by accident. The count resets when focus moves away from the row
 * or when 3 seconds pass between presses.
 */
class Unlock(private val presses: Int = PRESSES, private val now: () -> Long = System::currentTimeMillis) {
    sealed class Outcome {
        /** Not enough presses to show anything yet. */
        object Nothing : Outcome()

        /** Mid-sequence: [pressesLeft] to go, [dots] of the toast's dots now filled. */
        data class Progress(val pressesLeft: Int, val dots: Int) : Outcome()

        /** The seventh press: open godmode and save the flag. */
        object Unlocked : Outcome()
    }

    private var count = 0
    private var lastAt = 0L

    fun ok(pendingUpdate: Boolean): Outcome {
        if (pendingUpdate) return Outcome.Nothing
        val at = now()
        if (count > 0 && at - lastAt > GAP_MS) count = 0
        count++
        lastAt = at
        return when {
            count >= presses -> {
                count = 0
                Outcome.Unlocked
            }
            count >= FIRST_SHOWN -> Outcome.Progress(presses - count, count)
            else -> Outcome.Nothing
        }
    }

    /** The row lost focus, or something else happened that should end a budding sequence. */
    fun reset() {
        count = 0
    }

    companion object {
        private const val GAP_MS = 3_000L
        /** The countdown toast appears from the third press. */
        const val FIRST_SHOWN = 3
    }
}

package cloud.samlo.couch.adb

/**
 * Reads a shell command's text for a refusal. adbd hands back what the command printed but never
 * its exit code, so a command that was turned down (`pm clear --cache-only` on an Android too old
 * to know the option) looks just like one that worked; the wording is the only sign.
 */
object ShellOutput {
    /** What `pm`, `am` and `cmd` print instead of doing the work. */
    private val failures = listOf("error:", "exception", "unknown option", "failure")

    /** Whether any line of [output] reports a failure, whatever its case. */
    fun refused(output: String): Boolean = output.lineSequence().any { line ->
        failures.any { line.lowercase().contains(it) }
    }
}

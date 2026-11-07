package cloud.samlo.couch.hass

import java.time.OffsetDateTime

/**
 * Washers, dryers and dishwashers, which Home Assistant knows as a sensor holding the machine's
 * state ("run", "pause", "end"...) with, often, a sibling sensor for when the cycle ends. Names
 * vary by brand, so both are recognised by how their entity ids end.
 */
object Appliances {
    enum class Phase { RUNNING, PAUSED, DELAYED, DONE, IDLE, OTHER }

    /** How a machine's state sensor's id ends, after its device's own prefix. */
    private val STATE_SUFFIXES = listOf(
        "_machine_state", "_current_status", "_operation_state", "_operating_state", "_job_state",
        "_program_state", "_run_state", "_appliance_state", "_cycle_state",
    )

    /** Sensors that say when the cycle ends: a time, or minutes left. Tried in this order. */
    private val FINISH_SUFFIXES = listOf("_remaining_time", "_estimated_finish", "_finish_time", "_time_remaining", "_end_time")

    fun isStateSensor(id: String): Boolean = id.startsWith(HassDevice.SENSOR) && STATE_SUFFIXES.any { id.endsWith(it) }

    /**
     * The machine's own name from its state sensor's [name]: Home Assistant appends the sensor's
     * ("Dryer Machine state"), which is the id's ending in words.
     */
    fun deviceName(id: String, name: String): String {
        val suffix = STATE_SUFFIXES.firstOrNull { id.endsWith(it) } ?: return name
        val words = suffix.trimStart('_').replace('_', ' ')
        return if (name.endsWith(" $words", ignoreCase = true)) name.dropLast(words.length + 1).trim() else name
    }

    /** Where to look for [id]'s end time. */
    fun finishCandidates(id: String): List<String> {
        val suffix = STATE_SUFFIXES.firstOrNull { id.endsWith(it) } ?: return emptyList()
        val prefix = id.removeSuffix(suffix)
        return FINISH_SUFFIXES.map { prefix + it }
    }

    fun phase(state: String): Phase = when (state.lowercase().replace(' ', '_')) {
        "run", "running", "on", "active", "in_progress", "drying", "washing", "rinsing", "spinning", "cooling",
        "wrinkle_prevent", "drain", "draining", "heating", "cleaning", "main_wash", "pre_wash", "dry", "wash", "rinse", "spin",
        -> Phase.RUNNING
        "pause", "paused" -> Phase.PAUSED
        "delayed_start", "delay_start", "delay", "delayed", "scheduled" -> Phase.DELAYED
        "end", "ended", "finished", "finish", "complete", "completed", "done", "clean" -> Phase.DONE
        "idle", "stop", "stopped", "ready", "off", "none", "standby", "power_off", "inactive" -> Phase.IDLE
        else -> Phase.OTHER
    }

    /** A raw state Couch doesn't know, made readable: "rinse_aid_low" gives "Rinse aid low". */
    fun readable(state: String): String = state.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    /**
     * When the cycle ends, in epoch milliseconds, from a finish sensor's [state] and [unit]; null
     * when it says nothing usable (unknown, or already past).
     */
    fun finishAt(state: String, unit: String, now: Long): Long? {
        val at = state.toDoubleOrNull()?.let { value ->
            when (unit) {
                "min" -> now + (value * 60_000).toLong()
                "h" -> now + (value * 3_600_000).toLong()
                "s" -> now + (value * 1000).toLong()
                else -> null
            }
        } ?: runCatching { OffsetDateTime.parse(state).toInstant().toEpochMilli() }.getOrNull()
        return at?.takeIf { it > now }
    }
}

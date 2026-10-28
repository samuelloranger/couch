package cloud.samlo.couch.index

import android.content.Context
import android.text.format.DateFormat
import cloud.samlo.couch.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Durations and dates in the TV's language. Date patterns come from ICU, which is slow
 * enough to drop frames, so formats are built once per locale and reused (main thread only).
 */
object Formats {
    private const val MINUTE = 60_000L
    private const val DAY = 86_400_000L

    private var locale: Locale? = null
    private lateinit var time24: SimpleDateFormat
    private lateinit var time12: SimpleDateFormat
    private lateinit var weekday: SimpleDateFormat
    private lateinit var narrowDay: SimpleDateFormat
    private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    private var lettersFor = ""
    private var letters = emptyList<String>()

    /** "8 h" or "23 min" beside an app's name; empty when unused. */
    fun short(context: Context, ms: Long): String {
        val minutes = ms / MINUTE
        return when {
            minutes <= 0 -> ""
            minutes < 60 -> context.getString(R.string.index_minutes, minutes.toInt())
            else -> context.getString(R.string.index_hours, ((minutes + 30) / 60).toInt())
        }
    }

    /** "3 h 44" or "23 min"; "< 1 min" when opened but barely used; a dash when unused. */
    fun long(context: Context, ms: Long, opened: Boolean): String {
        val minutes = (ms / MINUTE).toInt()
        return when {
            minutes <= 0 -> context.getString(if (opened) R.string.index_under_minute else R.string.index_none)
            minutes < 60 -> context.getString(R.string.index_minutes, minutes)
            else -> context.getString(R.string.index_hours_minutes, minutes / 60, minutes % 60)
        }
    }

    /** When the app was last opened, to follow "last opened": "today at 10:20", "Wednesday at 22:37"; null if never. */
    fun lastOpened(context: Context, at: Long, now: Long, clock24h: Boolean): String? {
        if (at <= 0L) return null
        formats()
        val date = Date(at)
        val time = (if (clock24h) time24 else time12).format(date)
        val day = when (dayKey.format(date)) {
            dayKey.format(Date(now)) -> context.getString(R.string.index_today)
            dayKey.format(Date(now - DAY)) -> context.getString(R.string.index_yesterday)
            // Within the week, the weekday alone says which day: "Wednesday", "mercredi".
            else -> weekday.format(date)
        }
        return context.getString(R.string.index_day_at, day, time)
    }

    /** Narrow, lowercase weekday initials for the seven bars, oldest first; recomputed once a day. */
    fun dayLetters(now: Long): List<String> {
        val locale = formats()
        val key = dayKey.format(Date(now)) + locale
        if (key != lettersFor) {
            lettersFor = key
            letters = (6 downTo 0).map { narrowDay.format(Date(now - it * DAY)).lowercase(locale) }
        }
        return letters
    }

    /** Builds the formats when the locale changed; returns the current locale. */
    private fun formats(): Locale {
        val current = Locale.getDefault()
        if (current != locale) {
            locale = current
            time24 = SimpleDateFormat(DateFormat.getBestDateTimePattern(current, "Hm"), current)
            time12 = SimpleDateFormat(DateFormat.getBestDateTimePattern(current, "hm"), current)
            weekday = SimpleDateFormat("EEEE", current)
            narrowDay = SimpleDateFormat("EEEEE", current)
        }
        return current
    }
}

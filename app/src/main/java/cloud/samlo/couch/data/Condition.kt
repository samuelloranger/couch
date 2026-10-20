package cloud.samlo.couch.data

import cloud.samlo.couch.R

/**
 * Sky conditions, from WMO weather codes as used by Open-Meteo. [label] is a string resource
 * (0 when the code is unknown, which shows no text) and [icon] picks the day or night drawable.
 * The icons are Meteocons (fill style), mapped the way Labby maps its weather.
 */
enum class Condition(val label: Int, private val dayIcon: Int, private val nightIcon: Int = dayIcon) {
    CLEAR(R.string.weather_clear, R.drawable.wx_clear_day, R.drawable.wx_clear_night),
    MOSTLY_CLEAR(R.string.weather_mostly_clear, R.drawable.wx_partly_cloudy_day, R.drawable.wx_partly_cloudy_night),
    PARTLY_CLOUDY(R.string.weather_partly_cloudy, R.drawable.wx_cloudy),
    CLOUDY(R.string.weather_cloudy, R.drawable.wx_overcast),
    FOG(R.string.weather_fog, R.drawable.wx_mist),
    DRIZZLE(R.string.weather_drizzle, R.drawable.wx_drizzle),
    RAIN(R.string.weather_rain, R.drawable.wx_partly_cloudy_day_rain, R.drawable.wx_partly_cloudy_night_rain),
    SNOW(R.string.weather_snow, R.drawable.wx_partly_cloudy_day_snow, R.drawable.wx_partly_cloudy_night_snow),
    STORM(R.string.weather_storm, R.drawable.wx_thunderstorms_day_rain, R.drawable.wx_thunderstorms_night_rain),
    UNKNOWN(0, R.drawable.wx_cloudy),
    ;

    fun icon(isDay: Boolean) = if (isDay) dayIcon else nightIcon

    companion object {
        fun of(code: Int) = when (code) {
            0 -> CLEAR
            1 -> MOSTLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> STORM
            else -> UNKNOWN
        }
    }
}

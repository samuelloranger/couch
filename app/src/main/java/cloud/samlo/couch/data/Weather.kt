package cloud.samlo.couch.data

import android.content.SharedPreferences

/**
 * Current conditions from Open-Meteo (free, no key). The resolved place name is shown on the ambient
 * screen so a wrong guess is easy to spot. Called from the loader thread; the last good answer is kept in
 * preferences so a flaky connection still shows something.
 */
class Weather(private val fetcher: Fetcher, private val prefs: SharedPreferences) {
    private val places = WeatherPlaces(fetcher, prefs)

    fun now(config: Config): WeatherNow? {
        if (!config.weather) return null
        val cacheKey = "weather:${config.weatherCity}:${config.fahrenheit}"
        val cachedAt = prefs.getLong("$cacheKey:at", 0L)
        if (System.currentTimeMillis() - cachedAt < FRESH_MS) cached(cacheKey)?.let { return it }
        val place = places.find(config.weatherCity) ?: return cached(cacheKey)
        val json = fetcher.text(forecastUrl(place, config.fahrenheit)) ?: return cached(cacheKey)
        val now = WeatherNow.fromForecast(place.name, json) ?: return cached(cacheKey)
        prefs.edit().putString(cacheKey, now.serialize()).putLong("$cacheKey:at", System.currentTimeMillis()).apply()
        return now
    }

    private fun cached(key: String) = prefs.getString(key, null)?.let { WeatherNow.parse(it) }

    private fun forecastUrl(place: Place, fahrenheit: Boolean): String {
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        return "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
            "&forecast_days=1&timezone=auto$unit"
    }

    private companion object {
        const val FRESH_MS = 20 * 60_000L
    }
}

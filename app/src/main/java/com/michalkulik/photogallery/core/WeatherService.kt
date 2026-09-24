package com.michalkulik.photogallery.core

import com.michalkulik.photogallery.util.Logs
import com.michalkulik.photogallery.weather.Place
import com.michalkulik.photogallery.weather.Weather
import com.michalkulik.photogallery.weather.WeatherApi
import com.michalkulik.photogallery.weather.WeatherCondition
import com.michalkulik.photogallery.weather.WeatherSource
import java.util.Locale

/**
 * Supplies the temperature and the sky for the screensaver.
 *
 * Holds the policy - how long a reading and a location stay good, and what to show when the
 * network is unavailable - while [WeatherApi] does the requests.
 */
class WeatherService(private val settings: Settings) : WeatherSource {

    /**
     * The last reading, whether or not it is still fresh.
     *
     * Used to paint the corner immediately at startup: the screensaver is shown on a timer and
     * the first photo should not be bare while a request is in flight.
     */
    override fun cached(): Weather? {
        val temperature = settings.weatherTemperature ?: return null
        val condition = settings.weatherConditionName
            ?.let { runCatching { WeatherCondition.valueOf(it) }.getOrNull() }
            ?: return null
        return Weather(temperature, condition, settings.weatherIsDay)
    }

    /** How long a reading is reused before another request is made. */
    private fun readingIsFresh(): Boolean =
        System.currentTimeMillis() - settings.weatherFetchedAt < READING_TTL_MS

    /**
     * Returns the current reading, fetching one only when needed.
     *
     * A failed request is not an error worth surfacing: the previous reading is returned, so a
     * television that has lost its connection keeps showing the last known temperature rather
     * than a blank space that looks like a fault.
     */
    override suspend fun current(): Weather? {
        if (readingIsFresh()) return cached()
        return refresh()
    }

    /** Fetches a reading regardless of how recent the stored one is. */
    suspend fun refresh(): Weather? {
        val place = location() ?: return cached()
        val fresh = WeatherApi.current(
            latitude = place.latitude,
            longitude = place.longitude,
            apiKey = settings.weatherApiKey.orEmpty(),
            language = language(),
        ) ?: return cached()
        store(fresh)
        Logs.d("Weather for ${place.label}: ${fresh.display}, ${fresh.iconName}")
        return fresh
    }

    /** The interface language, so the service answers in the same one. */
    private fun language(): String = Locale.getDefault().language.ifBlank { "en" }

    /**
     * Where the reading is taken.
     *
     * An automatic location is an address lookup, so it is cached for a day: it changes only
     * when the connection's exit point does, and the service is not worth asking every time the
     * screensaver wakes.
     */
    suspend fun location(force: Boolean = false): Place? {
        val latitude = settings.weatherLatitude
        val longitude = settings.weatherLongitude
        if (!settings.weatherAutoLocation) {
            if (latitude == null || longitude == null) return null
            return Place(settings.weatherPlaceName.orEmpty(), "", latitude, longitude)
        }
        val known = latitude != null && longitude != null
        val recent = System.currentTimeMillis() - settings.weatherLocationTime < LOCATION_TTL_MS
        if (!force && known && recent) {
            return Place(settings.weatherPlaceName.orEmpty(), "", latitude, longitude)
        }
        val detected = WeatherApi.locateByAddress()
        if (detected == null) {
            // Keep the last known place rather than dropping to no location at all.
            return if (known) Place(settings.weatherPlaceName.orEmpty(), "", latitude, longitude) else null
        }
        settings.weatherLatitude = detected.latitude
        settings.weatherLongitude = detected.longitude
        settings.weatherPlaceName = detected.label
        settings.weatherLocationTime = System.currentTimeMillis()
        Logs.d("Location detected as ${detected.label}")
        return detected
    }

    /** The place as it would be described in the settings screen. */
    fun locationLabel(): String? = settings.weatherPlaceName

    suspend fun search(query: String, language: String): List<Place> =
        WeatherApi.searchPlaces(query, settings.weatherApiKey.orEmpty(), language)

    private fun store(weather: Weather) {
        settings.weatherTemperature = weather.temperatureCelsius
        settings.weatherConditionName = weather.condition.name
        settings.weatherIsDay = weather.isDay
        settings.weatherFetchedAt = System.currentTimeMillis()
    }

    private companion object {
        /**
         * Twenty minutes.
         *
         * The reading is rounded to whole degrees, so it changes slowly; asking more often would
         * put requests on the network for a number that would almost never differ.
         */
        const val READING_TTL_MS = 20 * 60 * 1000L

        /** A day: an address-derived location moves only when the connection's exit point does. */
        const val LOCATION_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

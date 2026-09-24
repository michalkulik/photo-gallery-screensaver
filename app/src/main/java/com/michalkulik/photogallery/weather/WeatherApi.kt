package com.michalkulik.photogallery.weather

import com.michalkulik.photogallery.util.Http
import com.michalkulik.photogallery.util.Logs
import java.net.URLEncoder
import java.util.Locale

/**
 * The OpenWeather calls the weather display needs.
 *
 * Stateless and blocking: every method must be called off the main thread. Free of caching and
 * settings so the answers can be checked against the service directly.
 *
 * One provider covers everything - the conditions, the icon and the place search - so a single
 * key is enough, and the coordinates the forecast is asked about are the ones the search
 * returned.
 */
object WeatherApi {

    private const val BASE_URL = "https://api.openweathermap.org"

    /**
     * Locates the device by its public address.
     *
     * The only automatic location a television has: there is no GPS, and the network provider
     * returns nothing on these boxes. Accurate to a city, which is all a temperature display
     * needs, but it places the device at its connection's exit point, so a VPN moves the reading
     * to the other end of it.
     */
    private const val ADDRESS_LOOKUP_URL = "https://ipwho.is/"

    /** Latitude or longitude in a URL; fixed notation so no exponent ever appears. */
    private fun coordinate(value: Double): String = String.format(Locale.US, "%.4f", value)

    /**
     * The current conditions.
     *
     * `units=metric` is what makes the temperature Celsius, and `lang` is what makes the
     * description come back in the language of the interface.
     */
    fun current(latitude: Double, longitude: Double, apiKey: String, language: String): Weather? {
        if (apiKey.isBlank()) {
            Logs.w("No OpenWeather key is set, so the weather cannot be read")
            return null
        }
        val url = "$BASE_URL/data/2.5/weather?lat=${coordinate(latitude)}" +
            "&lon=${coordinate(longitude)}" +
            "&units=metric" +
            "&lang=${URLEncoder.encode(language, "UTF-8")}" +
            "&appid=${URLEncoder.encode(apiKey, "UTF-8")}"
        val result = Http.getJson(url)
        if (!result.isSuccess) {
            Logs.w("Weather request failed: ${result.code} ${result.body.take(200)}")
            return null
        }
        return WeatherParsing.parseCurrent(result.body)
    }

    fun locateByAddress(): Place? {
        val result = Http.getJson(ADDRESS_LOOKUP_URL)
        if (!result.isSuccess) {
            Logs.w("Address lookup failed: ${result.code}")
            return null
        }
        return WeatherParsing.parseLocatedPlace(result.body)
    }

    /** Searches for a place by name, for the manual location setting. */
    fun searchPlaces(name: String, apiKey: String, language: String): List<Place> {
        val query = name.trim()
        if (query.isEmpty() || apiKey.isBlank()) return emptyList()
        val url = "$BASE_URL/geo/1.0/direct?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&limit=8" +
            "&appid=${URLEncoder.encode(apiKey, "UTF-8")}"
        val result = Http.getJson(url)
        if (!result.isSuccess) {
            Logs.w("Place search failed: ${result.code} ${result.body.take(200)}")
            return emptyList()
        }
        return WeatherParsing.parsePlaces(result.body)
    }
}

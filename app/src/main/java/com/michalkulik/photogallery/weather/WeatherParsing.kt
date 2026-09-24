package com.michalkulik.photogallery.weather

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the responses of OpenWeather.
 *
 * Kept apart from the networking so it can be tested against recorded answers, including the
 * malformed ones: a service that changes shape must leave the screensaver showing photos rather
 * than taking it down.
 */
object WeatherParsing {

    /**
     * Reads the current conditions.
     *
     * OpenWeather states the icon directly (`weather[0].icon`, e.g. `10n`), so there is no code
     * table to keep in step: the symbol shown is the one the service chose.
     */
    fun parseCurrent(body: String): Weather? = runCatching {
        val root = JSONObject(body)
        val main = root.optJSONObject("main") ?: return null
        if (!main.has("temp")) return null
        val temperature = main.optDouble("temp", Double.NaN)
        if (temperature.isNaN()) return null

        val icon = root.optJSONArray("weather")
            ?.optJSONObject(0)
            ?.optString("icon")
            .orEmpty()
        val parsed = parseIconName(icon)
        // A reply without a usable icon still carries a usable temperature, so it falls back to
        // overcast rather than throwing the reading away.
        Weather(
            temperatureCelsius = temperature,
            condition = parsed?.condition ?: WeatherCondition.OVERCAST,
            isDay = parsed?.isDay ?: true,
        )
    }.getOrNull()

    /**
     * Reads the place from the address lookup, which locates the device by its public address.
     *
     * The service answers with `success: false` and a message rather than an HTTP error when it
     * cannot place the address, so that flag is what decides whether the result can be used.
     */
    fun parseLocatedPlace(body: String): Place? = runCatching {
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) return null
        if (!root.has("latitude") || !root.has("longitude")) return null
        val latitude = root.optDouble("latitude", Double.NaN)
        val longitude = root.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null
        Place(
            name = root.optString("city").ifBlank { root.optString("region") }.ifBlank { "?" },
            country = root.optString("country"),
            latitude = latitude,
            longitude = longitude,
        )
    }.getOrNull()

    /**
     * Reads the matches from OpenWeather's geocoding search.
     *
     * The answer is a bare array rather than an object, and is empty when nothing matches, which
     * is a normal outcome for a misspelt name.
     */
    fun parsePlaces(body: String): List<Place> = runCatching {
        val results = JSONArray(body)
        (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val latitude = item.optDouble("lat", Double.NaN)
            val longitude = item.optDouble("lon", Double.NaN)
            if (latitude.isNaN() || longitude.isNaN()) return@mapNotNull null
            Place(
                name = item.optString("name"),
                country = item.optString("country"),
                latitude = latitude,
                longitude = longitude,
            )
        }
    }.getOrDefault(emptyList())
}

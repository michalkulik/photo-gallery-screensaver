package com.michalkulik.photogallery.weather

/**
 * The state of the sky, in the grouping OpenWeather uses for its icons.
 *
 * OpenWeather publishes nine symbols - clear, few clouds, scattered clouds, broken clouds,
 * shower, rain, thunderstorm, snow and mist - and only three of them have a separate night
 * drawing. Its own condition table folds roughly sixty codes into those nine, which is the same
 * problem this app has with the WMO codes Open-Meteo reports: several of those differ only in
 * how heavy the rain is, which a symbol of this size cannot show.
 *
 * Following that published grouping rather than inventing a new one keeps the mapping checkable
 * against a table, and matches symbols people already recognise from phone weather apps.
 *
 * The drawings come from [WeatherIconView], not from OpenWeather: their artwork is their
 * property and is not redistributed inside this app.
 */
enum class WeatherCondition(val code: String) {
    /** OpenWeather 01d / 01n. */
    CLEAR("01"),

    /** OpenWeather 02d / 02n - a few clouds, 11-25% cover. */
    FEW_CLOUDS("02"),

    /** OpenWeather 03d / 03n - scattered clouds, 25-50%. */
    SCATTERED_CLOUDS("03"),

    /** OpenWeather 04d / 04n - broken to overcast, 51-100%. */
    OVERCAST("04"),

    /** OpenWeather 09d / 09n - shower rain. */
    SHOWER("09"),

    /** OpenWeather 10d / 10n. */
    RAIN("10"),

    /** OpenWeather 11d / 11n. */
    THUNDERSTORM("11"),

    /** OpenWeather 13d / 13n. Used for freezing rain and sleet too, as OpenWeather does. */
    SNOW("13"),

    /** OpenWeather 50d / 50n - mist, fog, haze. */
    MIST("50");

    /**
     * The icon name this condition corresponds to, e.g. `01d`.
     *
     * Only [CLEAR], [FEW_CLOUDS] and [RAIN] are drawn differently after dark, which is the rule
     * OpenWeather's own list follows: a cloud looks the same in the dark, and so does the sky
     * behind it.
     */
    fun iconName(isDay: Boolean): String {
        if (!hasNightVariant) return code + "d"
        return code + if (isDay) "d" else "n"
    }

    /** Whether this condition is drawn differently at night. */
    val hasNightVariant: Boolean
        get() = this == CLEAR || this == FEW_CLOUDS || this == RAIN
}

/** A temperature reading together with the sky it was taken under. */
data class Weather(
    val temperatureCelsius: Double,
    val condition: WeatherCondition,
    val isDay: Boolean,
) {
    /**
     * The reading as it is shown, e.g. `12°`.
     *
     * Rounded rather than truncated, so 11.9 does not appear as 11, and without a decimal part
     * because at this distance from the screen it would only add noise.
     */
    val display: String get() = formatTemperature(temperatureCelsius)

    /** The icon this reading calls for, e.g. `01n`. */
    val iconName: String get() = condition.iconName(isDay)
}

/** A named place, used to describe where the weather is being read. */
data class Place(
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
) {
    /** `Warszawa, Polska` - the country is only added when it differs from the name. */
    val label: String get() = if (country.isBlank() || country == name) name else "$name, $country"
}

/** Rounds to whole degrees and appends the degree sign, keeping the minus sign. */
fun formatTemperature(celsius: Double): String {
    if (celsius.isNaN() || celsius.isInfinite()) return "--°"
    // Rounded away from zero rather than with Math.round, which breaks ties towards positive
    // infinity and would turn -4.5 into -4 while turning 4.5 into 5. Away from zero keeps the
    // two sides symmetrical, which is what "rounded to the nearest degree" is expected to mean.
    val rounded = if (celsius >= 0) Math.round(celsius) else -Math.round(-celsius)
    // -0.4 rounds to zero; "-0°" would be read as a real temperature below freezing.
    val normalised = if (rounded == 0L) 0L else rounded
    return "$normalised°"
}

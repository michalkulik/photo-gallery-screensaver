package com.michalkulik.photogallery.weather

/**
 * An icon name as OpenWeather states it, split into what it means.
 *
 * The name is a two digit group number and a `d` or `n`, for example `10n` for rain at night.
 */
data class ParsedIcon(val condition: WeatherCondition, val isDay: Boolean)

/**
 * Reads an OpenWeather icon name such as `10n`.
 *
 * Returns null for anything that is not one of the names in the set, so a value the service
 * adds later is treated as "no icon" rather than being guessed at. The group number is matched
 * against the same grouping [WeatherCondition] already encodes, so there is one list of names
 * rather than two that can drift apart.
 */
fun parseIconName(icon: String): ParsedIcon? {
    if (icon.length != 3) return null
    val group = icon.substring(0, 2)
    val condition = WeatherCondition.entries.firstOrNull { it.code == group } ?: return null
    return when (icon[2]) {
        'd' -> ParsedIcon(condition, isDay = true)
        'n' -> ParsedIcon(condition, isDay = false)
        else -> null
    }
}

/** Rounds a coordinate for display, e.g. `52.2300, 21.0100`. */
fun formatCoordinates(latitude: Double, longitude: Double): String =
    "%.4f, %.4f".format(latitude, longitude)

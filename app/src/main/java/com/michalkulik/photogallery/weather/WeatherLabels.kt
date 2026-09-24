package com.michalkulik.photogallery.weather

import com.michalkulik.photogallery.R

/**
 * The name of a condition, in the language of the interface.
 *
 * Used as the accessibility label of the icon: the symbol carries the meaning of the reading
 * and a screen reader would otherwise announce an unlabelled graphic next to a bare number.
 */
fun WeatherCondition.labelRes(): Int = when (this) {
    WeatherCondition.CLEAR -> R.string.weather_clear
    WeatherCondition.FEW_CLOUDS -> R.string.weather_few_clouds
    WeatherCondition.SCATTERED_CLOUDS -> R.string.weather_scattered_clouds
    WeatherCondition.OVERCAST -> R.string.weather_overcast
    WeatherCondition.SHOWER -> R.string.weather_shower
    WeatherCondition.RAIN -> R.string.weather_rain
    WeatherCondition.THUNDERSTORM -> R.string.weather_thunderstorm
    WeatherCondition.SNOW -> R.string.weather_snow
    WeatherCondition.MIST -> R.string.weather_mist
}

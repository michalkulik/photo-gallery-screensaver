package com.michalkulik.photogallery.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers reading an icon name and the way a temperature is written.
 *
 * Worth testing on the JVM because both are invisible in review: an icon name sent to the wrong
 * symbol still shows a plausible weather picture, and nobody notices that heavy rain became
 * cloud. That kind of fault also only appears under one particular sky.
 */
class WeatherRulesTest {

    @Test
    fun `reads every icon name in the set`() {
        // The nine symbols, of which three also have a night drawing.
        assertEquals(WeatherCondition.CLEAR, parseIconName("01d")!!.condition)
        assertEquals(WeatherCondition.FEW_CLOUDS, parseIconName("02d")!!.condition)
        assertEquals(WeatherCondition.SCATTERED_CLOUDS, parseIconName("03d")!!.condition)
        assertEquals(WeatherCondition.OVERCAST, parseIconName("04d")!!.condition)
        assertEquals(WeatherCondition.SHOWER, parseIconName("09d")!!.condition)
        assertEquals(WeatherCondition.RAIN, parseIconName("10d")!!.condition)
        assertEquals(WeatherCondition.THUNDERSTORM, parseIconName("11d")!!.condition)
        assertEquals(WeatherCondition.SNOW, parseIconName("13d")!!.condition)
        assertEquals(WeatherCondition.MIST, parseIconName("50d")!!.condition)
    }

    @Test
    fun `reads the day and night suffix`() {
        assertTrue(parseIconName("01d")!!.isDay)
        assertFalse(parseIconName("01n")!!.isDay)
        assertFalse(parseIconName("10n")!!.isDay)
        assertFalse(parseIconName("02n")!!.isDay)
    }

    @Test
    fun `an icon name outside the set is refused rather than guessed at`() {
        // The service may add names. A guess would put a confident symbol on an unknown sky,
        // and would also ask for an image that is not on disk.
        assertNull(parseIconName("99d"))
        assertNull(parseIconName("00d"))
        assertNull(parseIconName("01x"))
        assertNull(parseIconName("01"))
        assertNull(parseIconName("01dd"))
        assertNull(parseIconName(""))
        assertNull(parseIconName("abcd"))
    }

    @Test
    fun `only three conditions have a night drawing`() {
        // The rule OpenWeather's own icon list follows: a cloud looks the same in the dark.
        // Getting this wrong asks for an icon file that does not exist.
        assertTrue(WeatherCondition.CLEAR.hasNightVariant)
        assertTrue(WeatherCondition.FEW_CLOUDS.hasNightVariant)
        assertTrue(WeatherCondition.RAIN.hasNightVariant)
        assertEquals(6, WeatherCondition.entries.count { !it.hasNightVariant })

        // At night those still name a `d` icon, because there is no `n` file to name.
        assertEquals("04d", WeatherCondition.OVERCAST.iconName(isDay = false))
        assertEquals("11d", WeatherCondition.THUNDERSTORM.iconName(isDay = false))
        assertEquals("01n", WeatherCondition.CLEAR.iconName(isDay = false))
    }

    @Test
    fun `every icon name a condition can produce is one of the twelve`() {
        // The set on disk is fixed, so a name built here that is not in it would draw nothing
        // at all and leave a gap beside the temperature.
        val available = setOf(
            "01d", "01n", "02d", "02n", "03d", "04d",
            "09d", "10d", "10n", "11d", "13d", "50d",
        )
        val produced = WeatherCondition.entries.flatMap { condition ->
            listOf(condition.iconName(isDay = true), condition.iconName(isDay = false))
        }
        assertTrue("produced $produced", available.containsAll(produced))
    }

    @Test
    fun `temperature is rounded to whole degrees`() {
        assertEquals("12°", formatTemperature(11.9))
        assertEquals("12°", formatTemperature(12.0))
        assertEquals("12°", formatTemperature(12.4))
        // Rounding has to go up here; truncating would show 12° for 12.6.
        assertEquals("13°", formatTemperature(12.6))
        assertEquals("0°", formatTemperature(0.2))
        assertEquals("-3°", formatTemperature(-3.4))
    }

    @Test
    fun `a temperature just below zero is not written as minus zero`() {
        // Math.round(-0.4) is 0, so the sign has to be dropped with it.
        assertEquals("0°", formatTemperature(-0.4))
        assertEquals("0°", formatTemperature(-0.0))
    }

    @Test
    fun `halves round away from zero on both sides`() {
        // Math.round alone breaks ties towards positive infinity: 4.5 becomes 5 but -4.5
        // becomes -4, so a reading and its mirror image would not round to mirror values.
        assertEquals("5°", formatTemperature(4.5))
        assertEquals("-5°", formatTemperature(-4.5))
        assertEquals("2°", formatTemperature(1.5))
        assertEquals("-2°", formatTemperature(-1.5))
    }

    @Test
    fun `a missing temperature is shown as a dash rather than as a number`() {
        // Only reaches the view if something is badly wrong; the point is that NaN must never
        // be printed as "NaN°".
        assertEquals("--°", formatTemperature(Double.NaN))
        assertEquals("--°", formatTemperature(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a place joins the country unless it repeats the name`() {
        assertEquals("Warszawa, PL", Place("Warszawa", "PL", 52.23, 21.01).label)
        assertEquals("Warszawa", Place("Warszawa", "Warszawa", 52.23, 21.01).label)
        assertEquals("Warszawa", Place("Warszawa", "", 52.23, 21.01).label)
    }

    @Test
    fun `the reading carries its own display text and icon`() {
        val weather = Weather(11.9, WeatherCondition.RAIN, isDay = false)
        assertEquals("12°", weather.display)
        assertEquals("10n", weather.iconName)
    }
}

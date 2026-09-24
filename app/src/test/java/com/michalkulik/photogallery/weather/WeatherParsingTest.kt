package com.michalkulik.photogallery.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers reading the two services, using the bodies they actually answered with.
 *
 * The malformed cases matter more than the happy ones: these run inside the screensaver, and an
 * unexpected answer must leave it showing photos instead of taking it down.
 */
class WeatherParsingTest {

    @Test
    fun `reads the current conditions from OpenWeather`() {
        // Trimmed from a real reply for Warsaw.
        val body = """
            {"coord":{"lon":21.0118,"lat":52.2298},
             "weather":[{"id":804,"main":"Clouds","description":"zachmurzenie duże","icon":"04d"}],
             "base":"stations",
             "main":{"temp":11.9,"feels_like":10.4,"humidity":77},
             "name":"Warsaw","cod":200}
        """.trimIndent()

        val weather = WeatherParsing.parseCurrent(body)

        assertEquals(11.9, weather!!.temperatureCelsius, 0.001)
        assertEquals(WeatherCondition.OVERCAST, weather.condition)
        assertTrue(weather.isDay)
        assertEquals("04d", weather.iconName)
    }

    @Test
    fun `reads a night time reading`() {
        val body = """
            {"weather":[{"id":800,"main":"Clear","description":"bezchmurnie","icon":"01n"}],
             "main":{"temp":-4.5},"cod":200}
        """.trimIndent()

        val weather = WeatherParsing.parseCurrent(body)

        assertEquals("-5°", weather!!.display)
        assertEquals(WeatherCondition.CLEAR, weather.condition)
        assertEquals(false, weather.isDay)
        assertEquals("01n", weather.iconName)
    }

    @Test
    fun `refuses a reply with no temperature`() {
        // Without one there is nothing to show, so the previous reading is kept.
        assertNull(WeatherParsing.parseCurrent("""{"main":{"humidity":77}}"""))
        assertNull(WeatherParsing.parseCurrent("""{"main":{"temp":null}}"""))
        assertNull(WeatherParsing.parseCurrent("""{"weather":[]}"""))
        assertNull(WeatherParsing.parseCurrent("not json at all"))
        assertNull(WeatherParsing.parseCurrent(""))
    }

    @Test
    fun `refuses the error object the service answers with`() {
        // A wrong key comes back as 200 with an error object, so the body is what has to be
        // rejected rather than the status code.
        val body = """{"cod":401,"message":"Invalid API key. Please see https://openweathermap.org/faq#error401"}"""

        assertNull(WeatherParsing.parseCurrent(body))
    }

    @Test
    fun `keeps the temperature when the reply carries no icon`() {
        // The temperature is the point of the display; a missing icon should not lose it.
        val weather = WeatherParsing.parseCurrent("""{"main":{"temp":5.0},"weather":[]}""")

        assertEquals("5°", weather!!.display)
        assertEquals(WeatherCondition.OVERCAST, weather.condition)
        assertTrue(weather.isDay)
    }

    @Test
    fun `falls back to overcast for an icon name it does not know`() {
        val body = """{"main":{"temp":3.0},"weather":[{"icon":"77d"}]}"""

        assertEquals(WeatherCondition.OVERCAST, WeatherParsing.parseCurrent(body)!!.condition)
    }

    @Test
    fun `reads the place from the address lookup`() {
        // Trimmed from a real ipwho.is reply.
        val body = """
            {"ip":"193.238.180.1","success":true,"type":"IPv4","continent":"Europe",
             "country":"Poland","region":"Masovian Voivodeship","city":"Warsaw",
             "latitude":52.2297657,"longitude":21.0117835}
        """.trimIndent()

        val place = WeatherParsing.parseLocatedPlace(body)

        assertEquals("Warsaw", place!!.name)
        assertEquals("Poland", place.country)
        assertEquals(52.2297657, place.latitude, 0.0000001)
        assertEquals("Warsaw, Poland", place.label)
    }

    @Test
    fun `falls back to the region when the lookup names no city`() {
        val body = """{"success":true,"region":"Masovian","country":"Poland","latitude":52.2,"longitude":21.0}"""

        assertEquals("Masovian", WeatherParsing.parseLocatedPlace(body)!!.name)
    }

    @Test
    fun `refuses an address lookup that reports failure`() {
        // ipwho.is answers 200 with success:false when it cannot place the address, so the flag
        // is what decides, not the status code.
        assertNull(WeatherParsing.parseLocatedPlace("""{"success":false,"message":"Reserved range"}"""))
        assertNull(WeatherParsing.parseLocatedPlace("""{"success":true,"city":"Warsaw"}"""))
        assertNull(WeatherParsing.parseLocatedPlace("""{"ip":"1.2.3.4"}"""))
        assertNull(WeatherParsing.parseLocatedPlace("<html>nope</html>"))
    }

    @Test
    fun `reads the matches from the place search`() {
        // OpenWeather's geocoder answers with a bare array and short keys.
        val body = """
            [{"name":"Warszawa","lat":52.22977,"lon":21.01178,"country":"PL",
              "state":"Masovian Voivodeship"},
             {"name":"Warsaw","lat":41.2381,"lon":-85.8531,"country":"US",
              "state":"Indiana"}]
        """.trimIndent()

        val places = WeatherParsing.parsePlaces(body)

        assertEquals(2, places.size)
        assertEquals("Warszawa, PL", places[0].label)
        assertEquals(-85.8531, places[1].longitude, 0.00001)
    }

    @Test
    fun `an unmatched search is an empty list, not a failure`() {
        // A misspelt name is normal, so it must not be treated as an error.
        assertTrue(WeatherParsing.parsePlaces("[]").isEmpty())
        assertTrue(WeatherParsing.parsePlaces("").isEmpty())
        assertTrue(WeatherParsing.parsePlaces("{").isEmpty())
    }

    @Test
    fun `a match without coordinates is skipped rather than placed at zero`() {
        // Latitude 0 is a real place in the Atlantic, so a missing value must not become one.
        val body = """[{"name":"Warszawa","country":"PL"},
                       {"name":"Kraków","lat":50.06,"lon":19.94,"country":"PL"}]"""

        val places = WeatherParsing.parsePlaces(body)

        assertEquals(1, places.size)
        assertEquals("Kraków", places[0].name)
    }
}

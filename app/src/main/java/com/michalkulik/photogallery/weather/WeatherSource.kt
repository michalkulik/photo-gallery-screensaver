package com.michalkulik.photogallery.weather

/**
 * Where the slideshow gets the temperature it shows.
 *
 * An interface rather than the service itself so the slideshow stays free of networking and
 * settings, and so the view can be driven with a fixed reading in a test.
 */
interface WeatherSource {

    /** The last known reading, or null when there is none yet. Never blocks or fetches. */
    fun cached(): Weather?

    /** The current reading, fetching one if the stored one is stale. */
    suspend fun current(): Weather?
}

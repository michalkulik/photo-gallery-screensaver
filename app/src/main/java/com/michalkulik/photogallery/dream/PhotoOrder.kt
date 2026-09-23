package com.michalkulik.photogallery.dream

import com.michalkulik.photogallery.data.Photo
import kotlin.random.Random

/**
 * Turns the raw photo list into the order the slideshow plays it in.
 * Pure and deterministic for a given [Random], so it is covered by unit tests.
 */
object PhotoOrder {

    fun arrange(photos: List<Photo>, order: PlayOrder, random: Random): List<Photo> = when (order) {
        PlayOrder.SEQUENTIAL -> photos.sortedWith(
            compareByDescending<Photo> { it.dateAdded }.thenBy { it.id },
        )

        PlayOrder.SHUFFLE -> photos.shuffled(random)
    }
}

package com.michalkulik.photogallery.dream

import com.michalkulik.photogallery.data.Photo
import kotlin.random.Random

/**
 * Turns the raw photo list into the order the slideshow plays it in.
 * Pure and deterministic for a given [Random], so it is covered by unit tests.
 */
object PhotoOrder {

    /**
     * @param seenIds photos already shown in the current pass, from [PlaybackHistory]. They are
     *   played last rather than dropped: a shuffle on its own guarantees that a pass contains no
     *   repeats, but it says nothing about two consecutive passes, and those are what look like
     *   a fault when the screensaver restarts.
     */
    fun arrange(
        photos: List<Photo>,
        order: PlayOrder,
        random: Random,
        seenIds: Set<String> = emptySet(),
    ): List<Photo> = when (order) {
        PlayOrder.SEQUENTIAL -> photos.sortedWith(
            compareByDescending<Photo> { it.dateAdded }.thenBy { it.id },
        )

        PlayOrder.SHUFFLE -> shuffleUnseenFirst(photos, random, seenIds)
    }

    /**
     * Shuffles the photos that have not been shown yet, then those that have.
     *
     * The two groups are shuffled separately so the split survives: shuffling one combined list
     * would put an already-seen photo back among the new ones at random.
     */
    private fun shuffleUnseenFirst(
        photos: List<Photo>,
        random: Random,
        seenIds: Set<String>,
    ): List<Photo> {
        if (seenIds.isEmpty()) return photos.shuffled(random)
        val (unseen, seen) = photos.partition { it.id !in seenIds }
        // Every photo has been shown, so this is the start of a new pass.
        if (unseen.isEmpty()) return photos.shuffled(random)
        return unseen.shuffled(random) + seen.shuffled(random)
    }
}

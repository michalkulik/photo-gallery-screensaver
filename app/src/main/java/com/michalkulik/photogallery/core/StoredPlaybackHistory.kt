package com.michalkulik.photogallery.core

import com.michalkulik.photogallery.dream.PlaybackHistory

/**
 * Keeps the pass of photos that have already been shown, in the app's own preferences.
 *
 * It has to outlive the screensaver: the dream service is created and destroyed with every
 * screen-off cycle, so a history held only in memory would be empty at each start and the
 * slideshow would begin a fresh shuffle every time. That is precisely the behaviour this exists
 * to prevent.
 */
class StoredPlaybackHistory(private val settings: Settings) : PlaybackHistory {

    override fun seenIds(): Set<String> = settings.playedPhotoIds

    override fun markShown(id: String) {
        val seen = settings.playedPhotoIds
        // Cheap guard: this runs once per photo, and re-writing an unchanged set would mean a
        // preferences write on every slide.
        if (id in seen) return
        settings.playedPhotoIds = seen + id
    }

    override fun clear() {
        settings.playedPhotoIds = emptySet()
    }
}

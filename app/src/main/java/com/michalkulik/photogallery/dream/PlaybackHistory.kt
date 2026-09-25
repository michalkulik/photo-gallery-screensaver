package com.michalkulik.photogallery.dream

/**
 * Remembers which photos have already been shown in the current pass.
 *
 * The playlist is reshuffled every time the slideshow starts - which happens each time the
 * screensaver wakes, so possibly several times an evening. Without this, every start began a
 * brand new shuffle from its first entry, and because a shuffle is random the first photo of the
 * new pass could easily be one shown a moment earlier. Over short viewing sessions that reads as
 * "the same photo keeps coming back".
 *
 * With it, a start continues the pass instead of restarting it: photos not yet shown come first,
 * and only once every photo has been seen does the set start again.
 */
interface PlaybackHistory {

    /** Ids of the photos already shown in this pass. */
    fun seenIds(): Set<String>

    /** Records that a photo has been shown. */
    fun markShown(id: String)

    /** Begins a new pass. */
    fun clear()
}

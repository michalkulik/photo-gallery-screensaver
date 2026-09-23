package com.michalkulik.photogallery.google

/**
 * Pure helpers that turn a [PickedMediaItem] into a downloadable URL and a safe file name.
 * Separated from [GooglePhotosImporter] so they can be unit tested without Android classes.
 */
object GoogleMediaUrls {

    /** Requested image resolution; big enough for a 4K TV without wasting bandwidth. */
    const val IMAGE_SIZE = "w2560-h1440"

    /**
     * Appends the sizing parameters Google requires on a `baseUrl`.
     * Videos are fetched as a still thumbnail without the playback overlay, because the
     * screensaver only ever shows a single frame anyway.
     */
    fun downloadUrl(item: PickedMediaItem, size: String = IMAGE_SIZE): String {
        val suffix = if (item.isVideo) "=$size-no" else "=$size"
        return item.baseUrl + suffix
    }

    /** Media item ids are opaque; reduce them to characters that are safe in a file name. */
    fun sanitizeId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "photo" }
}

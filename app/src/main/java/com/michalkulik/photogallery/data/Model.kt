package com.michalkulik.photogallery.data

/** Where a photo source gets its photos from. */
enum class SourceKind {
    /** Images already on the TV (MediaStore). */
    LOCAL,

    /** Albums/photos picked in Google Photos and cached on the device. */
    GOOGLE,

    /**
     * An album on a Synology DiskStation.
     *
     * Unlike the other kinds these are not copied to the TV: the list is re-read from the NAS
     * every time the screensaver starts, so photos added to the album show up on their own.
     */
    SYNO,
}

/**
 * A named collection of photos that the screensaver can play.
 *
 * @param ref for [SourceKind.LOCAL] the MediaStore bucket id (`ALL` for the whole library),
 *            for [SourceKind.GOOGLE] the id of the local cache directory,
 *            for [SourceKind.SYNO] the Synology album id (`0` for the whole library).
 */
data class PhotoSource(
    val id: String,
    val kind: SourceKind,
    val name: String,
    val ref: String,
    val photoCount: Int = 0,
)

/** A single image the slideshow can display. */
data class Photo(
    val id: String,
    val uri: String,
    val sourceId: String,
    val dateAdded: Long = 0L,
    /** Clockwise rotation in degrees that must be applied when decoding (EXIF orientation). */
    val orientation: Int = 0,
    /**
     * Stable identity used as the file name when a remote image is cached locally.
     *
     * A Synology image URL carries a session id that changes on every login, so hashing the URL
     * itself would miss the cache every time. Null for photos that are already local.
     */
    val cacheKey: String? = null,
    /**
     * True when this remote photo must be fetched with relaxed TLS validation.
     *
     * Set for a NAS that is reached by IP while its certificate is issued for a hostname.
     */
    val allowInsecureTls: Boolean = false,
    /**
     * A second URL to try when [uri] does not return an image.
     *
     * Used for Synology photos: the shared and personal spaces have separate download APIs and
     * neither serves the other's photos, so a wrong guess has to be recoverable.
     */
    val fallbackUri: String? = null,
)

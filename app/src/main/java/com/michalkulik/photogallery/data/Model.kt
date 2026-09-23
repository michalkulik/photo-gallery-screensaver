package com.michalkulik.photogallery.data

/** Where a photo source gets its photos from. */
enum class SourceKind {
    /** Images already on the TV (MediaStore). */
    LOCAL,

    /** Albums/photos picked in Google Photos and cached on the device. */
    GOOGLE,
}

/**
 * A named collection of photos that the screensaver can play.
 *
 * @param ref for [SourceKind.LOCAL] the MediaStore bucket id (`ALL` for the whole library),
 *            for [SourceKind.GOOGLE] the id of the local cache directory.
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
)

package com.michalkulik.photogallery.ui

import android.content.Context
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.data.LocalMedia
import com.michalkulik.photogallery.data.PhotoSource
import com.michalkulik.photogallery.data.SourceKind
import java.text.DateFormat
import java.util.Date

/** Human readable name of a source; the "all photos" bucket has no stored name on purpose. */
fun PhotoSource.displayName(context: Context): String = when {
    kind == SourceKind.LOCAL && ref == LocalMedia.ALL_BUCKETS -> context.getString(R.string.sources_all_photos)
    kind == SourceKind.LOCAL -> name.ifBlank { ref }
    kind == SourceKind.SYNO -> name.ifBlank { context.getString(R.string.syno_all_photos) }
    else -> name.ifBlank { context.getString(R.string.sources_google_unnamed) }
}

/** Builds the label used when a new Google Photos selection is imported. */
fun googleSourceName(): String =
    "Google Photos · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())

/**
 * Localised "N photos" label. Polish needs the `one`/`few`/`many` forms, so this must go
 * through [Context.getResources.getQuantityString] rather than a plain formatted string.
 */
fun photoCountText(context: Context, count: Int): String =
    context.resources.getQuantityString(R.plurals.photo_count, count, count)

/** Localised "Imported N photos" message; same plural rules as [photoCountText]. */
fun importedPhotosText(context: Context, count: Int): String =
    context.resources.getQuantityString(R.plurals.imported_photos, count, count)

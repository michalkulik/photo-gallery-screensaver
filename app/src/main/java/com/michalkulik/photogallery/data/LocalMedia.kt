package com.michalkulik.photogallery.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.michalkulik.photogallery.util.Logs

/** One MediaStore image folder on the device. */
data class MediaBucket(val id: String, val name: String, val photoCount: Int)

/**
 * Reads images from the local gallery through MediaStore. This backs the
 * "folder from this device" photo source type.
 */
object LocalMedia {

    /** Bucket id used when the user wants every image on the device. */
    const val ALL_BUCKETS = "ALL"

    /** Upper bound on how many local photos a source exposes to the slideshow. */
    const val DEFAULT_LIMIT = 500

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun requiredPermissions(): Array<String> = permissions

    fun hasPermission(context: Context): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Lists image folders with their photo counts, plus a synthetic "all photos" entry
     * ([ALL_BUCKETS]) whose name is intentionally empty so the UI can localise it.
     */
    fun buckets(context: Context): List<MediaBucket> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val counts = LinkedHashMap<String, Int>()
        val names = HashMap<String, String>()
        var total = 0
        query(context.contentResolver, projection, null, null) { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                total++
                val bucketId = cursor.getString(idColumn) ?: continue
                counts[bucketId] = (counts[bucketId] ?: 0) + 1
                if (!names.containsKey(bucketId)) {
                    names[bucketId] = cursor.getString(nameColumn) ?: bucketId
                }
            }
        }

        val result = ArrayList<MediaBucket>()
        result += MediaBucket(ALL_BUCKETS, "", total)
        counts.entries
            .sortedBy { names[it.key]?.lowercase() ?: it.key }
            .forEach { (bucketId, count) ->
                result += MediaBucket(bucketId, names[bucketId] ?: bucketId, count)
            }
        return result
    }

    /** Returns up to [limit] newest images of [bucketId] ([ALL_BUCKETS] for the whole library). */
    fun photos(context: Context, bucketId: String, limit: Int = DEFAULT_LIMIT): List<Photo> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.ORIENTATION,
        )
        val isAll = bucketId == ALL_BUCKETS
        val selection = if (isAll) null else "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = if (isAll) null else arrayOf(bucketId)
        val result = ArrayList<Photo>()
        query(context.contentResolver, projection, selection, selectionArgs, limit) { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                result += Photo(
                    id = id.toString(),
                    uri = contentUri.toString(),
                    sourceId = bucketId,
                    dateAdded = cursor.getLong(dateColumn),
                    orientation = if (orientationColumn >= 0) cursor.getInt(orientationColumn) else 0,
                )
            }
        }
        return result
    }

    /**
     * Single query helper. Selection, sort order and limit are all passed through the
     * [Bundle] overload so they can be combined; mixing that with the string overload
     * silently dropped the bucket filter whenever a limit was used.
     */
    private inline fun query(
        resolver: ContentResolver,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int? = null,
        body: (Cursor) -> Unit,
    ) {
        val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val extras = Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_ADDED),
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            if (selection != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
            if (limit != null) {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
        }
        try {
            resolver.query(uri, projection, extras, null)?.use(body)
        } catch (error: Exception) {
            Logs.w("MediaStore query failed", error)
        }
    }
}

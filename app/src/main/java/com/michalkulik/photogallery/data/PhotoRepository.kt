package com.michalkulik.photogallery.data

import android.content.Context
import com.michalkulik.photogallery.core.Settings
import java.io.File

/**
 * Facade over the configured [PhotoSource]s and the photos they expose.
 * The UI works against this; the dream service uses [activePhotos] only.
 */
class PhotoRepository(
    private val context: Context,
    private val settings: Settings,
    val cache: PhotoCache,
) {

    fun sources(): List<PhotoSource> = SourceCodec.decode(settings.sourcesJson)

    private fun save(sources: List<PhotoSource>) {
        settings.sourcesJson = SourceCodec.encode(sources)
    }

    /** Adds a source or replaces the existing entry with the same id. */
    fun addOrUpdate(source: PhotoSource) {
        val current = sources().filterNot { it.id == source.id }.toMutableList()
        current += source
        save(current)
        if (settings.activeSourceId == null || settings.activeSourceId == source.id) {
            settings.activeSourceId = source.id
        }
    }

    fun remove(sourceId: String) {
        val source = sources().firstOrNull { it.id == sourceId }
        save(sources().filterNot { it.id == sourceId })
        if (source != null && source.kind == SourceKind.GOOGLE) {
            cache.delete(source.ref)
        }
        if (settings.activeSourceId == sourceId) {
            settings.activeSourceId = sources().firstOrNull()?.id
        }
    }

    fun setActive(sourceId: String?) {
        settings.activeSourceId = sourceId
    }

    fun activeSource(): PhotoSource? {
        val all = sources()
        return all.firstOrNull { it.id == settings.activeSourceId } ?: all.firstOrNull()
    }

    fun photosFor(source: PhotoSource): List<Photo> = when (source.kind) {
        SourceKind.LOCAL -> LocalMedia.photos(context, source.ref)
        SourceKind.GOOGLE -> cache.photos(source.ref)
    }

    /** Photos the screensaver should play right now. */
    fun activePhotos(): List<Photo> = activeSource()?.let { photosFor(it) }.orEmpty()

    /** Recomputes and persists the photo count of a source (used after imports). */
    fun refreshCount(sourceId: String) {
        val all = sources()
        val source = all.firstOrNull { it.id == sourceId } ?: return
        val count = photosFor(source).size
        if (source.photoCount != count) {
            save(all.map { if (it.id == sourceId) it.copy(photoCount = count) else it })
        }
    }

    companion object {
        fun newSourceId(kind: SourceKind, ref: String): String = "${kind.name.lowercase()}:$ref"

        fun newCacheRef(): String = "album_" + System.currentTimeMillis().toString(36)

        fun cacheRoot(context: Context): File = File(context.filesDir, "photos")
    }
}

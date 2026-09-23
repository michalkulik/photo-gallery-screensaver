package com.michalkulik.photogallery.data

import java.io.File

/**
 * Stores Google Photos images that were downloaded earlier so the screensaver can run
 * without a network connection (Google photo URLs expire after one hour).
 */
class PhotoCache(private val root: File) {

    /** Directory that holds the cached images of one source. */
    fun directoryFor(ref: String): File = File(root, ref)

    fun fileFor(ref: String, mediaId: String): File = File(directoryFor(ref), "$mediaId.jpg")

    /** Lists the already downloaded photos of a source, oldest download first. */
    fun photos(ref: String): List<Photo> =
        directoryFor(ref).listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedBy { it.name }
            ?.map { file ->
                Photo(
                    id = file.nameWithoutExtension,
                    uri = file.absolutePath,
                    sourceId = ref,
                    dateAdded = file.lastModified(),
                )
            }
            .orEmpty()

    fun count(ref: String): Int = directoryFor(ref).listFiles()?.count { it.isFile && it.length() > 0 } ?: 0

    /** Removes cached images that are no longer part of the picker selection. */
    fun prune(ref: String, keepIds: Set<String>) {
        directoryFor(ref).listFiles()?.forEach { file ->
            if (file.isFile && file.nameWithoutExtension !in keepIds) {
                file.delete()
            }
        }
    }

    fun delete(ref: String) {
        directoryFor(ref).deleteRecursively()
    }

    fun totalBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

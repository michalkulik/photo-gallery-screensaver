package com.michalkulik.photogallery.google

import com.michalkulik.photogallery.data.PhotoCache
import com.michalkulik.photogallery.util.Http
import com.michalkulik.photogallery.util.Logs
import kotlinx.coroutines.delay

/**
 * Drives the full Google Photos import: create a picking session, wait for the user to pick
 * photos/albums, then download the selection into the local [PhotoCache].
 *
 * Google photo URLs expire after ~60 minutes, so caching the bytes is what makes the screensaver
 * work later and offline.
 */
class GooglePhotosImporter(
    private val auth: GoogleAuth,
    private val picker: PickerClient,
    private val cache: PhotoCache,
) {

    /** Starts a picking session for the signed-in user. */
    fun beginSession(): PickerSession = picker.createSession()

    /**
     * Polls the session until the user confirms a selection.
     *
     * @return true when items are ready, false when the session expired.
     */
    suspend fun awaitSelection(session: PickerSession, onPoll: (PickerSession) -> Unit = {}): Boolean {
        val deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS
        var interval = session.pollIntervalMillis
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val current = try {
                picker.getSession(session.id)
            } catch (error: GoogleApiException) {
                Logs.w("Picker session poll failed", error)
                return false
            }
            onPoll(current)
            if (current.mediaItemsSet) return true
            interval = current.pollIntervalMillis
        }
        return false
    }

    /**
     * Downloads every picked photo into the cache directory of [ref].
     *
     * @param onProgress receives (completed, total) so the UI can show a progress bar.
     * @return number of photos stored.
     */
    fun importSelection(
        sessionId: String,
        ref: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        val items = picker.listPickedItems(sessionId)
        val photos = items.filter { it.baseUrl.isNotBlank() }
        if (photos.isEmpty()) return 0

        val token = auth.accessToken() ?: throw GoogleApiException("not_signed_in")
        var completed = 0
        var saved = 0
        photos.forEach { item ->
            val url = GoogleMediaUrls.downloadUrl(item)
            val destination = cache.fileFor(ref, GoogleMediaUrls.sanitizeId(item.id))
            val ok = Http.download(url, token, destination)
            completed++
            if (ok) {
                saved++
            } else {
                Logs.w("Skipped photo ${item.filename}")
            }
            onProgress(completed, photos.size)
        }
        picker.deleteSession(sessionId)
        return saved
    }

    private companion object {
        /** Google expires a picking session after about an hour of inactivity. */
        const val SESSION_TIMEOUT_MS = 60 * 60 * 1000L
    }
}

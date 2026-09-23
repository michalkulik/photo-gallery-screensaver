package com.michalkulik.photogallery.google

import com.michalkulik.photogallery.BuildConfig
import com.michalkulik.photogallery.util.Http

/**
 * Thin client for the Google Photos **Picker API**. The Picker API is the only supported way
 * (since the April 2025 Library API scope removal) for an app to access photos the user already
 * has in Google Photos.
 */
class PickerClient(private val auth: GoogleAuth, private val baseUrl: String = BuildConfig.PICKER_API_BASE) {

    /** Creates a picking session and returns its `pickerUri` for the user to open. */
    fun createSession(): PickerSession {
        val token = auth.accessToken() ?: throw GoogleApiException("not_signed_in")
        val result = Http.postJson("$baseUrl/sessions", "{}", token)
        if (!result.isSuccess) {
            throw GoogleApiException("create_session_failed (${result.code}) ${result.body.take(300)}")
        }
        return GoogleParsers.parseSession(result.body)
    }

    fun getSession(sessionId: String): PickerSession {
        val token = auth.accessToken() ?: throw GoogleApiException("not_signed_in")
        val result = Http.getJson("$baseUrl/sessions/$sessionId", token)
        if (!result.isSuccess) {
            throw GoogleApiException("get_session_failed (${result.code}) ${result.body.take(300)}")
        }
        return GoogleParsers.parseSession(result.body)
    }

    /** Lists everything the user picked, following pagination. */
    fun listPickedItems(sessionId: String, maxItems: Int = MAX_ITEMS): List<PickedMediaItem> {
        val token = auth.accessToken() ?: throw GoogleApiException("not_signed_in")
        val items = ArrayList<PickedMediaItem>()
        var pageToken: String? = null
        do {
            val url = StringBuilder("$baseUrl/mediaItems?sessionId=").append(sessionId)
                .append("&pageSize=").append(PAGE_SIZE)
            if (pageToken != null) {
                url.append("&pageToken=").append(pageToken)
            }
            val result = Http.getJson(url.toString(), token)
            if (!result.isSuccess) {
                throw GoogleApiException("list_media_failed (${result.code}) ${result.body.take(300)}")
            }
            items += GoogleParsers.parseMediaItems(result.body)
            pageToken = GoogleParsers.nextPageToken(result.body)
        } while (pageToken != null && items.size < maxItems)
        return items.take(maxItems)
    }

    /** Releases the session; failures are not fatal for the import. */
    fun deleteSession(sessionId: String) {
        val token = auth.accessToken() ?: return
        Http.delete("$baseUrl/sessions/$sessionId", token)
    }

    companion object {
        const val PAGE_SIZE = 100

        /** Hard cap so a huge selection cannot fill up the TV's storage. */
        const val MAX_ITEMS = 500

        /** Width/height requested from Google; large enough for 1080p and 4K TVs. */
        const val IMAGE_SIZE = "w2560-h1440"
    }
}

package com.michalkulik.photogallery.google

import org.json.JSONArray
import org.json.JSONObject

/** Response of the OAuth 2.0 device authorization endpoint. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/** A successful token response (device flow or refresh). */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Int,
)

/** A Google Photos Picker session. */
data class PickerSession(
    val id: String,
    val pickerUri: String,
    val mediaItemsSet: Boolean,
    val pollIntervalMillis: Long,
)

/** One media item picked by the user in Google Photos. */
data class PickedMediaItem(
    val id: String,
    val baseUrl: String,
    val mimeType: String,
    val filename: String,
    val isVideo: Boolean,
)

/** Fraction-friendly parser for every Google payload the app consumes. */
object GoogleParsers {

    fun parseDeviceCode(json: String): DeviceCode {
        val root = JSONObject(json)
        return DeviceCode(
            deviceCode = root.getString("device_code"),
            userCode = root.getString("user_code"),
            verificationUrl = root.optString("verification_url", "https://www.google.com/device"),
            expiresInSeconds = root.optInt("expires_in", 1800),
            intervalSeconds = root.optInt("interval", 5).coerceAtLeast(1),
        )
    }

    /** Returns the error code of a token endpoint response, or null when the call succeeded. */
    fun tokenError(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return "invalid_response"
        if (!root.has("error")) return null
        return root.optString("error").ifBlank { root.optString("error_description", "unknown_error") }
    }

    fun parseToken(json: String): TokenResponse {
        val root = JSONObject(json)
        return TokenResponse(
            accessToken = root.getString("access_token"),
            refreshToken = root.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInSeconds = root.optInt("expires_in", 3600),
        )
    }

    fun parseSession(json: String): PickerSession {
        val root = JSONObject(json)
        val polling = root.optJSONObject("pollingConfig")
        val interval = polling?.optString("pollInterval")?.toDurationMillisOrNull() ?: DEFAULT_POLL_MS
        return PickerSession(
            id = root.getString("id"),
            pickerUri = root.optString("pickerUri"),
            mediaItemsSet = root.optBoolean("mediaItemsSet", false),
            pollIntervalMillis = interval.coerceAtLeast(1000L),
        )
    }

    fun parseMediaItems(json: String): List<PickedMediaItem> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val array: JSONArray = root.optJSONArray("mediaItems") ?: return emptyList()
        val items = ArrayList<PickedMediaItem>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val file = item.optJSONObject("mediaFile") ?: continue
            val baseUrl = file.optString("baseUrl")
            if (baseUrl.isBlank()) continue
            val id = item.optString("id").ifBlank { baseUrl.hashCode().toString() }
            val type = item.optString("type").ifBlank { file.optString("mimeType") }
            items += PickedMediaItem(
                id = id,
                baseUrl = baseUrl,
                mimeType = file.optString("mimeType"),
                filename = file.optString("filename"),
                isVideo = type.contains("VIDEO", ignoreCase = true) ||
                    file.optString("mimeType").startsWith("video/"),
            )
        }
        return items
    }

    fun nextPageToken(json: String): String? =
        runCatching { JSONObject(json).optString("nextPageToken") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** Converts Google's protobuf-style durations such as `5s` or `1.500s` to milliseconds. */
    private fun String.toDurationMillisOrNull(): Long? {
        val trimmed = trim()
        if (trimmed.isEmpty() || !trimmed.endsWith("s")) return null
        return trimmed.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
    }

    const val DEFAULT_POLL_MS = 5000L
}

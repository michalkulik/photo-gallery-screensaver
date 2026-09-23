package com.michalkulik.photogallery.google

import com.michalkulik.photogallery.BuildConfig
import com.michalkulik.photogallery.core.Settings
import com.michalkulik.photogallery.util.Http
import com.michalkulik.photogallery.util.Logs
import kotlinx.coroutines.delay

/**
 * Google OAuth 2.0 for TVs and limited-input devices.
 *
 * The TV shows a short user code; the user types it on google.com/device. This avoids needing a
 * browser on the TV and gives the app a refresh token so the screensaver keeps working.
 */
class GoogleAuth(private val settings: Settings) {

    fun hasCredentials(): Boolean = settings.clientId != null && settings.clientSecret != null

    fun isSignedIn(): Boolean = settings.isSignedIn()

    /**
     * Stores new OAuth credentials and drops any existing tokens: a refresh token is bound to the
     * client that requested it, so keeping it after the credentials change only leads to
     * confusing `invalid_grant` failures later.
     */
    fun setCredentials(clientId: String, clientSecret: String) {
        val changed = settings.clientId != clientId || settings.clientSecret != clientSecret
        settings.clientId = clientId
        settings.clientSecret = clientSecret
        if (changed) {
            settings.clearTokens()
        }
    }

    /** Step 1: ask Google for a user code to display on screen. */
    fun requestDeviceCode(): DeviceCode {
        val clientId = requireNotNull(settings.clientId) { "OAuth Client ID is not configured" }
        val result = Http.postForm(
            DEVICE_CODE_ENDPOINT,
            mapOf(
                "client_id" to clientId,
                "scope" to BuildConfig.PHOTOS_SCOPE,
            ),
        )
        if (!result.isSuccess) {
            throw GoogleApiException("device_code_request_failed (${result.code}) ${result.body.take(300)}")
        }
        return GoogleParsers.parseDeviceCode(result.body)
    }

    /**
     * Step 2: poll until the user approves on their phone.
     * Returns null when the code expired instead of throwing, so the UI can offer a retry.
     */
    suspend fun awaitAuthorization(code: DeviceCode): TokenResponse? {
        val clientId = requireNotNull(settings.clientId) { "OAuth Client ID is not configured" }
        val clientSecret = requireNotNull(settings.clientSecret) { "OAuth Client Secret is not configured" }
        var intervalMs = code.intervalSeconds * 1000L
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val result = Http.postForm(
                TOKEN_ENDPOINT,
                mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "device_code" to code.deviceCode,
                    "grant_type" to DEVICE_GRANT_TYPE,
                ),
            )
            if (result.isSuccess) {
                val token = GoogleParsers.parseToken(result.body)
                store(token)
                return token
            }
            when (GoogleParsers.tokenError(result.body)) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs += 5000L
                "access_denied" -> throw GoogleApiException("access_denied")
                "expired_token" -> return null
                else -> throw GoogleApiException("token_error: ${result.body.take(300)}")
            }
        }
        return null
    }

    /** Returns a usable access token, refreshing it when it is close to expiring. */
    fun accessToken(): String? {
        val current = settings.accessToken
        val expiresAt = settings.tokenExpiryMillis
        if (current != null && System.currentTimeMillis() < expiresAt - 60_000L) {
            return current
        }
        return refreshAccessToken()
    }

    private fun refreshAccessToken(): String? {
        val clientId = settings.clientId ?: return null
        val clientSecret = settings.clientSecret ?: return null
        val refreshToken = settings.refreshToken ?: return null
        val result = Http.postForm(
            TOKEN_ENDPOINT,
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            ),
        )
        if (!result.isSuccess) {
            Logs.w("Token refresh failed (${result.code}): ${result.body.take(300)}")
            // An invalid_grant means the user revoked access; force a fresh sign-in.
            if (GoogleParsers.tokenError(result.body) == "invalid_grant") {
                settings.clearTokens()
            }
            return null
        }
        val token = GoogleParsers.parseToken(result.body)
        store(token)
        return token.accessToken
    }

    private fun store(token: TokenResponse) {
        settings.accessToken = token.accessToken
        if (token.refreshToken != null) {
            settings.refreshToken = token.refreshToken
        }
        settings.tokenExpiryMillis = System.currentTimeMillis() + token.expiresInSeconds * 1000L
    }

    fun signOut() = settings.clearTokens()

    private companion object {
        const val DEVICE_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    }
}

/** Google client ids always carry this suffix; used only to warn about obvious typos. */
private const val CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"

/** Cheap sanity check so a mistyped client id is caught before an OAuth round trip. */
fun looksLikeGoogleClientId(value: String): Boolean =
    value.endsWith(CLIENT_ID_SUFFIX) && value.length > CLIENT_ID_SUFFIX.length

/** Raised for OAuth/Picker failures that should be shown to the user. */
class GoogleApiException(message: String) : Exception(message)

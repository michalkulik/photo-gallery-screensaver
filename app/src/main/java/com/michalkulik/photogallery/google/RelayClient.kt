package com.michalkulik.photogallery.google

import com.michalkulik.photogallery.BuildConfig
import com.michalkulik.photogallery.util.Http
import org.json.JSONObject

/**
 * Talks to the OAuth relay (see the `server/` directory).
 *
 * The relay exists because Google's device flow does not allow the Photos Picker scope and a TV
 * has no browser: the app opens a normal web page on the user's phone, and then collects the
 * resulting tokens from the relay. The client secret never reaches the device.
 */
class RelayClient(private val baseUrl: String = BuildConfig.RELAY_BASE_URL) {

    /** A freshly created sign-in session: [id] is secret, [authUrl] goes into the QR code. */
    data class Session(val id: String, val authUrl: String, val pollIntervalSeconds: Int)

    /** Outcome of one poll of a sign-in session. */
    sealed interface Poll {
        data object Pending : Poll
        data class Ready(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Int) : Poll
        data class Failed(val message: String) : Poll
        data object Expired : Poll
    }

    fun createSession(): Session {
        val result = Http.postJson("$baseUrl/api/session", "{}")
        if (!result.isSuccess) {
            throw GoogleApiException("relay_unavailable (${result.code}) ${result.body.take(200)}")
        }
        val json = JSONObject(result.body)
        return Session(
            id = json.getString("id"),
            authUrl = json.getString("auth_url"),
            pollIntervalSeconds = json.optInt("poll_interval_seconds", 3).coerceAtLeast(1),
        )
    }

    fun poll(sessionId: String): Poll {
        val result = Http.getJson("$baseUrl/api/session/$sessionId")
        if (result.code == 404) return Poll.Expired
        if (!result.isSuccess) {
            throw GoogleApiException("relay_poll_failed (${result.code}) ${result.body.take(200)}")
        }
        val json = JSONObject(result.body)
        return when (json.optString("status")) {
            "ready" -> Poll.Ready(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresInSeconds = json.optInt("expires_in", 3600),
            )
            "error" -> Poll.Failed(json.optString("error", "unknown"))
            "expired" -> Poll.Expired
            else -> Poll.Pending
        }
    }

    /** Releases the session once the tokens are safely stored; failures are harmless. */
    fun deleteSession(sessionId: String) {
        Http.delete("$baseUrl/api/session/$sessionId")
    }

    /**
     * Trades a refresh token for a new access token. Returns null when the token is no longer
     * valid, which tells the caller to sign in again.
     */
    fun refresh(refreshToken: String): TokenResponse? {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        val result = Http.postJson("$baseUrl/api/refresh", body)
        if (!result.isSuccess) {
            if (result.body.contains("invalid_grant")) return null
            throw GoogleApiException("relay_refresh_failed (${result.code}) ${result.body.take(200)}")
        }
        val json = JSONObject(result.body)
        return TokenResponse(
            accessToken = json.getString("access_token"),
            refreshToken = null,
            expiresInSeconds = json.optInt("expires_in", 3600),
        )
    }
}

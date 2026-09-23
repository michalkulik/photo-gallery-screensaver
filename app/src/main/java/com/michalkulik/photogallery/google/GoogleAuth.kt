package com.michalkulik.photogallery.google

import com.michalkulik.photogallery.core.Settings
import com.michalkulik.photogallery.util.Logs
import kotlinx.coroutines.delay

/**
 * Google sign-in for a TV that has no browser.
 *
 * Google's device flow (the "type a code on your phone" flow) only allows a small set of scopes
 * and does not include the Photos Picker scope, and Google refuses OAuth inside a WebView. The app
 * therefore hands the OAuth dance to a small relay service: the TV shows a QR code, the user signs
 * in on their phone through a normal browser redirect, and the TV then collects the tokens.
 *
 * Only the refresh token is kept on the device. The client secret stays on the relay, which also
 * performs the token refreshes, so the app never ships a secret.
 */
class GoogleAuth(private val settings: Settings, private val relay: RelayClient) {

    fun isSignedIn(): Boolean = settings.isSignedIn()

    /** Creates a sign-in session; [RelayClient.Session.authUrl] is what the user opens. */
    fun beginSignIn(): RelayClient.Session = relay.createSession()

    /**
     * Polls the relay until the user finishes signing in on their phone.
     * Returns false when the session expired instead of throwing, so the UI can offer a retry.
     */
    suspend fun awaitSignIn(session: RelayClient.Session): Boolean {
        val deadline = System.currentTimeMillis() + SIGN_IN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(session.pollIntervalSeconds * 1000L)
            when (val poll = relay.poll(session.id)) {
                is RelayClient.Poll.Ready -> {
                    store(poll.accessToken, poll.refreshToken, poll.expiresInSeconds)
                    // The tokens are safe on the device now, so the relay can forget them.
                    runCatching { relay.deleteSession(session.id) }
                    return true
                }
                is RelayClient.Poll.Failed -> throw GoogleApiException(poll.message)
                RelayClient.Poll.Expired -> return false
                RelayClient.Poll.Pending -> Unit
            }
        }
        return false
    }

    fun cancelSignIn(session: RelayClient.Session) {
        runCatching { relay.deleteSession(session.id) }
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
        val refreshToken = settings.refreshToken ?: return null
        return try {
            val token = relay.refresh(refreshToken)
            if (token == null) {
                // invalid_grant: the user revoked access, so a fresh sign-in is required.
                Logs.w("Refresh token rejected, signing out")
                settings.clearTokens()
                null
            } else {
                store(token.accessToken, null, token.expiresInSeconds)
                token.accessToken
            }
        } catch (error: Exception) {
            // A network hiccup must not throw away a perfectly good refresh token.
            Logs.w("Token refresh failed", error)
            null
        }
    }

    private fun store(accessToken: String, refreshToken: String?, expiresInSeconds: Int) {
        settings.accessToken = accessToken
        if (refreshToken != null) {
            settings.refreshToken = refreshToken
        }
        settings.tokenExpiryMillis = System.currentTimeMillis() + expiresInSeconds * 1000L
    }

    fun signOut() = settings.clearTokens()

    private companion object {
        /** A little longer than the relay's own session lifetime. */
        const val SIGN_IN_TIMEOUT_MS = 16 * 60 * 1000L
    }
}

/** Raised for OAuth/Picker failures that should be shown to the user. */
class GoogleApiException(message: String) : Exception(message)

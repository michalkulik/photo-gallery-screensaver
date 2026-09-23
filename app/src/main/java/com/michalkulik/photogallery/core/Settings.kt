package com.michalkulik.photogallery.core

import android.content.Context
import android.content.SharedPreferences
import com.michalkulik.photogallery.dream.FitMode
import com.michalkulik.photogallery.dream.PlayOrder
import com.michalkulik.photogallery.dream.SlideshowSettings
import com.michalkulik.photogallery.dream.Transition
import com.michalkulik.photogallery.syno.SynoConfig

/**
 * Single place that owns every persisted value: the slideshow settings, the list of photo
 * sources and the Google OAuth credentials/tokens.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Slideshow -------------------------------------------------------------------------

    var intervalSeconds: Int
        get() = SlideshowSettings.clampInterval(
            prefs.getInt(KEY_INTERVAL, SlideshowSettings.DEFAULT_INTERVAL_SECONDS),
        )
        set(value) = prefs.edit().putInt(KEY_INTERVAL, SlideshowSettings.clampInterval(value)).apply()

    var order: PlayOrder
        get() = SlideshowSettings.parseOrder(prefs.getString(KEY_ORDER, null))
        set(value) = prefs.edit().putString(KEY_ORDER, value.name).apply()

    var transition: Transition
        get() = SlideshowSettings.parseTransition(prefs.getString(KEY_TRANSITION, null))
        set(value) = prefs.edit().putString(KEY_TRANSITION, value.name).apply()

    var kenBurns: Boolean
        get() = prefs.getBoolean(KEY_KEN_BURNS, true)
        set(value) = prefs.edit().putBoolean(KEY_KEN_BURNS, value).apply()

    var fit: FitMode
        get() = SlideshowSettings.parseFit(prefs.getString(KEY_FIT, null))
        set(value) = prefs.edit().putString(KEY_FIT, value.name).apply()

    var showClock: Boolean
        get() = prefs.getBoolean(KEY_CLOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOCK, value).apply()

    var dim: Float
        get() = SlideshowSettings.clampDim(prefs.getFloat(KEY_DIM, SlideshowSettings.DEFAULT_DIM))
        set(value) = prefs.edit().putFloat(KEY_DIM, SlideshowSettings.clampDim(value)).apply()

    fun slideshowSettings(): SlideshowSettings = SlideshowSettings(
        intervalSeconds = intervalSeconds,
        order = order,
        transition = transition,
        kenBurns = kenBurns,
        fit = fit,
        showClock = showClock,
        dim = dim,
    )

    // --- Sources ---------------------------------------------------------------------------

    var sourcesJson: String?
        get() = prefs.getString(KEY_SOURCES, null)
        set(value) = prefs.edit().putString(KEY_SOURCES, value).apply()

    var activeSourceId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    // --- Synology DiskStation -----------------------------------------------------------------

    var synoHost: String?
        get() = prefs.getString(KEY_SYNO_HOST, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_SYNO_HOST, value).apply()

    var synoPort: Int
        get() = prefs.getInt(KEY_SYNO_PORT, SynoConfig.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_SYNO_PORT, value).apply()

    var synoSecure: Boolean
        get() = prefs.getBoolean(KEY_SYNO_SECURE, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNO_SECURE, value).apply()

    var synoIgnoreCertificate: Boolean
        get() = prefs.getBoolean(KEY_SYNO_INSECURE_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_SYNO_INSECURE_TLS, value).apply()

    var synoAccount: String?
        get() = prefs.getString(KEY_SYNO_ACCOUNT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_SYNO_ACCOUNT, value).apply()

    var synoPassword: String?
        get() = prefs.getString(KEY_SYNO_PASSWORD, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_SYNO_PASSWORD, value).apply()

    /**
     * Synology's "remember this device" token.
     *
     * Replaying it on later sign-ins skips the one-time password, so the code only has to be
     * typed once on the TV. Cleared whenever the address or account changes, because the NAS
     * ties the token to that pair.
     */
    var synoDeviceId: String?
        get() = prefs.getString(KEY_SYNO_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_SYNO_DEVICE_ID, value).apply()

    /**
     * The album list from the last successful connect.
     *
     * Remembered so the albums are still selectable after leaving the setup screen, instead of
     * requiring another sign-in just to pick one.
     */
    var synoAlbumsJson: String?
        get() = prefs.getString(KEY_SYNO_ALBUMS, null)
        set(value) = prefs.edit().putString(KEY_SYNO_ALBUMS, value).apply()

    /** The stored NAS settings, or null when the user has not configured one yet. */
    fun synoConfig(): SynoConfig? {
        val host = synoHost ?: return null
        val account = synoAccount ?: return null
        val password = synoPassword ?: return null
        return SynoConfig(
            host = host,
            port = synoPort,
            secure = synoSecure,
            account = account,
            password = password,
            ignoreCertificate = synoIgnoreCertificate,
        )
    }

    fun clearSynoCredentials() {
        prefs.edit()
            .remove(KEY_SYNO_ACCOUNT)
            .remove(KEY_SYNO_PASSWORD)
            .remove(KEY_SYNO_DEVICE_ID)
            .apply()
    }

    /** Drops the remembered device token; used when the address or account changes. */
    fun clearSynoDeviceToken() = prefs.edit().remove(KEY_SYNO_DEVICE_ID).apply()

    /**
     * Forgets everything derived from a successful connect.
     *
     * Called when the address or account changes: the albums and the remembered device both
     * belong to the server that issued them.
     */
    fun clearSynoDerivedState() {
        prefs.edit()
            .remove(KEY_SYNO_DEVICE_ID)
            .remove(KEY_SYNO_ALBUMS)
            .apply()
    }

    // --- Google OAuth ----------------------------------------------------------------------
    // Only tokens are stored here; the OAuth client secret stays on the relay service.

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var tokenExpiryMillis: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .apply()
    }

    fun isSignedIn(): Boolean = refreshToken != null

    private companion object {
        const val PREFS_NAME = "photo_screensaver_settings"

        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_ORDER = "order"
        const val KEY_TRANSITION = "transition"
        const val KEY_KEN_BURNS = "ken_burns"
        const val KEY_FIT = "fit"
        const val KEY_CLOCK = "show_clock"
        const val KEY_DIM = "dim"

        const val KEY_SOURCES = "sources_json"
        const val KEY_ACTIVE = "active_source_id"

        const val KEY_ACCESS_TOKEN = "google_access_token"

        const val KEY_SYNO_HOST = "syno_host"
        const val KEY_SYNO_PORT = "syno_port"
        const val KEY_SYNO_SECURE = "syno_secure"
        const val KEY_SYNO_INSECURE_TLS = "syno_ignore_certificate"
        const val KEY_SYNO_ACCOUNT = "syno_account"
        const val KEY_SYNO_PASSWORD = "syno_password"
        const val KEY_SYNO_DEVICE_ID = "syno_device_id"
        const val KEY_SYNO_ALBUMS = "syno_albums_json"
        const val KEY_REFRESH_TOKEN = "google_refresh_token"
        const val KEY_TOKEN_EXPIRY = "google_token_expiry"
    }
}

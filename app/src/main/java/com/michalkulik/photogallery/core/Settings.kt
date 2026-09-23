package com.michalkulik.photogallery.core

import android.content.Context
import android.content.SharedPreferences
import com.michalkulik.photogallery.dream.FitMode
import com.michalkulik.photogallery.dream.PlayOrder
import com.michalkulik.photogallery.dream.SlideshowSettings
import com.michalkulik.photogallery.dream.Transition

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

    // --- Google OAuth ----------------------------------------------------------------------

    var clientId: String?
        get() = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value).apply()

    var clientSecret: String?
        get() = prefs.getString(KEY_CLIENT_SECRET, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_CLIENT_SECRET, value).apply()

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

        const val KEY_CLIENT_ID = "google_client_id"
        const val KEY_CLIENT_SECRET = "google_client_secret"
        const val KEY_ACCESS_TOKEN = "google_access_token"
        const val KEY_REFRESH_TOKEN = "google_refresh_token"
        const val KEY_TOKEN_EXPIRY = "google_token_expiry"
    }
}

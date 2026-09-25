package com.michalkulik.photogallery.data

import android.content.Context
import com.michalkulik.photogallery.core.Settings
import com.michalkulik.photogallery.syno.SynoAlbum
import com.michalkulik.photogallery.syno.SynoClient
import com.michalkulik.photogallery.syno.SynoConfig
import com.michalkulik.photogallery.syno.SynoException
import com.michalkulik.photogallery.syno.SynoLoginPlan
import com.michalkulik.photogallery.syno.SynoSession
import com.michalkulik.photogallery.util.Logs
import java.io.File

/**
 * Facade over the configured [PhotoSource]s and the photos they expose.
 * The UI works against this; the dream service uses [activePhotos] only.
 */
class PhotoRepository(
    private val context: Context,
    private val settings: Settings,
    val cache: PhotoCache,
    private val syno: SynoClient,
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
        // Only Google sources own cached bytes; Synology photos stay on the NAS.
        if (source != null && source.kind == SourceKind.GOOGLE) {
            cache.delete(source.ref)
        }
        if (settings.activeSourceId == sourceId) {
            settings.activeSourceId = sources().firstOrNull()?.id
        }
    }

    fun setActive(sourceId: String?) {
        if (settings.activeSourceId != sourceId) {
            // The record of what has been shown belongs to the previous source; its ids mean
            // nothing in another one, and keeping them would suppress photos for no reason.
            settings.playedPhotoIds = emptySet()
        }
        settings.activeSourceId = sourceId
    }

    fun activeSource(): PhotoSource? {
        val all = sources()
        return all.firstOrNull { it.id == settings.activeSourceId } ?: all.firstOrNull()
    }

    fun photosFor(source: PhotoSource): List<Photo> = when (source.kind) {
        SourceKind.LOCAL -> LocalMedia.photos(context, source.ref)
        SourceKind.GOOGLE -> cache.photos(source.ref)
        SourceKind.SYNO -> synoPhotos(source)
    }

    /**
     * Photo count for list screens.
     *
     * Synology sources are counted when the album is chosen rather than on every redraw, because
     * counting them means a round trip to the NAS.
     */
    fun countForDisplay(source: PhotoSource): Int = when (source.kind) {
        SourceKind.SYNO -> source.photoCount
        else -> runCatching { photosFor(source).size }.getOrDefault(source.photoCount)
    }

    /** Photos the screensaver should play right now. */
    fun activePhotos(): List<Photo> = activeSource()?.let { photosFor(it) }.orEmpty()

    /**
     * Recomputes and persists the photo count of a source.
     *
     * @return true when the stored count actually changed, so callers can avoid redrawing for
     *   nothing.
     */
    fun refreshCount(sourceId: String): Boolean {
        val all = sources()
        val source = all.firstOrNull { it.id == sourceId } ?: return false
        val count = when (source.kind) {
            SourceKind.SYNO -> synoPhotoCount(source)
            else -> photosFor(source).size
        }
        if (source.photoCount == count) return false
        save(all.map { if (it.id == sourceId) it.copy(photoCount = count) else it })
        return true
    }

    // --- Synology ---------------------------------------------------------------------------

    /**
     * Albums on the configured NAS, including a synthetic "all photos" entry.
     *
     * No code is passed on purpose: [synoTestConnection] has already signed in and cached the
     * session, and a one-time password cannot be used twice.
     */
    fun synoAlbums(): List<SynoAlbum> {
        val config = settings.synoConfig() ?: throw SynoException("nas_not_configured")
        return withSession(config) { syno.albums(config, it) }
    }

    /**
     * Verifies the NAS address and credentials; used by the setup screen.
     *
     * @param otpCode the one-time password, needed the first time for an account with 2FA.
     * @throws SynoTwoFactorRequired when the account uses 2FA and the code has not been given yet.
     */
    fun synoTestConnection(otpCode: String? = null): String {
        val config = settings.synoConfig() ?: throw SynoException("nas_not_configured")
        // This is the screen where the user (re)enters credentials, so always sign in afresh.
        invalidateSynoSession()
        // Routed through sessionFor so the resulting session is cached. A one-time password is
        // single-use, so whatever runs next - the album listing - must reuse this session
        // instead of signing in again and being asked for another code.
        sessionFor(config, forceLogin = true, otpCode = otpCode)
        return config.account
    }

    /** True when a device token is stored, meaning no code is needed on the next sign-in. */
    fun synoHasDeviceToken(): Boolean = settings.synoDeviceId != null

    fun synoForgetDevice() {
        invalidateSynoSession()
        settings.clearSynoDeviceToken()
    }

    fun synoSignOut() {
        val config = settings.synoConfig()
        val session = synoSession
        if (config != null && session != null) {
            syno.logout(config, session)
        }
        invalidateSynoSession()
        settings.clearSynoCredentials()
    }

    /** Drops the cached session without touching the stored credentials. */
    fun synoSignOutQuietly() {
        invalidateSynoSession()
        // The remembered device is tied to the address/account pair, so a change invalidates it.
        settings.clearSynoDeviceToken()
    }

    /**
     * Reads the current items of a Synology album.
     *
     * Deliberately a live call: this is what makes photos added on the NAS appear in the
     * screensaver without any re-import step.
     */
    private fun synoPhotos(source: PhotoSource): List<Photo> {
        val config = settings.synoConfig() ?: return emptyList()
        val albumId = source.ref.toIntOrNull() ?: SynoClient.ALL_PHOTOS_ID
        return try {
            withSession(config) { session ->
                syno.items(config, session, albumId).map { item ->
                    Photo(
                        id = item.id.toString(),
                        uri = syno.imageUrl(config, session, item),
                        sourceId = source.id,
                        dateAdded = item.timeSeconds,
                        cacheKey = "syno-${source.ref}-${item.id}",
                        allowInsecureTls = config.ignoreCertificate,
                        // Thumbnails are preferred for their size; full-size downloads cover a
                        // NAS whose thumbnail API behaves differently.
                        fallbackUris = syno.fallbackImageUrls(config, session, item),
                    )
                }
            }
        } catch (error: Exception) {
            Logs.w("Cannot read Synology album ${source.ref}", error)
            emptyList()
        }
    }

    private fun synoPhotoCount(source: PhotoSource): Int = synoPhotos(source).size

    /** Runs [body] with a valid session, retrying once if the NAS reports an expired one. */
    private fun <T> withSession(
        config: SynoConfig,
        otpCode: String? = null,
        body: (SynoSession) -> T,
    ): T {
        val session = sessionFor(config, otpCode = otpCode)
        return try {
            body(session)
        } catch (error: SynoException) {
            // 119/120 mean the session went away; one silent re-login keeps the screensaver alive.
            if (!error.message.orEmpty().contains("session")) throw error
            Logs.w("Synology session expired, signing in again")
            body(sessionFor(config, forceLogin = true, otpCode = otpCode))
        }
    }

    @Volatile
    private var synoSession: SynoSession? = null

    @Volatile
    private var synoSessionKey: String? = null

    private val synoSessionLock = Any()

    private fun sessionFor(
        config: SynoConfig,
        forceLogin: Boolean = false,
        otpCode: String? = null,
    ): SynoSession = synchronized(synoSessionLock) {
        val key = SynoLoginPlan.sessionKey(config.baseUrl, config.account, config.password)
        // A one-time password is consumed by the sign-in that uses it, so an existing session
        // must always be reused - even when the caller still has a code in hand. Logging in
        // again with the same code gets it rejected and looks like a wrong password.
        if (SynoLoginPlan.canReuseSession(synoSessionKey, key, forceLogin)) {
            synoSession?.let { return it }
        }
        val session = syno.login(
            config = config,
            otpCode = otpCode,
            // Replay the remembered device so an account with 2FA does not ask for a code again.
            deviceId = if (otpCode.isNullOrBlank()) settings.synoDeviceId else null,
            deviceName = SynoClient.DEVICE_NAME,
        )
        rememberDeviceToken(session)
        synoSession = session
        synoSessionKey = key
        session
    }

    /** Stores the device token the NAS issued, so the next sign-in needs no code. */
    private fun rememberDeviceToken(session: SynoSession) {
        session.deviceId?.let { settings.synoDeviceId = it }
    }

    private fun invalidateSynoSession() {
        synchronized(synoSessionLock) {
            synoSession = null
            synoSessionKey = null
        }
    }

    companion object {
        fun newSourceId(kind: SourceKind, ref: String): String = "${kind.name.lowercase()}:$ref"

        fun newCacheRef(): String = "album_" + System.currentTimeMillis().toString(36)

        fun cacheRoot(context: Context): File = File(context.filesDir, "photos")
    }
}

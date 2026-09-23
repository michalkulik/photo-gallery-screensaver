package com.michalkulik.photogallery.ui

import android.widget.LinearLayout
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.data.PhotoRepository
import com.michalkulik.photogallery.data.PhotoSource
import com.michalkulik.photogallery.data.SourceKind
import com.michalkulik.photogallery.syno.SynoAlbum
import com.michalkulik.photogallery.syno.SynoClient
import com.michalkulik.photogallery.syno.SynoException
import com.michalkulik.photogallery.syno.SynoTwoFactorRequired
import com.michalkulik.photogallery.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects the app to a Synology DiskStation.
 *
 * Sources created here are *live*: the photo list is re-read from the NAS every time the
 * screensaver starts, so pictures added to an album appear on their own. Nothing is copied to
 * the TV apart from a small image cache.
 */
class SynoPhotosActivity : TvActivity() {

    override val screenTitle: String get() = getString(R.string.syno_title)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var albums: List<SynoAlbum> = emptyList()

    override fun buildContent(container: LinearLayout) {
        val settings = graph.settings

        TvUi.body(container, getString(R.string.syno_intro))

        // --- Address ---------------------------------------------------------------------
        TvUi.section(container, getString(R.string.syno_server))
        TvUi.row(
            container,
            getString(R.string.syno_host),
            subtitle = settings.synoHost ?: MISSING,
            onClick = {
                Dialogs.input(this, getString(R.string.syno_host), settings.synoHost.orEmpty()) { value ->
                    settings.synoHost = value.trim()
                    afterConfigChange()
                }
            },
        )
        TvUi.row(
            container,
            getString(R.string.syno_port),
            subtitle = settings.synoPort.toString(),
            onClick = {
                Dialogs.input(this, getString(R.string.syno_port), settings.synoPort.toString()) { value ->
                    value.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let { settings.synoPort = it }
                    afterConfigChange()
                }
            },
        )
        TvUi.row(
            container,
            getString(R.string.syno_https),
            subtitle = if (settings.synoSecure) getString(R.string.on) else getString(R.string.off),
            trailing = if (settings.synoSecure) CHECK else null,
            onClick = {
                settings.synoSecure = !settings.synoSecure
                // 5001 is the HTTPS port, 5000 the plain one; follow the toggle.
                settings.synoPort = if (settings.synoSecure) {
                    SynoConfig_HTTPS_PORT
                } else {
                    SynoConfig_HTTP_PORT
                }
                afterConfigChange()
            },
        )
        TvUi.row(
            container,
            getString(R.string.syno_ignore_certificate),
            subtitle = if (settings.synoIgnoreCertificate) getString(R.string.on) else getString(R.string.off),
            trailing = if (settings.synoIgnoreCertificate) CHECK else null,
            onClick = {
                settings.synoIgnoreCertificate = !settings.synoIgnoreCertificate
                afterConfigChange()
            },
        )

        // --- Credentials -----------------------------------------------------------------
        TvUi.section(container, getString(R.string.syno_account))
        TvUi.row(
            container,
            getString(R.string.syno_account),
            subtitle = settings.synoAccount ?: MISSING,
            onClick = {
                Dialogs.input(this, getString(R.string.syno_account), settings.synoAccount.orEmpty()) { value ->
                    settings.synoAccount = value.trim()
                    afterConfigChange()
                }
            },
        )
        TvUi.row(
            container,
            getString(R.string.syno_password),
            subtitle = if (settings.synoPassword != null) MASKED else MISSING,
            onClick = {
                Dialogs.input(
                    this,
                    getString(R.string.syno_password),
                    settings.synoPassword.orEmpty(),
                    secret = true,
                    onResult = { value ->
                        settings.synoPassword = value
                        afterConfigChange()
                    },
                    trim = false,
                )
            },
        )

        TvUi.row(
            container,
            getString(R.string.syno_test),
            subtitle = if (settings.synoConfig() != null) null else getString(R.string.syno_incomplete),
        ) { testConnection() }

        // --- Two-factor ------------------------------------------------------------------
        if (graph.repository.synoHasDeviceToken()) {
            TvUi.row(
                container,
                getString(R.string.syno_device_remembered),
                trailing = CHECK,
            ) { forgetDevice() }
        }

        // --- Albums ----------------------------------------------------------------------
        if (albums.isNotEmpty()) {
            TvUi.section(container, getString(R.string.syno_albums))
            albums.forEach { album ->
                val label = if (album.id == SynoClient.ALL_PHOTOS_ID) {
                    getString(R.string.syno_all_photos)
                } else {
                    album.name
                }
                TvUi.row(
                    container,
                    label,
                    subtitle = photoCountText(this, album.itemCount),
                ) { addSource(album) }
            }
        }

        // --- Existing sources ------------------------------------------------------------
        val synoSources = graph.repository.sources().filter { it.kind == SourceKind.SYNO }
        if (synoSources.isNotEmpty()) {
            TvUi.section(container, getString(R.string.main_sources))
            synoSources.forEach { source ->
                TvUi.row(
                    container,
                    source.displayName(this),
                    subtitle = photoCountText(this, source.photoCount),
                    trailing = if (graph.repository.activeSource()?.id == source.id) {
                        getString(R.string.sources_active)
                    } else {
                        null
                    },
                    onClick = {
                        graph.repository.setActive(source.id)
                        rebuild()
                    },
                )
            }
        }
    }

    /** Any change to the address or credentials invalidates the album list and the session. */
    private fun afterConfigChange() {
        albums = emptyList()
        graph.repository.synoSignOutQuietly()
        rebuild()
    }

    private fun testConnection(otpCode: String? = null) {
        if (graph.settings.synoConfig() == null) {
            Dialogs.message(this, getString(R.string.syno_title), getString(R.string.syno_incomplete))
            return
        }
        val sheet = Sheet(this, getString(R.string.syno_test))
        sheet.setMessage(getString(R.string.syno_connecting))
        sheet.showIndeterminate()
        sheet.show()
        scope.launch {
            try {
                val account = withContext(Dispatchers.IO) { graph.repository.synoTestConnection(otpCode) }
                // No code here: the sign-in above already consumed it and cached the session.
                val list = withContext(Dispatchers.IO) { graph.repository.synoAlbums() }
                albums = list
                sheet.dismiss()
                Dialogs.message(
                    this@SynoPhotosActivity,
                    getString(R.string.syno_title),
                    getString(R.string.syno_connected, account, list.size - 1),
                )
                rebuild()
            } catch (needsCode: SynoTwoFactorRequired) {
                sheet.dismiss()
                askForOneTimeCode()
            } catch (error: Exception) {
                sheet.dismiss()
                Logs.e("Synology connection failed", error)
                Dialogs.message(
                    this@SynoPhotosActivity,
                    getString(R.string.syno_test),
                    describeSynoError(error),
                )
            }
        }
    }

    /**
     * Asks for the TOTP code. The code is only needed once: on success the NAS issues a device
     * token, which is stored so later sign-ins skip this prompt entirely.
     */
    private fun askForOneTimeCode() {
        Dialogs.input(
            this,
            getString(R.string.syno_otp_title),
            "",
            onResult = { code ->
                if (code.isNotBlank()) {
                    testConnection(code)
                }
            },
        )
    }

    /** Drops the remembered device token so a code is requested again. */
    private fun forgetDevice() {
        graph.repository.synoForgetDevice()
        albums = emptyList()
        Dialogs.message(this, getString(R.string.syno_title), getString(R.string.syno_device_forgotten))
        rebuild()
    }

    private fun addSource(album: SynoAlbum) {
        val ref = album.id.toString()
        val name = if (album.id == SynoClient.ALL_PHOTOS_ID) {
            getString(R.string.syno_all_photos)
        } else {
            album.name
        }
        graph.repository.addOrUpdate(
            PhotoSource(
                id = PhotoRepository.newSourceId(SourceKind.SYNO, ref),
                kind = SourceKind.SYNO,
                name = name,
                ref = ref,
                photoCount = album.itemCount,
            ),
        )
        Dialogs.message(this, getString(R.string.syno_title), getString(R.string.syno_added, name))
        rebuild()
    }

    /** Turns a [SynoException] into something the user can act on. */
    private fun describeSynoError(error: Throwable): String {
        val message = error.message.orEmpty()
        val known = listOf(
            "wrong_account_or_password" to R.string.syno_err_credentials,
            "ip_blocked" to R.string.syno_err_ip_blocked,
            "two_factor" to R.string.syno_err_2fa,
            "account_disabled" to R.string.syno_err_disabled,
            "permission_denied" to R.string.syno_err_permission,
            "nas_not_configured" to R.string.syno_incomplete,
            "nas_did_not_provide_a_public_key" to R.string.syno_err_not_a_nas,
        )
        known.firstOrNull { message.contains(it.first) }?.let { return getString(it.second) }
        return getString(R.string.syno_err_generic, message.ifBlank { error.javaClass.simpleName })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val MISSING = "\u2014"
        const val MASKED = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
        const val CHECK = "\u2713"
    }
}

/** Default ports, kept next to the screen that switches between them. */
private const val SynoConfig_HTTPS_PORT = 5001
private const val SynoConfig_HTTP_PORT = 5000

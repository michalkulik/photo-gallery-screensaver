package com.michalkulik.photogallery.ui

import android.widget.LinearLayout
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.data.PhotoRepository
import com.michalkulik.photogallery.data.PhotoSource
import com.michalkulik.photogallery.data.SourceKind
import com.michalkulik.photogallery.google.looksLikeGoogleClientId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Google Photos on a TV without a browser:
 * 1. the user pastes an OAuth client (type "TVs and Limited Input devices"),
 * 2. signs in with a code typed on a phone (device flow),
 * 3. picks albums/photos in Google Photos through the Picker API,
 * 4. the selection is downloaded to the device for offline playback.
 */
class GooglePhotosActivity : TvActivity() {

    override val screenTitle: String get() = getString(R.string.google_title)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun buildContent(container: LinearLayout) {
        val settings = graph.settings
        val auth = graph.auth

        TvUi.body(container, getString(R.string.google_intro))

        TvUi.section(container, getString(R.string.google_client_id))
        TvUi.body(container, getString(R.string.google_credentials_help))
        TvUi.row(
            container,
            getString(R.string.google_client_id),
            subtitle = settings.clientId?.let { truncate(it) } ?: MISSING,
            onClick = {
                Dialogs.input(this, getString(R.string.google_client_id), settings.clientId.orEmpty()) { entered ->
                    val value = entered.trim()
                    graph.auth.setCredentials(value, settings.clientSecret.orEmpty())
                    if (value.isNotEmpty() && !looksLikeGoogleClientId(value)) {
                        Dialogs.message(
                            this,
                            getString(R.string.google_client_id),
                            getString(R.string.google_client_id_invalid),
                        )
                    }
                    rebuild()
                }
            },
        )
        TvUi.row(
            container,
            getString(R.string.google_client_secret),
            subtitle = if (settings.clientSecret != null) MASKED else MISSING,
            onClick = {
                Dialogs.input(this, getString(R.string.google_client_secret), settings.clientSecret.orEmpty(), secret = true) { entered ->
                    graph.auth.setCredentials(settings.clientId.orEmpty(), entered.trim())
                    rebuild()
                }
            },
        )

        TvUi.section(container, getString(R.string.main_google))
        if (auth.isSignedIn()) {
            TvUi.row(container, getString(R.string.google_signed_in), trailing = "\u2713")
            TvUi.row(container, getString(R.string.google_pick)) { startPicker() }
            TvUi.row(container, getString(R.string.google_sign_out)) {
                auth.signOut()
                rebuild()
            }
        } else {
            TvUi.row(container, getString(R.string.google_sign_in)) { startSignIn() }
        }

        val googleSources = graph.repository.sources().filter { it.kind == SourceKind.GOOGLE }
        if (googleSources.isNotEmpty()) {
            TvUi.section(container, getString(R.string.main_sources))
            googleSources.forEach { source ->
                TvUi.row(
                    container,
                    source.displayName(this),
                    subtitle = photoCountText(this, source.photoCount),
                    trailing = if (graph.repository.activeSource()?.id == source.id) getString(R.string.sources_active) else null,
                    onClick = {
                        graph.repository.setActive(source.id)
                        rebuild()
                    },
                )
            }
        }
    }

    // --- Sign in (OAuth 2.0 device flow) ---------------------------------------------------

    private fun startSignIn() {
        val auth = graph.auth
        if (!auth.hasCredentials()) {
            Dialogs.message(this, getString(R.string.google_sign_in), getString(R.string.google_client_required))
            return
        }
        val sheet = Sheet(this, getString(R.string.google_sign_in))
        sheet.setMessage(getString(R.string.google_waiting))
        sheet.showIndeterminate()
        sheet.show()
        scope.launch {
            try {
                val code = withContext(Dispatchers.IO) { auth.requestDeviceCode() }
                sheet.setCode(code.userCode)
                sheet.setMessage(getString(R.string.google_device_hint, code.verificationUrl))
                sheet.setImage(withContext(Dispatchers.IO) { QrCode.bitmap(code.verificationUrl, QR_SIZE) })
                val token = auth.awaitAuthorization(code)
                sheet.dismiss()
                if (token == null) {
                    Dialogs.message(
                        this@GooglePhotosActivity,
                        getString(R.string.google_sign_in),
                        getString(R.string.google_code_expired),
                    )
                }
                rebuild()
            } catch (error: Exception) {
                sheet.dismiss()
                Dialogs.message(
                    this@GooglePhotosActivity,
                    getString(R.string.google_sign_in),
                    getString(R.string.google_auth_failed, error.message ?: "?"),
                )
            }
        }
    }

    // --- Pick and import photos ------------------------------------------------------------

    private fun startPicker() {
        if (!graph.auth.isSignedIn()) {
            Dialogs.message(this, getString(R.string.google_pick), getString(R.string.google_need_login))
            return
        }
        val sheet = Sheet(this, getString(R.string.google_pick))
        sheet.setMessage(getString(R.string.google_pick_hint))
        sheet.showIndeterminate()
        sheet.show()
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) { graph.importer.beginSession() }
                sheet.setMessage(getString(R.string.google_pick_hint) + "\n\n" + session.pickerUri)
                sheet.setImage(withContext(Dispatchers.IO) { QrCode.bitmap(session.pickerUri, QR_SIZE) })

                val ready = graph.importer.awaitSelection(session) {
                    sheet.setMessage(getString(R.string.google_picker_waiting))
                }
                if (!ready) {
                    sheet.dismiss()
                    Dialogs.message(
                        this@GooglePhotosActivity,
                        getString(R.string.google_pick),
                        getString(R.string.google_picker_expired),
                    )
                    return@launch
                }

                val ref = PhotoRepository.newCacheRef()
                val saved = withContext(Dispatchers.IO) {
                    graph.importer.importSelection(session.id, ref) { done, total ->
                        runOnUiThread {
                            sheet.setMessage(getString(R.string.google_importing, done, total))
                            sheet.showProgress(done, total)
                        }
                    }
                }
                sheet.dismiss()
                if (saved == 0) {
                    Dialogs.message(
                        this@GooglePhotosActivity,
                        getString(R.string.google_pick),
                        getString(R.string.google_import_none),
                    )
                } else {
                    graph.repository.addOrUpdate(
                        PhotoSource(
                            id = PhotoRepository.newSourceId(SourceKind.GOOGLE, ref),
                            kind = SourceKind.GOOGLE,
                            name = googleSourceName(),
                            ref = ref,
                            photoCount = saved,
                        ),
                    )
                    Dialogs.message(
                        this@GooglePhotosActivity,
                        getString(R.string.app_name),
                        importedPhotosText(this@GooglePhotosActivity, saved),
                    )
                }
                rebuild()
            } catch (error: Exception) {
                sheet.dismiss()
                Dialogs.message(
                    this@GooglePhotosActivity,
                    getString(R.string.google_pick),
                    getString(R.string.google_import_failed, error.message ?: "?"),
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun truncate(value: String): String =
        if (value.length <= 24) value else value.take(12) + "\u2026" + value.takeLast(6)

    private companion object {
        const val QR_SIZE = 520
        const val MISSING = "\u2014"
        const val MASKED = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
    }
}

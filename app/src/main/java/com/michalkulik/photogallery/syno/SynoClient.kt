package com.michalkulik.photogallery.syno

import com.michalkulik.photogallery.util.Http
import com.michalkulik.photogallery.util.HttpResult
import com.michalkulik.photogallery.util.Logs
import java.net.URLEncoder

/**
 * Client for the Synology Photos WebAPI.
 *
 * Built against the API surface the DiskStation advertises through `SYNO.API.Info` (a public
 * discovery endpoint), so it stays valid across DSM versions and does not depend on any
 * Synology code.
 *
 * All calls are blocking and must run off the main thread.
 */
class SynoClient {

    /**
     * Logs in and records which API versions this NAS supports.
     *
     * Handles the two-factor case: when the account uses a one-time password and neither a
     * [otpCode] nor a remembered [deviceId] is supplied, the NAS answers 403 and this throws
     * [SynoTwoFactorRequired] so the caller can ask for the code.
     *
     * Passing a [deviceId] that the NAS still accepts skips the code entirely, which is what
     * makes a TV usable: the code is typed once and remembered afterwards.
     */
    fun login(
        config: SynoConfig,
        otpCode: String? = null,
        deviceId: String? = null,
        deviceName: String? = null,
    ): SynoSession {
        // DSM expects the password in the clear when HTTPS already protects it, and only wants
        // the RSA-wrapped form on plain HTTP. Sending the wrapped form over HTTPS does not work:
        // DSM compares the blob literally and answers 400, which is indistinguishable from a
        // genuinely wrong password. Try the transport-appropriate form first and fall back to
        // the other one, so this works whichever way a given DSM build behaves.
        val plain = config.password
        val wrapped = runCatching { SynoCrypto.encryptPassword(fetchPublicKey(config), plain) }
            .onFailure { Logs.w("Cannot wrap the password with the NAS public key", it) }
            .getOrNull()

        val attempts = SynoLoginPlan.passwordForms(config.secure).mapNotNull { form ->
            when (form) {
                SynoLoginPlan.PasswordForm.PLAIN -> plain
                SynoLoginPlan.PasswordForm.WRAPPED -> wrapped
            }
        }

        var failure: String? = null
        attempts.forEachIndexed { index, passwd ->
            val result = attemptLogin(config, passwd, otpCode, deviceId, deviceName)
            val errorCode = SynoParsers.errorCode(result.body)
            // Logged without the password: which form was tried is what matters for diagnosis.
            Logs.d(
                "Synology login attempt ${index + 1}/${attempts.size} " +
                    "wrapped=${passwd !== plain} account=${config.account} " +
                    "passwordLength=${plain.length} otp=${!otpCode.isNullOrBlank()} " +
                    "deviceId=${!deviceId.isNullOrBlank()} httpCode=${result.code} errorCode=$errorCode",
            )

            if (!result.isSuccess) {
                failure = "login_failed (${result.code}) ${result.body.take(200)}"
                return@forEachIndexed
            }

            // 403 is the NAS asking for the one-time password, not a real failure.
            if (SynoParsers.requiresTwoFactor(errorCode)) {
                throw SynoTwoFactorRequired()
            }

            if (errorCode == null) {
                val data = SynoParsers.envelope(result.body)
                val session = SynoSession(
                    sid = SynoParsers.parseSession(data),
                    apiVersions = fetchApiVersions(config),
                    deviceId = SynoParsers.parseDeviceId(data),
                )
                // Keys only, never values: this is how we find out whether the NAS actually
                // hands back a device token, which the screensaver needs to sign in unattended.
                Logs.d(
                    "Synology login ok: dataKeys=${data.keys().asSequence().toList()} " +
                        "deviceToken=${session.deviceId != null}",
                )
                return session
            }

            failure = SynoParsers.describeError(errorCode) + " (code $errorCode)"
            // Only a rejected password justifies trying the other form; anything else would
            // just burn another sign-in attempt against Auto Block.
            if (!SynoLoginPlan.shouldRetryWithOtherForm(errorCode)) return@forEachIndexed
        }

        throw SynoException(failure ?: "login_failed")
    }

    /** One sign-in request with the given `passwd` value. */
    private fun attemptLogin(
        config: SynoConfig,
        passwd: String,
        otpCode: String?,
        deviceId: String?,
        deviceName: String?,
    ): HttpResult {
        val params = LinkedHashMap<String, String>()
        params["account"] = config.account
        params["passwd"] = passwd
        params["session"] = SESSION
        params["format"] = "sid"
        // Present in the reference client; DSM accepts sign-ins without them but including them
        // keeps this request shaped like the one Synology's own clients send.
        params["logintype"] = "local"
        params["client"] = "browser"
        params["enable_syno_token"] = "yes"
        if (!otpCode.isNullOrBlank()) {
            params["otp_code"] = otpCode.trim()
        }
        if (!deviceId.isNullOrBlank()) {
            params["device_id"] = deviceId.trim()
        }
        // Ask the NAS to hand back a device token so later sign-ins need no code. Only worth
        // requesting when a code was actually used, otherwise there is nothing to skip.
        if (!otpCode.isNullOrBlank() && !deviceName.isNullOrBlank()) {
            params["enable_device_token"] = "yes"
            params["device_name"] = deviceName
        }

        return Http.getJson(
            url(config, "SYNO.API.Auth", 7, "login", params),
            insecure = config.ignoreCertificate,
        )
    }

    fun logout(config: SynoConfig, session: SynoSession) {
        runCatching {
            Http.getJson(
                url(config, "SYNO.API.Auth", 7, "logout", mapOf("session" to SESSION, "_sid" to session.sid)),
                insecure = config.ignoreCertificate,
            )
        }
    }

    /**
     * Lists the user's albums.
     *
     * The synthetic entry with id 0 represents "all photos" and is added here so the UI does not
     * have to special-case it; it is served by [items] through the timeline API.
     */
    fun albums(config: SynoConfig, session: SynoSession): List<SynoAlbum> {
        val data = call(
            config, session, "SYNO.Foto.Browse.Album", 5, "list",
            mapOf("offset" to "0", "limit" to ALBUM_LIMIT.toString()),
        )
        val albums = SynoParsers.parseAlbums(data)
        val total = data.optInt("total", albums.sumOf { it.itemCount })
        return listOf(SynoAlbum(id = ALL_PHOTOS_ID, name = "", itemCount = total)) + albums
    }

    /**
     * Lists photos, newest first.
     *
     * @param albumId an album id from [albums], or [ALL_PHOTOS_ID] for the whole library.
     * @param limit   how many items to expose to the slideshow.
     */
    fun items(
        config: SynoConfig,
        session: SynoSession,
        albumId: Int,
        limit: Int = DEFAULT_LIMIT,
    ): List<SynoItem> {
        val params = LinkedHashMap<String, String>()
        params["offset"] = "0"
        params["limit"] = limit.toString()
        params["sort_by"] = "takentime"
        params["sort_direction"] = "desc"
        // Without "additional" the response carries no thumbnail cache key, and every
        // later download of the image would be rejected.
        params["additional"] = """["thumbnail","filename"]"""

        return if (albumId == ALL_PHOTOS_ID) {
            SynoParsers.parseItems(
                call(config, session, "SYNO.Foto.Browse.Timeline", 5, "list", params),
            )
        } else {
            params["album_id"] = albumId.toString()
            SynoParsers.parseItems(
                call(config, session, "SYNO.Foto.Browse.Item", 6, "list", params),
            )
        }
    }

    /**
     * Builds the URL that serves one image.
     *
     * Synology has no dedicated thumbnail API: `SYNO.Foto.Download` takes a size and returns the
     * bytes, so the screensaver asks for a TV-sized image rather than the original.
     */
    fun imageUrl(
        config: SynoConfig,
        session: SynoSession,
        item: SynoItem,
        size: String = SIZE_TV,
    ): String {
        val params = LinkedHashMap<String, String>()
        params["unit_id"] = "[${item.id}]"
        params["item_id"] = "[${item.id}]"
        params["size"] = size
        params["type"] = if (item.isVideo) "thumb" else "unit"
        item.cacheKey?.let { params["cache_key"] = it }
        params["_sid"] = session.sid
        return url(config, "SYNO.Foto.Download", 2, "download", params)
    }

    /** Quick reachability probe used by the setup screen. */
    fun ping(config: SynoConfig): Boolean =
        runCatching {
            Http.getJson(
                "${base(config)}/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth",
                insecure = config.ignoreCertificate,
            ).isSuccess
        }.getOrDefault(false)

    // --- Internals -----------------------------------------------------------------------

    private fun call(
        config: SynoConfig,
        session: SynoSession,
        api: String,
        version: Int,
        method: String,
        params: Map<String, String>,
    ) = SynoParsers.envelope(
        run {
            val withSid: Map<String, String> = params + mapOf("_sid" to session.sid)
            val result = Http.getJson(
                url(config, api, version, method, withSid),
                insecure = config.ignoreCertificate,
            )
            if (!result.isSuccess) {
                throw SynoException("$api.$method failed (${result.code}) ${result.body.take(200)}")
            }
            result.body
        },
    )

    private fun fetchPublicKey(config: SynoConfig): String {
        val result = Http.getJson(
            url(config, "SYNO.API.Encryption", 1, "getinfo", emptyMap()),
            insecure = config.ignoreCertificate,
        )
        if (!result.isSuccess) {
            throw SynoException("encryption_info_failed (${result.code})")
        }
        return SynoParsers.parseEncryption(SynoParsers.envelope(result.body))
            ?: throw SynoException("nas_did_not_provide_a_public_key")
    }

    private fun fetchApiVersions(config: SynoConfig): Map<String, Int> = runCatching {
        val result = Http.getJson(
            "${base(config)}/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=all",
            insecure = config.ignoreCertificate,
        )
        if (!result.isSuccess) return@runCatching emptyMap()
        SynoParsers.parseApiInfo(SynoParsers.envelope(result.body))
    }.onFailure { Logs.w("Cannot read the NAS API list", it) }.getOrDefault(emptyMap())

    private fun base(config: SynoConfig) = config.baseUrl

    private fun url(
        config: SynoConfig,
        api: String,
        version: Int,
        method: String,
        params: Map<String, String>,
    ): String {
        val query = buildString {
            append("api=").append(api)
            append("&version=").append(version)
            append("&method=").append(method)
            params.forEach { (key, value) ->
                append('&').append(encode(key)).append('=').append(encode(value))
            }
        }
        return "${base(config)}/webapi/entry.cgi?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Session name Synology Photos itself uses. */
        const val SESSION = "Photos"

        /** Album id standing for "the whole library"; served through the timeline API. */
        const val ALL_PHOTOS_ID = 0

        /** How many photos the screensaver will cycle through. */
        const val DEFAULT_LIMIT = 500

        private const val ALBUM_LIMIT = 200

        /**
         * Image size requested for the TV. `xl` is the largest pre-generated preview, which is
         * plenty for 1080p and far cheaper than re-encoding the original.
         */
        const val SIZE_TV = "xl"

        /**
         * Name the NAS shows in its "remembered devices" list. Lets the user revoke this TV's
         * access later without affecting other clients.
         */
        const val DEVICE_NAME = "Photo Gallery Screensaver (TV)"
    }
}

package com.michalkulik.photogallery.syno

import com.michalkulik.photogallery.util.Http
import com.michalkulik.photogallery.util.HttpResult
import com.michalkulik.photogallery.util.Logs
import org.json.JSONObject
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
        // Deliberately NOT sending enable_syno_token. Asking for it makes DSM demand an
        // X-SYNO-TOKEN header on every later call - including the image downloads, which are
        // plain URLs - and without it every request fails with 119 "session expired". The token
        // is CSRF protection for browser sessions; this is a direct API client.
        params["logintype"] = "local"
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
        // The album listing's own `total` counts albums, so the real photo count is asked for
        // separately - otherwise the "all photos" entry would show a meaningless number.
        val libraryCount = runCatching { itemCount(config, session, ALL_PHOTOS_ID) }
            .onFailure { Logs.w("Cannot read the Synology library size", it) }
            .getOrDefault(0)
        return listOf(SynoAlbum(id = ALL_PHOTOS_ID, name = "", itemCount = libraryCount)) + albums
    }

    /**
     * Lists photos, newest first, following pagination.
     *
     * DSM returns one page per request, so a single call only ever exposes the first few hundred
     * items. Paging is what makes a library of thousands usable.
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
        var page = 0
        val result = SynoPaging.collect(limit) { offset, size ->
            val data = listPage(config, session, albumId, offset, size)
            page++
            val items = SynoParsers.parseItems(data)
            val total = data.optInt("total", -1)
            Logs.d(
                "Synology items page $page: offset=$offset requested=$size got=${items.size} total=$total",
            )
            SynoPaging.Page(items = items, total = total)
        }
        Logs.d("Synology listing for album $albumId: ${result.size} items over $page page(s)")
        return result
    }

    /**
     * How many items a source holds, according to the NAS.
     *
     * Asked for separately because the album listing's `total` describes albums, not photos, so
     * the "all photos" entry would otherwise show a meaningless number.
     */
    fun itemCount(config: SynoConfig, session: SynoSession, albumId: Int): Int =
        // A full page rather than 1: DSM rejects a limit below its minimum with 103, so asking
        // for a single item would fail outright.
        listPage(config, session, albumId, offset = 0, limit = SynoPaging.PAGE_SIZE).optInt("total", 0)

    /**
     * One page of a listing.
     *
     * Everything goes through `SYNO.Foto.Browse.Item`:
     * an album is selected with the `id` parameter, and leaving it out lists the whole library.
     * `SYNO.Foto.Browse.Timeline` is not used here on purpose - its only method is `get`, which
     * returns date sections rather than photos.
     */
    private fun listPage(
        config: SynoConfig,
        session: SynoSession,
        albumId: Int,
        offset: Int,
        limit: Int,
    ): JSONObject {
        val params = LinkedHashMap<String, String>()
        params["offset"] = offset.toString()
        params["limit"] = limit.toString()
        params["sort_by"] = "takentime"
        params["sort_direction"] = "desc"
        // Only values DSM accepts here. An unknown name is rejected with 120, which reads as
        // "session expired" but actually names the offending parameter in the body.
        params["additional"] = """["thumbnail"]"""
        if (albumId != ALL_PHOTOS_ID) {
            params["id"] = albumId.toString()
        }
        return call(config, session, "SYNO.Foto.Browse.Item", 6, "list", params)
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
    ) = try {
        SynoParsers.envelope(
            run {
                val withSid: Map<String, String> = params + mapOf("_sid" to session.sid)
                val requestUrl = url(config, api, version, method, withSid)
                val result = Http.getJson(requestUrl, insecure = config.ignoreCertificate)
                if (!result.isSuccess) {
                    throw SynoException("$api.$method failed (${result.code}) ${result.body.take(200)}")
                }
                // Logged without the session id, which is a credential.
                if (SynoParsers.errorCode(result.body) != null) {
                    Logs.w(
                        "Synology $api.$method rejected: ${result.body.take(300)} " +
                            "params=${params.keys.joinToString(",")}",
                    )
                }
                result.body
            },
        )
    } catch (error: SynoException) {
        // Naming the API matters: a bare error code does not say which call failed.
        throw SynoException("$api.$method: ${error.message}")
    }

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

        /**
         * How many photos the screensaver will cycle through.
         *
         * Generous on purpose: this is only a listing, and each image is fetched lazily while
         * the slideshow runs, so a large library costs nothing until it is actually shown.
         */
        const val DEFAULT_LIMIT = 5000

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

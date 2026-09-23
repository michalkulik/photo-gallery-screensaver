package com.michalkulik.photogallery.syno

import android.util.Base64
import com.michalkulik.photogallery.util.Http
import com.michalkulik.photogallery.util.Logs
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.net.URLEncoder
import javax.crypto.Cipher

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

    /** Logs in and records which API versions this NAS supports. */
    fun login(config: SynoConfig): SynoSession {
        val publicKey = fetchPublicKey(config)
        val encrypted = encryptPassword(publicKey, config.password)

        val login = Http.getJson(
            url(
                config,
                "SYNO.API.Auth",
                7,
                "login",
                mapOf(
                    "account" to config.account,
                    "passwd" to encrypted,
                    "session" to SESSION,
                    "format" to "sid",
                ),
            ),
            insecure = config.ignoreCertificate,
        )
        if (!login.isSuccess) {
            throw SynoException("login_failed (${login.code}) ${login.body.take(200)}")
        }
        val sid = SynoParsers.parseSession(SynoParsers.envelope(login.body))

        return SynoSession(sid = sid, apiVersions = fetchApiVersions(config))
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

    /**
     * Encrypts the password with the NAS public key.
     *
     * Sending it in the clear would be a downgrade the NAS explicitly supports but the app should
     * never use; RSA with PKCS#1 padding is what the WebAPI expects for `passwd`.
     */
    private fun encryptPassword(publicKeyBase64: String, password: String): String {
        val keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

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
    }
}

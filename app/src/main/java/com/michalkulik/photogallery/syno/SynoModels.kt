package com.michalkulik.photogallery.syno

import org.json.JSONObject

/** Credentials plus the address of a DiskStation, as entered by the user on the TV. */
data class SynoConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val secure: Boolean = true,
    val account: String,
    val password: String,
    val ignoreCertificate: Boolean = false,
) {
    /** `https://nas:5001`, or `https://nas.example.com` when the port is the default one. */
    val baseUrl: String
        get() {
            val scheme = if (secure) "https" else "http"
            val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            // A reverse proxy on the standard port is the common case for a public address, and
            // "https://host:443" looks wrong even though it works.
            val defaultPort = if (secure) HTTPS_PORT else HTTP_PORT
            return if (port == defaultPort) "$scheme://$cleanHost" else "$scheme://$cleanHost:$port"
        }

    companion object {
        const val DEFAULT_PORT = 5001
        const val DEFAULT_PORT_PLAIN = 5000
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80
    }
}

/** One album in Synology Photos. [id] is 0 for the synthetic "all photos" entry. */
data class SynoAlbum(val id: Int, val name: String, val itemCount: Int, val isShared: Boolean = false)

/** One photo returned by the browse APIs. */
data class SynoItem(
    val id: Int,
    val filename: String,
    val timeSeconds: Long,
    val isVideo: Boolean,
    /**
     * True when the photo lives in the shared space.
     *
     * The two spaces have separate download APIs and neither serves the other's photos: asking
     * the wrong one answers error 117, which the slideshow can only report as "no photos". An
     * album in the personal space can still hold shared photos, so this cannot be inferred from
     * the album.
     */
    val sharedSpace: Boolean,
) {
    /**
     * The `SYNO.Foto.Download` cache key. Synology requires the caller to pass back the
     * `cache_key` it returned with the item, otherwise the request is rejected.
     */
    var cacheKey: String? = null
}

/**
 * A logged-in session: the session id plus the APIs this NAS actually supports.
 *
 * [deviceId] is Synology's "remember this device" token. When present it can be replayed on a
 * later sign-in to skip the one-time password, which is what keeps a TV from asking for a fresh
 * TOTP code every time the app restarts.
 */
data class SynoSession(
    val sid: String,
    val apiVersions: Map<String, Int>,
    val deviceId: String? = null,
)

/** Raised for Synology failures that should be shown to the user. */
open class SynoException(message: String) : Exception(message)

/**
 * The account is protected by a one-time password and the code is still needed.
 *
 * A subclass so that existing catch blocks keep working; the setup screen catches this one
 * specifically to ask for the code instead of showing an error.
 */
class SynoTwoFactorRequired : SynoException("two_factor_required")

/**
 * Parsers for the Synology WebAPI payloads.
 *
 * The WebAPI wraps every response in `{"success":true,"data":{...}}` or
 * `{"success":false,"error":{"code":N}}`, so all parsing funnels through [envelope].
 */
object SynoParsers {

    /** Unwraps the WebAPI envelope, turning `success:false` into a [SynoException]. */
    fun envelope(body: String): JSONObject {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw SynoException("invalid_response: ${body.take(200)}")
        if (!root.optBoolean("success", false)) {
            val code = root.optJSONObject("error")?.optInt("code", -1) ?: -1
            throw SynoException("${describeError(code)} (code $code)")
        }
        return root.optJSONObject("data") ?: JSONObject()
    }

    /**
     * The error code of a failed envelope, or null when the response succeeded or is unreadable.
     *
     * Callers need the raw code because 403 does not mean "failed": it is the NAS asking for a
     * one-time password.
     */
    fun errorCode(body: String): Int? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optBoolean("success", false)) return null
        return root.optJSONObject("error")?.optInt("code", -1) ?: -1
    }

    /** True when the code means the account needs a one-time password before signing in. */
    fun requiresTwoFactor(code: Int?): Boolean = code == TWO_FACTOR_REQUIRED_CODE

    private const val TWO_FACTOR_REQUIRED_CODE = 403

    /**
     * Maps Synology's authentication error codes onto something a user can act on.
     *
     * The 40x range is the documented `SYNO.API.Auth` set; 407 in particular means the NAS's
     * Auto Block feature has banned this IP after repeated failed sign-ins.
     */
    fun describeError(code: Int): String = when (code) {
        400 -> "wrong_account_or_password"
        401 -> "account_disabled"
        402 -> "permission_denied"
        403 -> "two_factor_required"
        404 -> "two_factor_code_invalid"
        406 -> "two_factor_enforced"
        407 -> "ip_blocked"
        408 -> "two_factor_code_expired"
        409, 410, 411 -> "two_factor_unsupported"
        119, 120 -> "session_expired"
        106 -> "session_interrupted"
        101 -> "insufficient_permission"
        102, 103 -> "api_not_available"
        105 -> "not_configured"
        else -> "request_failed"
    }

    /** Reads `public_key` (and the cipher placeholders) from `SYNO.API.Encryption`. */
    fun parseEncryption(data: JSONObject): String? =
        data.optString("public_key").takeIf { it.isNotBlank() }

    fun parseSession(data: JSONObject): String =
        data.optString("sid").takeIf { it.isNotBlank() }
            ?: throw SynoException("login_succeeded_without_a_session")

    /**
     * The "remember this device" token, present only when the caller asked for one.
     *
     * DSM 7 names it `device_id`; older builds used `did`, so both are accepted.
     */
    fun parseDeviceId(data: JSONObject): String? =
        data.optString("device_id").takeIf { it.isNotBlank() }
            ?: data.optString("did").takeIf { it.isNotBlank() }

    fun parseAlbums(data: JSONObject): List<SynoAlbum> {
        val array = data.optJSONArray("list") ?: return emptyList()
        val result = ArrayList<SynoAlbum>(array.length())
        for (i in 0 until array.length()) {
            val album = array.optJSONObject(i) ?: continue
            val id = album.optInt("id", 0)
            if (id == 0) continue
            result += SynoAlbum(
                id = id,
                name = album.optString("name").ifBlank { "Album $id" },
                itemCount = album.optInt("item_count", 0),
                isShared = album.optBoolean("shared", false),
            )
        }
        return result
    }

    fun parseItems(data: JSONObject): List<SynoItem> {
        val array = data.optJSONArray("list") ?: return emptyList()
        val result = ArrayList<SynoItem>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", 0)
            if (id == 0) continue
            // The thumbnail block carries both the cache key needed for downloads and the
            // original file name. "filename" is not a valid `additional` value, so the name is
            // read from here rather than requested separately.
            val thumbnail = item.optJSONObject("additional")?.optJSONObject("thumbnail")
            val filename = thumbnail?.optString("original_name").orEmpty()
            result += SynoItem(
                id = id,
                filename = filename.ifBlank { "item-$id" },
                timeSeconds = item.optLong("time", 0L),
                isVideo = item.optInt("type", 0) != 0,
                // Verified against the NAS: shared-space photos report owner 0, personal photos
                // report the owning user's id.
                sharedSpace = item.optInt("owner_user_id", -1) == 0,
            ).apply {
                cacheKey = thumbnail?.optString("cache_key")?.takeIf { it.isNotBlank() }
            }
        }
        return result
    }

    /** Reads `SYNO.API.Info` into a `api name -> maxVersion` map. */
    fun parseApiInfo(data: JSONObject): Map<String, Int> {
        val result = HashMap<String, Int>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val info = data.optJSONObject(key) ?: continue
            result[key] = info.optInt("maxVersion", 1)
        }
        return result
    }
}

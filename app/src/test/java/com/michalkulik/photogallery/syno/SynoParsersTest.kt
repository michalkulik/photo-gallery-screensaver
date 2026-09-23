package com.michalkulik.photogallery.syno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the Synology WebAPI envelope and payload parsing. */
class SynoParsersTest {

    @Test
    fun `unwraps a successful envelope`() {
        val data = SynoParsers.envelope("""{"success":true,"data":{"total":3}}""")

        assertEquals(3, data.optInt("total"))
    }

    @Test
    fun `turns a failure envelope into an actionable message`() {
        val error = runCatching {
            SynoParsers.envelope("""{"success":false,"error":{"code":400}}""")
        }.exceptionOrNull()

        assertTrue(error is SynoException)
        assertTrue(error!!.message!!.contains("wrong_account_or_password"))
    }

    @Test
    fun `reports an auto-blocked address distinctly from bad credentials`() {
        // 407 is the Auto Block response; mixing it up with a wrong password would send the
        // user chasing the wrong problem.
        assertEquals("ip_blocked", SynoParsers.describeError(407))
        assertEquals("wrong_account_or_password", SynoParsers.describeError(400))
        assertEquals("session_expired", SynoParsers.describeError(119))
        assertEquals("two_factor_required", SynoParsers.describeError(403))
    }

    @Test
    fun `detects the one-time-password challenge instead of treating it as a failure`() {
        // A 2FA account answers the first sign-in with 403. Reading that as a plain error is
        // what stopped the setup screen from ever connecting.
        val challenge = """{"error":{"code":403},"success":false}"""

        assertTrue(SynoParsers.requiresTwoFactor(SynoParsers.errorCode(challenge)))
        assertEquals(403, SynoParsers.errorCode(challenge))
    }

    @Test
    fun `does not mistake other failures for the two-factor challenge`() {
        val wrongPassword = """{"error":{"code":400},"success":false}"""
        val blocked = """{"error":{"code":407},"success":false}"""
        val ok = """{"success":true,"data":{"sid":"abc"}}"""

        assertFalse(SynoParsers.requiresTwoFactor(SynoParsers.errorCode(wrongPassword)))
        assertFalse(SynoParsers.requiresTwoFactor(SynoParsers.errorCode(blocked)))
        // A successful response has no error code at all.
        assertNull(SynoParsers.errorCode(ok))
        assertFalse(SynoParsers.requiresTwoFactor(SynoParsers.errorCode(ok)))
    }

    @Test
    fun `reads the remembered device token from a login response`() {
        // DSM 7 returns device_id. Looking only for "did" silently produced a null token, so the
        // screensaver could never sign in unattended and kept asking for a code.
        val json = """
            {"success":true,"data":{"account":"michal","device_id":"device-token-123",
             "ik_message":"x","is_portal_port":false,"sid":"abc","synotoken":"t"}}
        """.trimIndent()

        val data = SynoParsers.envelope(json)

        assertEquals("device-token-123", SynoParsers.parseDeviceId(data))
    }

    @Test
    fun `still accepts the older did field name`() {
        val json = """{"success":true,"data":{"sid":"abc","did":"legacy-token"}}"""

        assertEquals("legacy-token", SynoParsers.parseDeviceId(SynoParsers.envelope(json)))
    }

    @Test
    fun `reports no device token when the NAS did not issue one`() {
        val json = """{"success":true,"data":{"account":"michal","sid":"abc","synotoken":"t"}}"""

        assertNull(SynoParsers.parseDeviceId(SynoParsers.envelope(json)))
    }

    @Test
    fun `tells the shared space from the personal one`() {
        // The two spaces have separate download APIs and neither serves the other's photos, so
        // reading this wrongly makes every photo undecodable. Verified against the NAS: shared
        // items report owner 0, personal items report the owning user's id.
        val json = """
            {"success":true,"data":{"list":[
              {"id":1,"type":"photo","owner_user_id":0},
              {"id":2,"type":"photo","owner_user_id":1},
              {"id":3,"type":"photo"}
            ]}}
        """.trimIndent()

        val items = SynoParsers.parseItems(SynoParsers.envelope(json))

        assertTrue("an owner of 0 means the shared space", items[0].sharedSpace)
        assertFalse("a real owner means the personal space", items[1].sharedSpace)
        // Without the field we cannot tell, so it defaults to the personal space.
        assertFalse(items[2].sharedSpace)
    }

    @Test
    fun `rejects a body that is not json at all`() {
        val error = runCatching { SynoParsers.envelope("<html>403</html>") }.exceptionOrNull()

        assertTrue(error is SynoException)
        assertTrue(error!!.message!!.contains("invalid_response"))
    }

    @Test
    fun `parses albums and skips entries without an id`() {
        val json = """
            {"success":true,"data":{"total":2,"list":[
              {"id":7,"name":"Holidays","item_count":42},
              {"id":0,"name":"broken"},
              {"id":9,"name":"Family","item_count":5,"shared":true}
            ]}}
        """.trimIndent()

        val albums = SynoParsers.parseAlbums(SynoParsers.envelope(json))

        assertEquals(2, albums.size)
        assertEquals(7, albums[0].id)
        assertEquals("Holidays", albums[0].name)
        assertEquals(42, albums[0].itemCount)
        assertFalse(albums[0].isShared)
        assertTrue(albums[1].isShared)
    }

    @Test
    fun `parses items and keeps the thumbnail cache key`() {
        val json = """
            {"success":true,"data":{"list":[
              {"id":11,"type":0,"time":1700000000,
               "additional":{"thumbnail":{"cache_key":"abc123","original_name":"a.jpg"}}},
              {"id":12,"type":1,"time":1700000001,
               "additional":{"thumbnail":{"cache_key":"def456"}}}
            ]}}
        """.trimIndent()

        val items = SynoParsers.parseItems(SynoParsers.envelope(json))

        assertEquals(2, items.size)
        assertEquals(11, items[0].id)
        assertEquals("a.jpg", items[0].filename)
        assertEquals("abc123", items[0].cacheKey)
        assertFalse(items[0].isVideo)
        assertTrue(items[1].isVideo)
        // Without the cache key every later download of this image would be rejected.
        assertEquals("def456", items[1].cacheKey)
    }

    @Test
    fun `falls back to a readable name when the thumbnail has no file name`() {
        val json = """{"success":true,"data":{"list":[{"id":5,"additional":{"thumbnail":{}}}]}}"""

        val items = SynoParsers.parseItems(SynoParsers.envelope(json))

        assertEquals("item-5", items[0].filename)
        assertNull(items[0].cacheKey)
    }

    @Test
    fun `reads api versions from the discovery payload`() {
        val json = """
            {"success":true,"data":{
              "SYNO.API.Auth":{"maxVersion":7,"minVersion":1,"path":"entry.cgi"},
              "SYNO.Foto.Browse.Item":{"maxVersion":6,"minVersion":1,"path":"entry.cgi"}
            }}
        """.trimIndent()

        val versions = SynoParsers.parseApiInfo(SynoParsers.envelope(json))

        assertEquals(7, versions["SYNO.API.Auth"])
        assertEquals(6, versions["SYNO.Foto.Browse.Item"])
    }

    @Test
    fun `extracts the public key needed for password encryption`() {
        val json = """{"success":true,"data":{"public_key":"MIIBIjANBg","server_time":1}}"""

        assertEquals("MIIBIjANBg", SynoParsers.parseEncryption(SynoParsers.envelope(json)))
        assertNull(SynoParsers.parseEncryption(SynoParsers.envelope("""{"success":true,"data":{}}""")))
    }

    @Test
    fun `builds the base url from host and scheme`() {
        val https = SynoConfig(host = "nas.local", account = "a", password = "b")
        assertEquals("https://nas.local:5001", https.baseUrl)

        val plain = SynoConfig(host = "192.168.1.5", port = 5000, secure = false, account = "a", password = "b")
        assertEquals("http://192.168.1.5:5000", plain.baseUrl)
    }

    @Test
    fun `omits the port when it is the default for the scheme`() {
        // A reverse proxy on 443 is how a public address is reached, and "https://host:443" is
        // needlessly ugly.
        val public = SynoConfig(host = "synoscreensaver.mkulik.eu", port = 443, account = "a", password = "b")
        assertEquals("https://synoscreensaver.mkulik.eu", public.baseUrl)

        val plain = SynoConfig(host = "nas.local", port = 80, secure = false, account = "a", password = "b")
        assertEquals("http://nas.local", plain.baseUrl)
    }

    @Test
    fun `keeps a non-default port even when it is unusual`() {
        val proxied = SynoConfig(host = "nas.example.com", port = 8443, account = "a", password = "b")

        assertEquals("https://nas.example.com:8443", proxied.baseUrl)
    }

    @Test
    fun `strips a scheme the user pasted into the address field`() {
        val config = SynoConfig(host = "https://nas.local/", account = "a", password = "b")

        assertEquals("https://nas.local:5001", config.baseUrl)
    }
}

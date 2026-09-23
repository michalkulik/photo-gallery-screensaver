package com.michalkulik.photogallery.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers parsing of the Google OAuth and Photos Picker payloads. */
class GoogleParsersTest {

    @Test
    fun `parses a device code response`() {
        val json = """
            {"device_code":"dev-code","user_code":"ABCD-EFGH",
             "verification_url":"https://www.google.com/device",
             "expires_in":1800,"interval":5}
        """.trimIndent()

        val code = GoogleParsers.parseDeviceCode(json)

        assertEquals("dev-code", code.deviceCode)
        assertEquals("ABCD-EFGH", code.userCode)
        assertEquals("https://www.google.com/device", code.verificationUrl)
        assertEquals(1800, code.expiresInSeconds)
        assertEquals(5, code.intervalSeconds)
    }

    @Test
    fun `falls back to sane defaults when optional fields are missing`() {
        val code = GoogleParsers.parseDeviceCode("""{"device_code":"d","user_code":"U","expires_in":60}""")

        assertEquals("https://www.google.com/device", code.verificationUrl)
        assertEquals(5, code.intervalSeconds)
    }

    @Test
    fun `never polls faster than one second`() {
        val code = GoogleParsers.parseDeviceCode(
            """{"device_code":"d","user_code":"U","expires_in":60,"interval":0}""",
        )

        assertEquals(1, code.intervalSeconds)
    }

    @Test
    fun `reports the error code of a failed token call`() {
        assertEquals("authorization_pending", GoogleParsers.tokenError("""{"error":"authorization_pending"}"""))
        assertEquals("bad", GoogleParsers.tokenError("""{"error":"bad","error_description":"nope"}"""))
        assertNull(GoogleParsers.tokenError("""{"access_token":"at","expires_in":3600}"""))
        assertEquals("invalid_response", GoogleParsers.tokenError("not json"))
    }

    @Test
    fun `parses a token response with and without a refresh token`() {
        val withRefresh = GoogleParsers.parseToken(
            """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}""",
        )
        assertEquals("at", withRefresh.accessToken)
        assertEquals("rt", withRefresh.refreshToken)
        assertEquals(3600, withRefresh.expiresInSeconds)

        val withoutRefresh = GoogleParsers.parseToken("""{"access_token":"at2","expires_in":100}""")
        assertNull(withoutRefresh.refreshToken)
    }

    @Test
    fun `parses a picker session and its polling interval`() {
        val json = """
            {"id":"session-1","pickerUri":"https://photos.google.com/picker/abc",
             "mediaItemsSet":false,"pollingConfig":{"pollInterval":"5s","timeoutIn":"1800s"}}
        """.trimIndent()

        val session = GoogleParsers.parseSession(json)

        assertEquals("session-1", session.id)
        assertEquals("https://photos.google.com/picker/abc", session.pickerUri)
        assertFalse(session.mediaItemsSet)
        assertEquals(5000L, session.pollIntervalMillis)
    }

    @Test
    fun `handles fractional polling intervals and enforces a minimum`() {
        val fractional = GoogleParsers.parseSession(
            """{"id":"s","pickerUri":"u","mediaItemsSet":true,"pollingConfig":{"pollInterval":"1.500s"}}""",
        )
        assertEquals(1500L, fractional.pollIntervalMillis)
        assertTrue(fractional.mediaItemsSet)

        val tiny = GoogleParsers.parseSession(
            """{"id":"s","pickerUri":"u","pollingConfig":{"pollInterval":"0.100s"}}""",
        )
        assertEquals(1000L, tiny.pollIntervalMillis)

        val missing = GoogleParsers.parseSession("""{"id":"s","pickerUri":"u"}""")
        assertEquals(GoogleParsers.DEFAULT_POLL_MS, missing.pollIntervalMillis)
    }

    @Test
    fun `parses picked media items and flags videos`() {
        val json = """
            {"mediaItems":[
              {"id":"m1","createTime":"2024-01-01T00:00:00Z","type":"PHOTO",
               "mediaFile":{"baseUrl":"https://lh3/photo","mimeType":"image/jpeg","filename":"a.jpg"}},
              {"id":"m2","type":"VIDEO",
               "mediaFile":{"baseUrl":"https://lh3/video","mimeType":"video/mp4","filename":"b.mp4"}}
            ]}
        """.trimIndent()

        val items = GoogleParsers.parseMediaItems(json)

        assertEquals(2, items.size)
        assertEquals("m1", items[0].id)
        assertEquals("https://lh3/photo", items[0].baseUrl)
        assertFalse(items[0].isVideo)
        assertTrue(items[1].isVideo)
    }

    @Test
    fun `skips media items without a usable base url`() {
        val json = """
            {"mediaItems":[{"id":"m1","mediaFile":{}},{"id":"m2","mediaFile":{"baseUrl":"https://lh3/ok"}}]}
        """.trimIndent()

        val items = GoogleParsers.parseMediaItems(json)

        assertEquals(1, items.size)
        assertEquals("https://lh3/ok", items[0].baseUrl)
    }

    @Test
    fun `reads the pagination token only when present`() {
        assertEquals("tok", GoogleParsers.nextPageToken("""{"nextPageToken":"tok"}"""))
        assertNull(GoogleParsers.nextPageToken("""{"mediaItems":[]}"""))
        assertNull(GoogleParsers.nextPageToken(""))
    }
}

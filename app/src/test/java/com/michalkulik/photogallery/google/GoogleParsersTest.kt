package com.michalkulik.photogallery.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers parsing of the Google Photos Picker payloads. */
class GoogleParsersTest {

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

package com.michalkulik.photogallery.syno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the image URLs.
 *
 * The two Synology image APIs take different parameter shapes and are easy to confuse:
 * `Thumbnail` wants a plain `id` and a required `type`, while `Download` wants bracketed
 * `unit_id`/`item_id`. Getting it wrong answers error 120, which names the offending parameter
 * but only in the response body.
 */
class SynoImageUrlTest {

    private val config = SynoConfig(host = "nas.local", account = "a", password = "b")
    private val session = SynoSession(sid = "SESSION", apiVersions = emptyMap())

    private fun item(id: Int, shared: Boolean, cacheKey: String? = "key_$id") =
        SynoItem(
            id = id,
            filename = "f$id",
            timeSeconds = 0,
            isVideo = false,
            sharedSpace = shared,
        ).apply { this.cacheKey = cacheKey }

    @Test
    fun `uses the thumbnail api with a plain id and a type`() {
        val url = SynoClient().imageUrl(config, session, item(id = 95611, shared = true))

        assertTrue(url.contains("api=SYNO.FotoTeam.Thumbnail"))
        assertTrue(url.contains("method=get"))
        // A plain id, not the bracketed form Download uses.
        assertTrue("expected a plain id, got $url", url.contains("&id=95611&"))
        assertTrue(url.contains("type=unit"))
        assertTrue(url.contains("size=xl"))
        assertTrue(url.contains("cache_key=key_95611"))
    }

    @Test
    fun `uses the personal thumbnail api for a personal photo`() {
        val url = SynoClient().imageUrl(config, session, item(id = 5, shared = false))

        assertTrue(url.contains("api=SYNO.Foto.Thumbnail"))
        // Not the team one: the two spaces do not serve each other's photos.
        assertFalse(url.contains("api=SYNO.FotoTeam.Thumbnail"))
    }

    @Test
    fun `always sends the cache key the nas requires`() {
        val url = SynoClient().imageUrl(config, session, item(id = 7, shared = false))

        assertTrue(url.contains("cache_key=key_7"))
    }

    @Test
    fun `offers both spaces as download fallbacks`() {
        val urls = SynoClient().fallbackImageUrls(config, session, item(id = 9, shared = true))

        assertEquals(2, urls.size)
        // The item's own space first, then the other one in case the space was read wrongly.
        assertTrue(urls[0].contains("api=SYNO.FotoTeam.Download"))
        assertTrue(urls[1].contains("api=SYNO.Foto.Download"))
        // Download takes the bracketed form.
        assertTrue(urls[0].contains("unit_id=%5B9%5D"))
        assertTrue(urls[0].contains("item_id=%5B9%5D"))
        assertTrue(urls[0].contains("method=download"))
    }

    @Test
    fun `percent-encodes the bracketed ids`() {
        val urls = SynoClient().fallbackImageUrls(config, session, item(id = 12, shared = false))

        // A raw "[" in a query string is not valid and some proxies reject it.
        assertFalse(urls[0].contains("["))
        assertTrue(urls[0].contains("%5B12%5D"))
    }
}

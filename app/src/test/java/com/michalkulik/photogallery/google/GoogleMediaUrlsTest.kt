package com.michalkulik.photogallery.google

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the URL/file-name helpers used while downloading picked photos. */
class GoogleMediaUrlsTest {

    private fun item(baseUrl: String, video: Boolean) = PickedMediaItem(
        id = "id",
        baseUrl = baseUrl,
        mimeType = if (video) "video/mp4" else "image/jpeg",
        filename = "x",
        isVideo = video,
    )

    @Test
    fun `requests a sized image for photos`() {
        assertEquals(
            "https://lh3/photo=w2560-h1440",
            GoogleMediaUrls.downloadUrl(item("https://lh3/photo", video = false)),
        )
    }

    @Test
    fun `requests a thumbnail without overlay for videos`() {
        assertEquals(
            "https://lh3/video=w2560-h1440-no",
            GoogleMediaUrls.downloadUrl(item("https://lh3/video", video = true)),
        )
    }

    @Test
    fun `honours a custom size`() {
        assertEquals(
            "https://lh3/photo=w800-h600",
            GoogleMediaUrls.downloadUrl(item("https://lh3/photo", video = false), size = "w800-h600"),
        )
    }

    @Test
    fun `sanitises media ids into file names`() {
        assertEquals("abc", GoogleMediaUrls.sanitizeId("a/b c!"))
        assertEquals("A-1_2", GoogleMediaUrls.sanitizeId("A-1_2"))
        assertEquals("photo", GoogleMediaUrls.sanitizeId(""))
        assertEquals("photo", GoogleMediaUrls.sanitizeId("///"))
    }
}

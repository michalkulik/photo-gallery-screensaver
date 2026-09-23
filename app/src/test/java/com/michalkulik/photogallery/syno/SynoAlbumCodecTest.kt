package com.michalkulik.photogallery.syno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers remembering the album list.
 *
 * Without this the albums vanished when the setup screen was closed, leaving only the synthetic
 * "all photos" entry and no way to pick a specific album without signing in again.
 */
class SynoAlbumCodecTest {

    @Test
    fun `round-trips the album list`() {
        val albums = listOf(
            SynoAlbum(id = 0, name = "", itemCount = 5000),
            SynoAlbum(id = 7, name = "Holidays", itemCount = 42),
            SynoAlbum(id = 9, name = "Family", itemCount = 5, isShared = true),
        )

        val decoded = SynoAlbumCodec.decode(SynoAlbumCodec.encode(albums))

        assertEquals(albums, decoded)
    }

    @Test
    fun `keeps the synthetic all-photos entry with its id of zero`() {
        val albums = listOf(SynoAlbum(id = 0, name = "", itemCount = 5000))

        val decoded = SynoAlbumCodec.decode(SynoAlbumCodec.encode(albums))

        assertEquals(1, decoded.size)
        assertEquals(0, decoded[0].id)
        assertEquals(5000, decoded[0].itemCount)
    }

    @Test
    fun `handles names with non-ascii characters`() {
        val albums = listOf(SynoAlbum(id = 3, name = "mała marysia", itemCount = 1))

        val decoded = SynoAlbumCodec.decode(SynoAlbumCodec.encode(albums))

        assertEquals("mała marysia", decoded[0].name)
    }

    @Test
    fun `returns nothing for missing or malformed input`() {
        assertTrue(SynoAlbumCodec.decode(null).isEmpty())
        assertTrue(SynoAlbumCodec.decode("").isEmpty())
        assertTrue(SynoAlbumCodec.decode("not json").isEmpty())
    }
}

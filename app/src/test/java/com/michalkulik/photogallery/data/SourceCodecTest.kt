package com.michalkulik.photogallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers persistence of the configured photo sources. */
class SourceCodecTest {

    private val local = PhotoSource("local:ALL", SourceKind.LOCAL, "", "ALL", 12)
    private val google = PhotoSource("google:album_1", SourceKind.GOOGLE, "Holidays 2026", "album_1", 340)

    @Test
    fun `round trips a list of sources`() {
        val decoded = SourceCodec.decode(SourceCodec.encode(listOf(local, google)))

        assertEquals(listOf(local, google), decoded)
    }

    @Test
    fun `treats missing or broken json as an empty list`() {
        assertTrue(SourceCodec.decode(null).isEmpty())
        assertTrue(SourceCodec.decode("").isEmpty())
        assertTrue(SourceCodec.decode("not json").isEmpty())
        assertTrue(SourceCodec.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun `skips entries with an unknown kind or no id`() {
        val json = """
            [
              {"id":"a","kind":"MAGIC","name":"x","ref":"r","count":1},
              {"kind":"LOCAL","name":"no id","ref":"ALL","count":2},
              {"id":"c","kind":"LOCAL","name":"","ref":"ALL","count":3}
            ]
        """.trimIndent()

        val decoded = SourceCodec.decode(json)

        assertEquals(1, decoded.size)
        assertEquals("c", decoded[0].id)
        assertEquals("", decoded[0].name)
    }
}

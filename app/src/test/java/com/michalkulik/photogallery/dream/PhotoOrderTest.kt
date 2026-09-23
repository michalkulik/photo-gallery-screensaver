package com.michalkulik.photogallery.dream

import com.michalkulik.photogallery.data.Photo
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers how the playlist order is derived from the user's preference. */
class PhotoOrderTest {

    private fun photo(id: String, added: Long) = Photo(id = id, uri = "uri://$id", sourceId = "s", dateAdded = added)

    private val photos = listOf(
        photo("b", 200),
        photo("a", 300),
        photo("d", 100),
        photo("c", 100),
    )

    @Test
    fun `sequential order is newest first and stable for equal dates`() {
        val ordered = PhotoOrder.arrange(photos, PlayOrder.SEQUENTIAL, Random(1))

        assertEquals(listOf("a", "b", "c", "d"), ordered.map { it.id })
    }

    @Test
    fun `shuffle keeps every photo exactly once`() {
        val shuffled = PhotoOrder.arrange(photos, PlayOrder.SHUFFLE, Random(7))

        assertEquals(photos.size, shuffled.size)
        assertEquals(photos.map { it.id }.toSet(), shuffled.map { it.id }.toSet())
    }

    @Test
    fun `shuffle is reproducible for a given seed`() {
        val first = PhotoOrder.arrange(photos, PlayOrder.SHUFFLE, Random(42)).map { it.id }
        val second = PhotoOrder.arrange(photos, PlayOrder.SHUFFLE, Random(42)).map { it.id }

        assertEquals(first, second)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(emptyList<Photo>(), PhotoOrder.arrange(emptyList(), PlayOrder.SHUFFLE, Random(1)))
        assertEquals(emptyList<Photo>(), PhotoOrder.arrange(emptyList(), PlayOrder.SEQUENTIAL, Random(1)))
    }
}

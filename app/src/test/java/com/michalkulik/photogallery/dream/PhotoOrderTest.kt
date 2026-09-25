package com.michalkulik.photogallery.dream

import com.michalkulik.photogallery.data.Photo
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `a repeated photo in the input survives the shuffle`() {
        // A shuffle is a permutation: it can neither drop a repeat nor create one. So one photo
        // showing up twice close together means the same photo was listed twice by the source,
        // not that the ordering went wrong - which is why the source list has to be deduplicated.
        val withRepeat = photos + photo("b", 200)

        val shuffled = PhotoOrder.arrange(withRepeat, PlayOrder.SHUFFLE, Random(3))

        assertEquals(5, shuffled.size)
        assertEquals(2, shuffled.count { it.id == "b" })
    }

    @Test
    fun `photos not yet shown come before photos already shown`() {
        // This is what stops the same picture coming round after only a few others when the
        // screensaver restarts: a start continues the pass instead of beginning a new shuffle.
        val ordered = PhotoOrder.arrange(photos, PlayOrder.SHUFFLE, Random(11), seenIds = setOf("a", "c"))

        assertEquals(listOf("b", "d"), ordered.take(2).map { it.id }.sorted())
        assertEquals(listOf("a", "c"), ordered.drop(2).map { it.id }.sorted())
    }

    @Test
    fun `nothing repeats while any photo is still unseen`() {
        // The guarantee that matters to the viewer. Walked over many seeds, because a shuffle
        // that ignores the seen set would only fail on some of them.
        val library = (1..30).map { photo("p$it", it.toLong()) }
        var seen = emptySet<String>()
        repeat(30) { step ->
            val order = PhotoOrder.arrange(library, PlayOrder.SHUFFLE, Random(step), seen)
            val next = order.first()
            assertTrue("photo ${next.id} repeated at step $step", next.id !in seen)
            seen = seen + next.id
        }
        assertEquals(30, seen.size)
    }

    @Test
    fun `a completed pass starts again from the whole library`() {
        // Once everything has been shown the set has no effect, so the next pass is a normal
        // shuffle of the whole library rather than an empty or truncated one.
        val everything = photos.map { it.id }.toSet()

        val ordered = PhotoOrder.arrange(photos, PlayOrder.SHUFFLE, Random(5), seenIds = everything)

        assertEquals(photos.size, ordered.size)
        assertEquals(photos.map { it.id }.toSet(), ordered.map { it.id }.toSet())
    }

    @Test
    fun `a sequential order ignores the seen set`() {
        // The setting asks for a fixed order, so continuing a pass must not reorder it.
        val ordered = PhotoOrder.arrange(
            photos,
            PlayOrder.SEQUENTIAL,
            Random(1),
            seenIds = setOf("a", "b"),
        )

        assertEquals(listOf("a", "b", "c", "d"), ordered.map { it.id })
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(emptyList<Photo>(), PhotoOrder.arrange(emptyList(), PlayOrder.SHUFFLE, Random(1)))
        assertEquals(emptyList<Photo>(), PhotoOrder.arrange(emptyList(), PlayOrder.SEQUENTIAL, Random(1)))
    }
}

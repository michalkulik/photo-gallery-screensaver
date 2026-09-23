package com.michalkulik.photogallery.syno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the listing pagination.
 *
 * The failure this guards against is silent: stopping after the first page makes a library of
 * thousands look like it only holds a few hundred photos.
 */
class SynoPagingTest {

    private fun item(id: Int) = SynoItem(id = id, filename = "f$id", timeSeconds = 0, isVideo = false)

    /** A fake NAS holding [total] photos, returning at most [pageCap] per request. */
    private fun fakeNas(total: Int, pageCap: Int = Int.MAX_VALUE): (Int, Int) -> SynoPaging.Page = { offset, limit ->
        val size = minOf(limit, pageCap, (total - offset).coerceAtLeast(0))
        SynoPaging.Page(
            items = (offset until offset + size).map { item(it) },
            total = total,
        )
    }

    @Test
    fun `walks every page of a large library`() {
        // The bug: a single request only ever returned the first page.
        val nas = fakeNas(total = 5000)

        val items = SynoPaging.collect(limit = 5000, fetch = nas)

        assertEquals(5000, items.size)
        assertEquals(0, items.first().id)
        assertEquals(4999, items.last().id)
    }

    @Test
    fun `keeps paging when the NAS returns fewer items than requested`() {
        // DSM caps a page below what was asked for. Comparing the batch size against the request
        // size would look like the end of the list and truncate everything after page one.
        val nas = fakeNas(total = 1000, pageCap = 50)

        val items = SynoPaging.collect(limit = 1000, pageSize = 200, fetch = nas)

        assertEquals(1000, items.size)
    }

    @Test
    fun `stops at the requested limit instead of the whole library`() {
        val nas = fakeNas(total = 5000)

        val items = SynoPaging.collect(limit = 250, fetch = nas)

        assertEquals(250, items.size)
    }

    @Test
    fun `stops when the library is exhausted`() {
        val nas = fakeNas(total = 120)

        val items = SynoPaging.collect(limit = 5000, pageSize = 200, fetch = nas)

        assertEquals(120, items.size)
    }

    @Test
    fun `handles an empty library`() {
        val nas = fakeNas(total = 0)

        val items = SynoPaging.collect(limit = 5000, fetch = nas)

        assertTrue(items.isEmpty())
    }

    @Test
    fun `does not loop forever when the NAS never reports a total`() {
        // Without a total, the only reliable end signal is an empty page.
        var calls = 0
        val nas: (Int, Int) -> SynoPaging.Page = { offset, limit ->
            calls++
            SynoPaging.Page(items = (offset until offset + limit).map { item(it) }, total = -1)
        }

        val items = SynoPaging.collect(limit = 100_000, pageSize = 200, maxPages = 5, fetch = nas)

        assertEquals(5, calls)
        assertEquals(1000, items.size)
    }

    @Test
    fun `stops immediately on an empty first page`() {
        var calls = 0
        val nas: (Int, Int) -> SynoPaging.Page = { _, _ ->
            calls++
            SynoPaging.Page(items = emptyList(), total = -1)
        }

        val items = SynoPaging.collect(limit = 5000, fetch = nas)

        assertTrue(items.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `requests successive offsets`() {
        val offsets = ArrayList<Int>()
        val nas: (Int, Int) -> SynoPaging.Page = { offset, limit ->
            offsets += offset
            SynoPaging.Page(items = (0 until limit).map { item(offset + it) }, total = 1000)
        }

        SynoPaging.collect(limit = 600, pageSize = 200, fetch = nas)

        assertEquals(listOf(0, 200, 400), offsets)
    }

    @Test
    fun `returns nothing for a non-positive limit`() {
        var calls = 0
        val nas: (Int, Int) -> SynoPaging.Page = { _, _ ->
            calls++
            SynoPaging.Page(emptyList(), 0)
        }

        assertTrue(SynoPaging.collect(limit = 0, fetch = nas).isEmpty())
        assertEquals(0, calls)
    }
}

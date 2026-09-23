package com.michalkulik.photogallery.syno

/**
 * Page walking for Synology listings.
 *
 * DSM returns one page per request and caps the page size server-side, so a single call only ever
 * exposes the first few hundred photos of a library. This walks the pages instead.
 *
 * Kept separate from the client so the loop can be unit tested: getting the stop condition wrong
 * silently truncates the photo list, which looks like "the NAS only has 500 pictures".
 */
object SynoPaging {

    /** Items requested per page. */
    const val PAGE_SIZE = 200

    /** Safety valve so a misbehaving NAS cannot spin the loop forever. */
    const val MAX_PAGES = 100

    /** One page of a listing, plus how many items exist in total when the NAS reports it. */
    data class Page(val items: List<SynoItem>, val total: Int)

    /**
     * Collects up to [limit] items by requesting successive pages.
     *
     * @param fetch returns the page starting at an offset.
     */
    fun collect(
        limit: Int,
        pageSize: Int = PAGE_SIZE,
        maxPages: Int = MAX_PAGES,
        fetch: (offset: Int, limit: Int) -> Page,
    ): List<SynoItem> {
        if (limit <= 0) return emptyList()
        val collected = ArrayList<SynoItem>()
        var offset = 0
        var page = 0
        var total = -1

        while (collected.size < limit && page < maxPages) {
            val result = fetch(offset, minOf(pageSize, limit - collected.size))
            val batch = result.items
            if (result.total > 0) total = result.total
            collected += batch
            page++

            if (batch.isEmpty()) break
            // Compared against the NAS's total rather than the page size: DSM may return fewer
            // items than requested without being at the end of the list, and treating that as
            // the end is exactly what truncates a large library.
            if (total in 1..collected.size) break
            offset += batch.size
        }

        return collected.take(limit)
    }
}

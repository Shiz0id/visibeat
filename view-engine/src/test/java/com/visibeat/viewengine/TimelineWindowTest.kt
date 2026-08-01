package com.visibeat.viewengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bucket window.
 *
 * This existed as `ORDER BY bucketStartEpochMs LIMIT 60` in SQL — ascending —
 * so it kept the sixty *oldest* buckets and the engine reversed them for
 * display. A library with more than sixty distinct buckets simply lost
 * everything recent, and the sort toggle could not get it back. These pin down
 * the property that failed: the window is taken from the end the sort starts at.
 */
class TimelineWindowTest {

    private fun bucketsFrom(vararg starts: Long) =
        starts.map { TimelineBucketRow(it, itemCount = 1, distinctReleaseCount = 1, distinctArtistCount = 1) }

    /** Ten buckets, oldest first, as the DAO returns them. */
    private val ascending = bucketsFrom(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    private fun starts(rows: List<TimelineBucketRow>) = rows.map { it.bucketStartEpochMs }

    // ── the regression ────────────────────────────────────

    @Test
    fun `newest-first keeps the newest buckets, not the oldest`() {
        val window = TimelineWindow.apply(ascending, SortDirection.DESC, limit = 3)
        assertEquals(listOf(10L, 9L, 8L), starts(window))
    }

    @Test
    fun `oldest-first keeps the oldest buckets`() {
        val window = TimelineWindow.apply(ascending, SortDirection.ASC, limit = 3)
        assertEquals(listOf(1L, 2L, 3L), starts(window))
    }

    @Test
    fun `the two sort directions cover opposite ends of a truncated library`() {
        // The old bug: both directions returned the same window, so flipping the
        // sort could never reveal the buckets that had been cut.
        val newest = starts(TimelineWindow.apply(ascending, SortDirection.DESC, limit = 3)).toSet()
        val oldest = starts(TimelineWindow.apply(ascending, SortDirection.ASC, limit = 3)).toSet()
        assertTrue("windows must not overlap on a library this size", (newest intersect oldest).isEmpty())
    }

    @Test
    fun `the most recent bucket is always reachable when sorted newest-first`() {
        // The single property whose absence made the timeline useless at scale.
        for (limit in 1..12) {
            val window = TimelineWindow.apply(ascending, SortDirection.DESC, limit)
            assertEquals("limit=$limit", 10L, window.first().bucketStartEpochMs)
        }
    }

    // ── ordering and edges ────────────────────────────────

    @Test
    fun `newest-first returns buckets in descending order`() {
        val window = TimelineWindow.apply(ascending, SortDirection.DESC, limit = 10)
        assertEquals(starts(window), starts(window).sortedDescending())
    }

    @Test
    fun `oldest-first returns buckets in ascending order`() {
        val window = TimelineWindow.apply(ascending, SortDirection.ASC, limit = 10)
        assertEquals(starts(window), starts(window).sorted())
    }

    @Test
    fun `a limit beyond the library returns everything`() {
        assertEquals(10, TimelineWindow.apply(ascending, SortDirection.DESC, limit = 500).size)
    }

    @Test
    fun `an empty library stays empty`() {
        assertTrue(TimelineWindow.apply(emptyList(), SortDirection.DESC, limit = 60).isEmpty())
    }

    @Test
    fun `a non-positive limit returns nothing rather than everything`() {
        assertTrue(TimelineWindow.apply(ascending, SortDirection.DESC, limit = 0).isEmpty())
        assertTrue(TimelineWindow.apply(ascending, SortDirection.ASC, limit = -1).isEmpty())
    }

    @Test
    fun `the window does not alias the source list`() {
        // asReversed and subList are both views. Returning one would let a later
        // mutation of the DAO's list change what the UI is already showing.
        val source = bucketsFrom(1, 2, 3, 4, 5).toMutableList()
        val window = TimelineWindow.apply(source, SortDirection.DESC, limit = 2)
        val before = starts(window)
        source.clear()
        assertEquals(before, starts(window))
    }
}

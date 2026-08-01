package com.visibeat.musicui.timeline.shared

import com.visibeat.viewengine.TimelineBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Covers the scroll-anchor maths behind pinch-to-zoom. Getting this wrong is
 * invisible in a screenshot and obvious in the hand: the timeline jumps back to
 * the top every time the granularity changes.
 */
class TimelineZoomTest {

    private fun utc(year: Int, month: Int = 1, day: Int = 1): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, 0, 0, 0)
        return cal.timeInMillis
    }

    @Test
    fun `nearestBucketIndex returns -1 for an empty timeline`() {
        assertEquals(-1, TimelineZoom.nearestBucketIndex(emptyList(), utc(2020)))
    }

    @Test
    fun `nearestBucketIndex finds an exact match`() {
        val starts = listOf(utc(2020), utc(2021), utc(2022))
        assertEquals(1, TimelineZoom.nearestBucketIndex(starts, utc(2021)))
    }

    @Test
    fun `nearestBucketIndex snaps to the closest bucket when there is no exact match`() {
        // Zooming from years into months: the focus is a year start, and the
        // month list has no bucket on that exact day.
        val starts = listOf(utc(2021, 1), utc(2021, 6), utc(2021, 11))
        assertEquals(2, TimelineZoom.nearestBucketIndex(starts, utc(2021, 12)))
    }

    @Test
    fun `nearestBucketIndex works on a descending timeline`() {
        // Newest-first is the app's default sort, so display order is descending.
        val starts = listOf(utc(2024), utc(2023), utc(2022), utc(2021))
        assertEquals(2, TimelineZoom.nearestBucketIndex(starts, utc(2022, 3)))
    }

    @Test
    fun `nearestBucketIndex handles a focus before every bucket`() {
        val starts = listOf(utc(2020), utc(2021), utc(2022))
        assertEquals(0, TimelineZoom.nearestBucketIndex(starts, utc(1990)))
    }

    @Test
    fun `nearestBucketIndex handles a focus after every bucket`() {
        val starts = listOf(utc(2020), utc(2021), utc(2022))
        assertEquals(2, TimelineZoom.nearestBucketIndex(starts, utc(2050)))
    }

    @Test
    fun `nearestBucketIndex does not overflow on far-apart epochs`() {
        // A subtraction-based comparator would wrap around Long here.
        val starts = listOf(Long.MIN_VALUE / 2, 0L, Long.MAX_VALUE / 2)
        assertEquals(2, TimelineZoom.nearestBucketIndex(starts, Long.MAX_VALUE / 2 - 1000))
    }

    @Test
    fun `nearestBucketIndex prefers the earlier bucket on a tie`() {
        val starts = listOf(0L, 100L)
        assertEquals(0, TimelineZoom.nearestBucketIndex(starts, 50L))
    }

    @Test
    fun `zoom in and out are inverses across the granularity ladder`() {
        assertEquals(TimelineBucket.MONTH, TimelineZoom.zoomIn(TimelineBucket.YEAR))
        assertEquals(TimelineBucket.DAY, TimelineZoom.zoomIn(TimelineBucket.MONTH))
        assertEquals(TimelineBucket.MONTH, TimelineZoom.zoomOut(TimelineBucket.DAY))
        assertEquals(TimelineBucket.YEAR, TimelineZoom.zoomOut(TimelineBucket.MONTH))
    }

    @Test
    fun `zoom clamps at both ends`() {
        assertEquals(TimelineBucket.DAY, TimelineZoom.zoomIn(TimelineBucket.DAY))
        assertEquals(TimelineBucket.YEAR, TimelineZoom.zoomOut(TimelineBucket.YEAR))
    }

    // -------------------------------------------------------------------
    // anchorIndex — the guard that stops the anchor being spent too early
    // -------------------------------------------------------------------

    @Test
    fun `anchorIndex waits while the rows still answer the old granularity`() {
        // The moment after tapping "Days": query says DAY, rows are still years.
        // Anchoring here scrolls the year list and throws the anchor away, which
        // is what used to dump the user back at the top.
        val yearStarts = listOf(utc(1983, 1, 1), utc(1984, 1, 1), utc(1985, 1, 1))
        assertNull(
            TimelineZoom.anchorIndex(
                dataBucket = TimelineBucket.YEAR,
                wantBucket = TimelineBucket.DAY,
                bucketStarts = yearStarts,
                focusEpochMs = utc(1985, 1, 1)
            )
        )
    }

    @Test
    fun `anchorIndex lands on the matching day once the day rows arrive`() {
        val dayStarts = listOf(utc(1984, 12, 30), utc(1985, 1, 2), utc(1985, 6, 9))
        assertEquals(
            1,
            TimelineZoom.anchorIndex(
                dataBucket = TimelineBucket.DAY,
                wantBucket = TimelineBucket.DAY,
                bucketStarts = dayStarts,
                focusEpochMs = utc(1985, 1, 1)
            )
        )
    }

    @Test
    fun `anchorIndex does nothing without a pending focus`() {
        assertNull(
            TimelineZoom.anchorIndex(
                dataBucket = TimelineBucket.DAY,
                wantBucket = TimelineBucket.DAY,
                bucketStarts = listOf(utc(1985, 1, 1)),
                focusEpochMs = null
            )
        )
    }

    @Test
    fun `anchorIndex waits on an empty list rather than anchoring to nothing`() {
        // A granularity whose query has not returned yet emits an empty list. If
        // that counted as "done", the anchor would be gone before the rows came.
        assertNull(
            TimelineZoom.anchorIndex(
                dataBucket = TimelineBucket.DAY,
                wantBucket = TimelineBucket.DAY,
                bucketStarts = emptyList(),
                focusEpochMs = utc(1985, 1, 1)
            )
        )
    }

    @Test
    fun `anchorIndex survives a round trip out and back to the same year`() {
        val focus = utc(1985, 1, 1)
        val monthStarts = (1..12).map { utc(1985, it, 1) }
        // Years -> Months lands on January 1985...
        assertEquals(
            0,
            TimelineZoom.anchorIndex(
                TimelineBucket.MONTH, TimelineBucket.MONTH, monthStarts, focus
            )
        )
        // ...and Months -> Years finds 1985 again rather than the newest year.
        val yearStarts = listOf(utc(1983, 1, 1), utc(1984, 1, 1), utc(1985, 1, 1))
        assertEquals(
            2,
            TimelineZoom.anchorIndex(
                TimelineBucket.YEAR, TimelineBucket.YEAR, yearStarts, focus
            )
        )
    }
}

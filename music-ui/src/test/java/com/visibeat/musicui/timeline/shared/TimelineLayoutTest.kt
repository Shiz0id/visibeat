package com.visibeat.musicui.timeline.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Which side of the spine each timeline card lands on.
 *
 * The side used to be computed from the bucket's *timestamp* — the parity of its
 * day number — which only alternates when consecutive buckets are an odd number
 * of days apart. Months mostly are not, so cards stacked up on the same side
 * several times a year. These tests describe the property that was missing:
 * consecutive rows alternate, whatever the dates happen to be.
 */
class TimelineLayoutTest {

    private fun monthStartsOf(year: Int): List<Long> = (0..11).map { month ->
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, 1, 0, 0, 0)
        }.timeInMillis
    }

    /** The old, broken rule, kept here only to show what it did. */
    private fun sideByTimestamp(bucketStartEpochMs: Long): Side =
        if ((bucketStartEpochMs / 86_400_000L) % 2L == 0L) Side.LEFT else Side.RIGHT

    @Test
    fun `consecutive rows always alternate`() {
        val sides = (0 until 50).map { sideForIndex(it) }
        sides.zipWithNext().forEachIndexed { i, (a, b) ->
            assertNotEquals("rows $i and ${i + 1} landed on the same side", a, b)
        }
    }

    @Test
    fun `the first row is on the left`() {
        assertEquals(Side.LEFT, sideForIndex(0))
    }

    @Test
    fun `a full year of months alternates cleanly`() {
        // The exact case that failed: twelve month buckets in a row.
        val sides = monthStartsOf(2024).indices.map { sideForIndex(it) }
        assertEquals(6, sides.count { it == Side.LEFT })
        assertEquals(6, sides.count { it == Side.RIGHT })
    }

    @Test
    fun `the old timestamp rule really did stack months on one side`() {
        // Guards the fix by pinning the bug: if this ever stops failing, the
        // premise for switching to index-based sides was wrong.
        val collisions = monthStartsOf(2023)
            .map { sideByTimestamp(it) }
            .zipWithNext()
            .count { (a, b) -> a == b }
        assertEquals("Feb/Mar, Apr/May, Jun/Jul, Sep/Oct, Nov/Dec", 5, collisions)
    }

    @Test
    fun `gaps in the data do not disturb the zigzag`() {
        // Day view only has buckets for days that actually hold music, so the
        // gaps are arbitrary. Sides must not care.
        val sparseRowIndices = listOf(0, 1, 2, 3, 4)
        val sides = sparseRowIndices.map { sideForIndex(it) }
        assertEquals(listOf(Side.LEFT, Side.RIGHT, Side.LEFT, Side.RIGHT, Side.LEFT), sides)
    }
}

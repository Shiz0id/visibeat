package com.visibeat.musicui.playback

import com.visibeat.musicui.design.formatDuration
import com.visibeat.musicui.feed.shared.DateQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    // ── progress ──────────────────────────────────────────
    //
    // Position lives in PlaybackProgress rather than PlaybackState because it
    // ticks twice a second and the host reads playback state in its root
    // composition scope — see PlaybackProgress.

    @Test
    fun `progress is zero while the duration is still unknown`() {
        // ExoPlayer reports TIME_UNSET until the track is prepared; the scrubber
        // must not divide by it.
        val progress = PlaybackProgress(positionMs = 5_000, durationMs = 0)
        assertEquals(0f, progress.fraction, 0.0001f)
    }

    @Test
    fun `progress is the position over the duration`() {
        val progress = PlaybackProgress(positionMs = 30_000, durationMs = 120_000)
        assertEquals(0.25f, progress.fraction, 0.0001f)
    }

    @Test
    fun `progress clamps when the position overshoots the duration`() {
        val progress = PlaybackProgress(positionMs = 130_000, durationMs = 120_000)
        assertEquals(1f, progress.fraction, 0.0001f)
    }

    @Test
    fun `playback state carries no position, so a tick cannot invalidate it`() {
        // The split is the fix; a position field creeping back onto PlaybackState
        // would silently restore 2Hz invalidation of the whole UI.
        val fields = PlaybackState::class.java.declaredFields.map { it.name }
        assertFalse("PlaybackState must not carry the ticking position", fields.any {
            it.contains("position", ignoreCase = true)
        })
    }

    // ── isCurrent ─────────────────────────────────────────

    @Test
    fun `binding marks only the playing track as current`() {
        val binding = PlaybackBinding(nowPlayingTrackId = 7L)
        assertTrue(binding.isCurrent(7L))
        assertFalse(binding.isCurrent(8L))
    }

    @Test
    fun `binding marks nothing as current when nothing is playing`() {
        assertFalse(PlaybackBinding().isCurrent(7L))
    }

    // ── duration formatting ───────────────────────────────

    @Test
    fun `formatDuration renders minutes and seconds`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(7_000))
        assertEquals("3:45", formatDuration(225_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun `formatDuration adds an hours field past one hour`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("2:03:04", formatDuration(7_384_000))
    }

    @Test
    fun `formatDuration shows unknown durations as dashes rather than zero`() {
        // A "0:00" total length reads as a broken file; dashes read as "not yet".
        assertEquals("--:--", formatDuration(-1))
    }

    // ── date-confidence filters ───────────────────────────

    @Test
    fun `date quality filters do not overlap`() {
        // Overlapping sets would make the chips ambiguous: two of them would
        // light up for the same release.
        val seen = mutableSetOf<String>()
        DateQuality.entries.forEach { quality ->
            quality.filters.forEach { value ->
                assertTrue("$value appears in more than one quality band", seen.add(value))
            }
        }
    }

    @Test
    fun `date quality filters cover every value a date view can actually show`() {
        // MusicResolver emits exactly these strings into releaseDateQuality.
        val emitted = setOf("USER", "MUSICBRAINZ", "VERIFIED", "TAGGED", "INFERRED", "UNKNOWN")

        // …except UNKNOWN, which it writes only when it found no date at all.
        // That means a null epoch, and every date-ranged query excludes those by
        // construction, so a chip offering it could never match a row. Those
        // tracks are surfaced as a count on the timeline instead.
        val placeable = emitted - "UNKNOWN"
        val covered = DateQuality.entries.flatMap { it.filters }.toSet()
        assertEquals(placeable, covered)
    }

    @Test
    fun `no filter offers UNKNOWN, which no date query can return`() {
        DateQuality.entries.forEach { quality ->
            assertFalse(
                "${quality.name} offers UNKNOWN, which matches nothing on a date axis",
                "UNKNOWN" in quality.filters
            )
        }
    }
}

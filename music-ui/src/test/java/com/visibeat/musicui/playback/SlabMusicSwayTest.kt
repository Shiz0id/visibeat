package com.visibeat.musicui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlabMusicSwayTest {

    private val tolerance = 0.0001f

    /** 16 bands, matching the slab's visualiser bar count. */
    private fun levels(vararg values: Float) = values

    private fun flat(value: Float, size: Int = 16) = FloatArray(size) { value }

    private fun ramp(from: Float, to: Float, size: Int = 16) =
        FloatArray(size) { i -> from + (to - from) * i / (size - 1f) }

    // ── the pulse-vs-rotation distinction ─────────────────

    @Test
    fun `a flat spectrum produces no sway at all`() {
        // The honest fallback. Sway reads spectral contrast, so a spectrum with
        // no contrast — including every band pinned at its own peak — gives
        // nothing, and the cube is left to its idle drift.
        for (level in listOf(0f, 0.5f, 0.95f, 1f)) {
            assertEquals(0f, SlabMusicSway.yawDegrees(flat(level)), tolerance)
            assertEquals(0f, SlabMusicSway.pitchDegrees(flat(level)), tolerance)
        }
    }

    @Test
    fun `silence produces no sway`() {
        assertEquals(0f, SlabMusicSway.yawDegrees(FloatArray(16)), tolerance)
        assertEquals(0f, SlabMusicSway.pitchDegrees(FloatArray(16)), tolerance)
    }

    @Test
    fun `an empty level array is handled`() {
        assertEquals(0f, SlabMusicSway.yawDegrees(FloatArray(0)), tolerance)
        assertEquals(0f, SlabMusicSway.pitchDegrees(FloatArray(0)), tolerance)
        assertEquals(0f, SlabMusicSway.bandMean(FloatArray(0), 0f, 1f), tolerance)
    }

    // ── yaw follows brightness ────────────────────────────

    @Test
    fun `a bass-heavy mix yaws one way`() {
        val bassHeavy = ramp(from = 1f, to = 0f)
        assertTrue(
            "expected negative yaw, got ${SlabMusicSway.yawDegrees(bassHeavy)}",
            SlabMusicSway.yawDegrees(bassHeavy) < -1f
        )
    }

    @Test
    fun `a bright mix yaws the other way`() {
        val bright = ramp(from = 0f, to = 1f)
        assertTrue(
            "expected positive yaw, got ${SlabMusicSway.yawDegrees(bright)}",
            SlabMusicSway.yawDegrees(bright) > 1f
        )
    }

    @Test
    fun `yaw is symmetric between mirrored spectra`() {
        val bright = ramp(0f, 1f)
        val dark = ramp(1f, 0f)
        assertEquals(
            SlabMusicSway.yawDegrees(bright),
            -SlabMusicSway.yawDegrees(dark),
            tolerance
        )
    }

    // ── pitch follows mid presence ────────────────────────

    @Test
    fun `a mid-forward mix nods up`() {
        // Quiet edges, loud middle.
        val midForward = FloatArray(16) { i -> if (i in 5..9) 1f else 0.1f }
        assertTrue(
            "expected positive pitch, got ${SlabMusicSway.pitchDegrees(midForward)}",
            SlabMusicSway.pitchDegrees(midForward) > 0.5f
        )
    }

    @Test
    fun `a scooped-mid mix nods down`() {
        val scooped = FloatArray(16) { i -> if (i in 5..9) 0.1f else 1f }
        assertTrue(
            "expected negative pitch, got ${SlabMusicSway.pitchDegrees(scooped)}",
            SlabMusicSway.pitchDegrees(scooped) < -0.5f
        )
    }

    @Test
    fun `yaw and pitch respond to different things`() {
        // The whole point: if both axes tracked the same signal the cube would
        // pump along one diagonal instead of turning.
        val midForward = FloatArray(16) { i -> if (i in 5..9) 1f else 0.1f }
        // Symmetric around the middle, so brightness balance is neutral...
        assertEquals(0f, SlabMusicSway.yawDegrees(midForward), 0.5f)
        // ...while mid prominence is not.
        assertTrue(SlabMusicSway.pitchDegrees(midForward) > 0.5f)
    }

    // ── bounds ────────────────────────────────────────────

    @Test
    fun `sway never exceeds its configured limits`() {
        val extremes = listOf(
            ramp(0f, 1f), ramp(1f, 0f), flat(1f), flat(0f),
            FloatArray(16) { i -> if (i % 2 == 0) 1f else 0f },
            FloatArray(16) { i -> if (i in 5..9) 1f else 0f }
        )
        for (levels in extremes) {
            val yaw = SlabMusicSway.yawDegrees(levels)
            val pitch = SlabMusicSway.pitchDegrees(levels)
            assertTrue("yaw $yaw out of range", kotlin.math.abs(yaw) <= SlabMusicSway.MAX_YAW_DEG + tolerance)
            assertTrue("pitch $pitch out of range", kotlin.math.abs(pitch) <= SlabMusicSway.MAX_PITCH_DEG + tolerance)
        }
    }

    @Test
    fun `sway never out-moves a deliberate drag`() {
        // The amount is a taste call and has been turned up once already. What
        // has to hold is that the cube moving itself never travels further than
        // the user moving it, or the gesture stops feeling like it is in charge.
        assertTrue(SlabMusicSway.MAX_YAW_DEG <= SlabDragRotation.MAX_DEGREES)
        assertTrue(SlabMusicSway.MAX_PITCH_DEG <= SlabDragRotation.MAX_DEGREES)
    }

    // ── bandMean ──────────────────────────────────────────

    @Test
    fun `bandMean averages the requested slice`() {
        val levels = levels(0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        assertEquals(0f, SlabMusicSway.bandMean(levels, 0f, 0.5f), tolerance)
        assertEquals(1f, SlabMusicSway.bandMean(levels, 0.5f, 1f), tolerance)
        assertEquals(0.5f, SlabMusicSway.bandMean(levels, 0f, 1f), tolerance)
    }

    @Test
    fun `bandMean always covers at least one band`() {
        // A narrow window on a short array must not divide by zero.
        val levels = levels(0.25f, 0.75f)
        assertEquals(0.25f, SlabMusicSway.bandMean(levels, 0f, 0.01f), tolerance)
        assertEquals(0.75f, SlabMusicSway.bandMean(levels, 0.9f, 1f), tolerance)
    }

    @Test
    fun `bandMean tolerates fractions outside zero to one`() {
        val levels = flat(0.4f, size = 8)
        assertEquals(0.4f, SlabMusicSway.bandMean(levels, -1f, 2f), tolerance)
    }
}

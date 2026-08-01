package com.visibeat.musicui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlabDragRotationTest {

    private val tolerance = 0.0001f

    @Test
    fun `a drag accumulates degrees at the configured rate`() {
        assertEquals(
            3f,
            SlabDragRotation.accumulate(current = 0f, deltaPx = 10f, degreesPerPx = 0.3f),
            tolerance
        )
    }

    @Test
    fun `successive deltas add up`() {
        var angle = 0f
        repeat(5) { angle = SlabDragRotation.accumulate(angle, 10f, degreesPerPx = 0.3f, limit = 90f) }
        assertEquals(15f, angle, tolerance)
    }

    @Test
    fun `dragging back the other way unwinds`() {
        val forward = SlabDragRotation.accumulate(0f, 40f, 0.3f, 90f)
        val back = SlabDragRotation.accumulate(forward, -40f, 0.3f, 90f)
        assertEquals(0f, back, tolerance)
    }

    @Test
    fun `rotation is clamped in both directions`() {
        assertEquals(20f, SlabDragRotation.accumulate(0f, 10_000f, 0.3f, 20f), tolerance)
        assertEquals(-20f, SlabDragRotation.accumulate(0f, -10_000f, 0.3f, 20f), tolerance)
    }

    @Test
    fun `dragging past the limit and back responds immediately`() {
        // The accumulated angle is clamped, not the delta, so there is no
        // invisible slack to unwind before the cube starts moving back.
        val pinned = SlabDragRotation.accumulate(0f, 10_000f, 0.3f, 20f)
        assertEquals(20f, pinned, tolerance)

        val released = SlabDragRotation.accumulate(pinned, -10f, 0.3f, 20f)
        assertEquals(17f, released, tolerance)
    }

    @Test
    fun `a zero delta leaves the angle alone`() {
        assertEquals(7.5f, SlabDragRotation.accumulate(7.5f, 0f, 0.3f, 20f), tolerance)
    }

    @Test
    fun `the default limit stays inside the range the material was tuned for`() {
        // The gloss, rim light and side-face shading are built around the base
        // tilt; a large limit would swing the cube somewhere unart-directed.
        assertTrue(
            "drag limit ${SlabDragRotation.MAX_DEGREES} is too wide",
            SlabDragRotation.MAX_DEGREES <= 25f
        )
    }

    @Test
    fun `the whole range is reachable within a thumb drag`() {
        val pxToReachLimit = SlabDragRotation.MAX_DEGREES / SlabDragRotation.DEGREES_PER_PX
        assertTrue("needs ${pxToReachLimit}px to reach the limit", pxToReachLimit <= 120f)
    }

    @Test
    fun `defaults clamp to the documented maximum`() {
        assertEquals(
            SlabDragRotation.MAX_DEGREES,
            SlabDragRotation.accumulate(0f, 5_000f),
            tolerance
        )
    }
}

package com.visibeat.musicui.playback

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slab's footprint has to match what it draws — MainActivity sizes the
 * collapsed tile's drag bounds from these numbers, and the expanded player works
 * out how big the cube can be from them too.
 */
class NowPlayingSlabMetricsTest {

    private val tolerance = 0.01f

    @Test
    fun `default scale is unchanged from the base metrics`() {
        // The collapsed tile passes no scale, so it must be byte-for-byte the
        // size it always was.
        assertEquals(
            NowPlayingSlabMetrics.Width.value,
            NowPlayingSlabMetrics.width().value,
            tolerance
        )
        assertEquals(
            NowPlayingSlabMetrics.CubeHeight.value,
            NowPlayingSlabMetrics.height(showReflection = false).value,
            tolerance
        )
    }

    @Test
    fun `scaling is uniform across width and height`() {
        val scale = NowPlayingSlabMetrics.ExpandedScale
        val widthRatio = NowPlayingSlabMetrics.width(scale) / NowPlayingSlabMetrics.width()
        val heightRatio = NowPlayingSlabMetrics.height(true, scale) /
            NowPlayingSlabMetrics.height(true)

        assertEquals(scale, widthRatio, tolerance)
        assertEquals(scale, heightRatio, tolerance)
        // Aspect ratio has to survive, or the cube would be drawn into a box the
        // wrong shape and clip on one axis.
        assertEquals(widthRatio, heightRatio, tolerance)
    }

    @Test
    fun `the reflection adds height and scales with everything else`() {
        val withReflection = NowPlayingSlabMetrics.height(showReflection = true)
        val without = NowPlayingSlabMetrics.height(showReflection = false)
        assertTrue(withReflection > without)

        val scale = 2f
        assertEquals(
            withReflection.value * scale,
            NowPlayingSlabMetrics.height(true, scale).value,
            tolerance
        )
        assertEquals(
            without.value * scale,
            NowPlayingSlabMetrics.height(false, scale).value,
            tolerance
        )
    }

    @Test
    fun `the expanded cube is about half again as large`() {
        assertEquals(1.5f, NowPlayingSlabMetrics.ExpandedScale, tolerance)
    }

    // ── the expanded player's fit calculation ─────────────

    /** Mirrors what NowPlayingExpanded does with its own height. */
    private fun slabScaleFor(panelHeightDp: Float, chromeDp: Float = 360f): Float {
        val available = (panelHeightDp - chromeDp).dp
        return (available / NowPlayingSlabMetrics.height(true))
            .coerceIn(0.85f, NowPlayingSlabMetrics.ExpandedScale)
    }

    @Test
    fun `a tall phone gets the full requested size`() {
        // 0.88 of an 800dp-tall screen.
        assertEquals(NowPlayingSlabMetrics.ExpandedScale, slabScaleFor(704f), tolerance)
    }

    @Test
    fun `a very tall screen is still capped`() {
        assertEquals(NowPlayingSlabMetrics.ExpandedScale, slabScaleFor(1200f), tolerance)
    }

    @Test
    fun `a short screen scales the cube down rather than clipping controls`() {
        val scale = slabScaleFor(560f)
        assertTrue("expected a reduced scale, got $scale", scale < NowPlayingSlabMetrics.ExpandedScale)
        assertTrue("expected the cube to stay usable, got $scale", scale >= 0.85f)
    }

    @Test
    fun `an absurdly short screen still leaves a visible cube`() {
        assertEquals(0.85f, slabScaleFor(200f), tolerance)
    }

    @Test
    fun `the fit calculation never returns a scale that overflows the panel`() {
        // Walk a range of plausible panel heights and confirm the cube plus the
        // chrome estimate fits, except where the floor deliberately wins.
        var panel = 420f
        while (panel <= 1000f) {
            val scale = slabScaleFor(panel)
            val used = NowPlayingSlabMetrics.height(true, scale).value + 360f
            if (scale > 0.85f) {
                assertTrue("panel ${panel}dp: used ${used}dp overflows", used <= panel + tolerance)
            }
            panel += 20f
        }
    }
}

package com.visibeat.musicui.playback

import com.visibeat.musicui.playback.VisualizerBinding.OUTPUT_MIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the frozen cube: the visualiser attached to the global
 * output mix, which yields silence on modern Android, and never re-attached once
 * the real audio session id turned up.
 */
class VisualizerBindingTest {

    private val REAL_SESSION = 42
    private val OTHER_SESSION = 99

    // ── which session do we want ──────────────────────────

    @Test
    fun `the highest-priority real id wins`() {
        assertEquals(
            REAL_SESSION,
            VisualizerBinding.preferredSessionId(listOf(REAL_SESSION, OTHER_SESSION))
        )
    }

    @Test
    fun `an empty source is skipped for a later real one`() {
        // The device case: the service field held the id, the session extras
        // came back 0. Falling through to the output mix here is what killed
        // the capture entirely.
        assertEquals(
            OTHER_SESSION,
            VisualizerBinding.preferredSessionId(listOf(OUTPUT_MIX, OTHER_SESSION))
        )
    }

    @Test
    fun `empty sources at any position are skipped`() {
        assertEquals(
            REAL_SESSION,
            VisualizerBinding.preferredSessionId(listOf(OUTPUT_MIX, OUTPUT_MIX, REAL_SESSION))
        )
    }

    @Test
    fun `with no id anywhere we fall back to the output mix`() {
        assertEquals(
            OUTPUT_MIX,
            VisualizerBinding.preferredSessionId(listOf(OUTPUT_MIX, OUTPUT_MIX, OUTPUT_MIX))
        )
    }

    @Test
    fun `no sources at all falls back to the output mix`() {
        assertEquals(OUTPUT_MIX, VisualizerBinding.preferredSessionId(emptyList()))
    }

    // ── when do we rebind ─────────────────────────────────

    @Test
    fun `binds when nothing is attached yet`() {
        assertTrue(VisualizerBinding.shouldRebind(boundSessionId = null, preferredSessionId = REAL_SESSION))
        assertTrue(VisualizerBinding.shouldRebind(boundSessionId = null, preferredSessionId = OUTPUT_MIX))
    }

    @Test
    fun `rebinds when a real session id arrives after settling for the output mix`() {
        // The bug. Playback starts before the service publishes its id, so the
        // visualiser lands on the output mix and captures silence; when the real
        // id shows up it has to move, or the cube never moves again.
        assertTrue(
            VisualizerBinding.shouldRebind(
                boundSessionId = OUTPUT_MIX,
                preferredSessionId = REAL_SESSION
            )
        )
    }

    @Test
    fun `does not rebind when already on the right session`() {
        assertFalse(VisualizerBinding.shouldRebind(REAL_SESSION, REAL_SESSION))
    }

    @Test
    fun `does not churn when stuck on the output mix with nothing better`() {
        // Extras changes fire repeatedly; tearing the capture down each time would
        // strobe the visualiser for no gain.
        assertFalse(VisualizerBinding.shouldRebind(OUTPUT_MIX, OUTPUT_MIX))
    }

    @Test
    fun `moves to a new session id when the platform changes it mid-session`() {
        assertTrue(VisualizerBinding.shouldRebind(REAL_SESSION, OTHER_SESSION))
    }

    // ── attempt order ─────────────────────────────────────

    @Test
    fun `a real session is tried before the output mix`() {
        assertEquals(listOf(REAL_SESSION, OUTPUT_MIX), VisualizerBinding.candidates(REAL_SESSION))
    }

    @Test
    fun `the output mix is not listed twice`() {
        assertEquals(listOf(OUTPUT_MIX), VisualizerBinding.candidates(OUTPUT_MIX))
    }
}

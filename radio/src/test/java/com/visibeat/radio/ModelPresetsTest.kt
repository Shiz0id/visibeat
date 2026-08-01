package com.visibeat.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the transcribed model configs.
 *
 * These are not testing our code — they are testing that nobody has quietly
 * edited a number that came off a model card. A wrong value here is invisible
 * everywhere else in the system.
 */
class ModelPresetsTest {

    @Test
    fun `dclap preset matches its published preprocessing`() {
        val mel = ModelPresets.DCLAP.mel
        assertEquals(48_000, mel.sampleRate)
        assertEquals(2048, mel.nFft)
        assertEquals(480, mel.hopLength)
        assertEquals(128, mel.nMels)
        assertEquals(0f, mel.fMin, 0f)
        assertEquals(14_000f, mel.effectiveFMax, 0f)
        // power_to_db(ref=1.0, top_db=None): fixed reference, no floor.
        val log = mel.logCompression
        assertTrue(log is LogCompression.Decibels)
        assertEquals(null, (log as LogCompression.Decibels).topDb)
        assertTrue(log.ref is LogCompression.Decibels.Ref.Fixed)
        assertEquals(512, ModelPresets.DCLAP_DIM)
    }

    @Test
    fun `laion preset matches its preprocessor config`() {
        val mel = ModelPresets.LAION_CLAP.mel
        assertEquals(48_000, mel.sampleRate)
        assertEquals(1024, mel.nFft)
        assertEquals(480, mel.hopLength)
        assertEquals(64, mel.nMels)
        assertEquals(50f, mel.fMin, 0f)
        assertEquals(14_000f, mel.effectiveFMax, 0f)
    }

    @Test
    fun `the teacher and its distillation disagree on three parameters`() {
        // The reason MelConfig is a config and not a constant. Same family, same
        // 512-dimensional output space, and every one of these differs. Swapping
        // the preset without swapping the model throws nothing and returns
        // vectors that look entirely normal.
        val teacher = ModelPresets.LAION_CLAP.mel
        val student = ModelPresets.DCLAP.mel
        assertNotEquals(teacher.nMels, student.nMels)
        assertNotEquals(teacher.nFft, student.nFft)
        assertNotEquals(teacher.fMin, student.fMin)
        // ...and agree on the ones that would have been easy to get wrong.
        assertEquals(teacher.sampleRate, student.sampleRate)
        assertEquals(teacher.hopLength, student.hopLength)
    }

    @Test
    fun `both ids name the preprocessing, not just the checkpoint`() {
        // Same weights fed different mel parameters are a different model, so the
        // invalidation key has to cover both or a preset change silently keeps
        // stale vectors.
        assertTrue(ModelPresets.DCLAP_ID.contains("mel128"))
        assertTrue(ModelPresets.LAION_CLAP_ID.contains("mel64"))
    }

    @Test
    fun `fixedFrames matches a ten second segment at the stated hop`() {
        // 10 s at 48 kHz, hop 480, centre-padded: 1 + 480000/480 = 1001.
        val mel = ModelPresets.DCLAP.mel
        val samples = (ModelPresets.DCLAP.segmentSeconds!! * mel.sampleRate).toInt()
        assertEquals(ModelPresets.DCLAP.fixedFrames, mel.frameCount(samples))
    }

    @Test
    fun `both presets segment rather than feeding one long window`() {
        for (spec in listOf(ModelPresets.DCLAP, ModelPresets.LAION_CLAP)) {
            assertEquals(10f, spec.segmentSeconds!!, 0f)
            assertEquals(0.5f, spec.segmentOverlap, 0f)
            assertTrue("window must be longer than a segment", spec.windowSeconds > spec.segmentSeconds!!)
            assertTrue("reference pipelines quantise to int16", spec.quantizeToInt16)
        }
    }
}

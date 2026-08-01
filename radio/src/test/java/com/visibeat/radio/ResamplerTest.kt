package com.visibeat.radio

import com.visibeat.radio.dsp.Resampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The resampler runs on nearly every track in the library, so it has to be both
 * correct and cheap. These pin the correctness; the cheapness came from
 * tabulating the kernel, and a table that had drifted from the closed form
 * would show up here as amplitude or frequency error.
 */
class ResamplerTest {

    private fun tone(hz: Double, rate: Int, seconds: Double, amp: Double = 1.0) =
        FloatArray((rate * seconds).toInt()) { i ->
            (amp * sin(2.0 * PI * hz * i / rate)).toFloat()
        }

    /** Peak amplitude away from the edges, where the kernel is fully covered. */
    private fun interiorPeak(x: FloatArray, margin: Int = 200): Float {
        var p = 0f
        for (i in margin until x.size - margin) {
            val a = abs(x[i])
            if (a > p) p = a
        }
        return p
    }

    /** Crude frequency estimate by counting zero crossings. */
    private fun estimateHz(x: FloatArray, rate: Int, margin: Int = 200): Double {
        var crossings = 0
        for (i in margin + 1 until x.size - margin) {
            if ((x[i - 1] < 0f) != (x[i] < 0f)) crossings++
        }
        val span = (x.size - 2 * margin).toDouble() / rate
        return crossings / 2.0 / span
    }

    @Test
    fun `same rate is returned untouched`() {
        val x = tone(440.0, 48_000, 0.1)
        assertTrue(x === Resampler.resample(x, 48_000, 48_000))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(0, Resampler.resample(FloatArray(0), 44_100, 48_000).size)
    }

    @Test
    fun `output length follows the ratio`() {
        val x = tone(440.0, 44_100, 1.0)
        val out = Resampler.resample(x, 44_100, 48_000)
        assertEquals(48_000, out.size)
    }

    @Test
    fun `upsampling preserves frequency and amplitude`() {
        // The real case: 44.1 kHz FLAC into a model that wants 48 kHz.
        val out = Resampler.resample(tone(1000.0, 44_100, 0.5), 44_100, 48_000)
        assertEquals(1000.0, estimateHz(out, 48_000), 15.0)
        assertEquals(1.0f, interiorPeak(out), 0.02f)
    }

    @Test
    fun `downsampling preserves a tone below the new Nyquist`() {
        val out = Resampler.resample(tone(1000.0, 48_000, 0.5), 48_000, 16_000)
        assertEquals(1000.0, estimateHz(out, 16_000), 15.0)
        assertEquals(1.0f, interiorPeak(out), 0.05f)
    }

    @Test
    fun `downsampling rejects content above the new Nyquist`() {
        // The whole reason this is not linear interpolation. A 15 kHz tone
        // resampled to 16 kHz has nowhere legitimate to go; without a proper
        // filter it folds down to 1 kHz and lands in the mel bands the model
        // reads, differently for every source rate.
        val out = Resampler.resample(tone(15_000.0, 48_000, 0.5), 48_000, 16_000)
        assertTrue(
            "alias energy leaked through: peak ${interiorPeak(out)}",
            interiorPeak(out) < 0.05f
        )
    }

    @Test
    fun `gain is unity on a constant signal`() {
        val dc = FloatArray(44_100) { 0.5f }
        val out = Resampler.resample(dc, 44_100, 48_000)
        for (i in 500 until out.size - 500) {
            assertEquals(0.5f, out[i], 1e-3f)
        }
    }

    @Test
    fun `quiet input stays quiet rather than picking up a floor`() {
        // Matters because AudioWindowReader treats a near-silent window as a
        // failed decode. A resampler that injected energy would defeat that.
        val out = Resampler.resample(tone(1000.0, 44_100, 0.2, amp = 1e-6), 44_100, 48_000)
        assertTrue(interiorPeak(out) < 1e-5f)
    }

    @Test
    fun `no output value is non-finite`() {
        val out = Resampler.resample(tone(3000.0, 44_100, 0.3), 44_100, 48_000)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun `an input shorter than the kernel does not throw`() {
        val out = Resampler.resample(FloatArray(4) { 0.1f }, 44_100, 48_000)
        assertTrue(out.all { it.isFinite() })
    }
}

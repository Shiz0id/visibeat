package com.visibeat.radio

import com.visibeat.radio.dsp.Resampler
import org.junit.Test

/** Not an assertion, just a number to look at. Prints on the test task's output. */
class ResamplerBench {
    @Test
    fun `time a thirty second window`() {
        val input = FloatArray(44_100 * 30) { kotlin.math.sin(it * 0.01).toFloat() }
        Resampler.resample(input.copyOf(2000), 44_100, 48_000)   // warm
        val t0 = System.nanoTime()
        val out = Resampler.resample(input, 44_100, 48_000)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("RESAMPLE 30s 44.1k->48k : %.0f ms  (%d samples out)".format(ms, out.size))
    }
}

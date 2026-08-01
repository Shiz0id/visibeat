package com.visibeat.radio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited resampling, because linear interpolation is not good enough here.
 *
 * Nearly all music decodes at 44.1 or 48 kHz and the models want 48, so a good
 * fraction of every library is resampled. Dropping samples — or interpolating
 * between them, which is the same thing with a bad filter — folds everything
 * above the new Nyquist back down into the audible band as alias energy. That
 * lands squarely in the mel bins the model reads, and it lands differently
 * depending on the source rate, so two encodings of the same song get different
 * embeddings. A windowed-sinc kernel with the cutoff at the *lower* of the two
 * Nyquists removes it.
 *
 * The kernel is tabulated once per call and read by interpolation. The obvious
 * implementation evaluates the window and the sinc at every tap of every output
 * sample, which for a thirty-second window at 48 kHz is 46 million kernel
 * evaluations — 92 million `sin`/`cos` calls, several seconds per track, and
 * over half an hour across a library. The table costs a few thousand trig calls
 * total and is read with a multiply and an add.
 */
internal object Resampler {

    /** Taps either side of centre. 16 is the usual quality/cost knee. */
    private const val HALF_TAPS = 16

    /**
     * Table entries per unit of kernel width.
     *
     * The kernel is smooth, so linear interpolation between entries is accurate
     * to about 1e-5 at this density — far below the noise floor of 16-bit audio
     * and orders of magnitude below anything a mel filterbank would notice.
     */
    private const val STEPS_PER_TAP = 512

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "rates must be positive" }
        if (fromRate == toRate || input.isEmpty()) return input

        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLength = floor(input.size * ratio).toInt()
        if (outLength <= 0) return FloatArray(0)

        // Downsampling moves the cutoff down with the output rate; upsampling
        // leaves it at the source Nyquist. Both are "the lower of the two".
        val cutoff = min(1.0, ratio)
        // The kernel widens by the same factor, so it still spans HALF_TAPS
        // periods of the cutoff frequency.
        val scale = if (ratio < 1.0) 1.0 / ratio else 1.0
        val halfWidth = ceil(HALF_TAPS * scale).toInt()

        val table = buildKernel(halfWidth, scale, cutoff)
        val tableMax = table.size - 2
        val stepsPerX = STEPS_PER_TAP.toFloat()
        val step = 1.0 / ratio

        val out = FloatArray(outLength)
        var centre = 0.0
        for (i in 0 until outLength) {
            val base = floor(centre).toInt()
            val first = base - halfWidth + 1
            val last = base + halfWidth

            // Distance from the first tap to the centre, walked forward by one
            // per tap. The obvious `abs(j - centre)` costs a double subtract
            // against a double every iteration; this is one float add.
            var x = (first - centre).toFloat()

            var acc = 0f
            var norm = 0f
            val lo = if (first < 0) 0 else first
            val hi = if (last >= input.size) input.size - 1 else last
            x += (lo - first).toFloat()

            for (j in lo..hi) {
                val pos = (if (x < 0f) -x else x) * stepsPerX
                val idx = pos.toInt()
                if (idx < tableMax) {
                    val frac = pos - idx
                    val h = table[idx] + (table[idx + 1] - table[idx]) * frac
                    acc += input[j] * h
                    norm += h
                }
                x += 1f
            }
            // Normalising by the realised kernel sum keeps unity gain even at
            // the edges, where part of the kernel hangs off the end of the input.
            out[i] = if (norm > 1e-9f) acc / norm else 0f
            centre += step
        }
        return out
    }

    /**
     * The windowed sinc, sampled at [STEPS_PER_TAP] points per unit distance.
     *
     * Indexed by distance from the centre in *input samples*, which is why the
     * caller can share one table across every output sample regardless of where
     * its fractional position falls.
     */
    private fun buildKernel(halfWidth: Int, scale: Double, cutoff: Double): FloatArray {
        val size = halfWidth * STEPS_PER_TAP + 2
        val table = FloatArray(size)
        for (i in 0 until size) {
            val x = i.toDouble() / STEPS_PER_TAP
            val t = x / scale
            table[i] = if (t >= halfWidth / scale) {
                0f
            } else {
                val window = 0.5 * (1.0 + cos(PI * t / (halfWidth / scale)))
                (window * cutoff * sinc(cutoff * t)).toFloat()
            }
        }
        return table
    }

    private fun sinc(x: Double): Double {
        if (abs(x) < 1e-9) return 1.0
        val px = PI * x
        return sin(px) / px
    }
}

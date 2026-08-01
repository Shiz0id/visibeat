package com.visibeat.radio.dsp

/**
 * Iterative radix-2 FFT, sized once and reused.
 *
 * A 30-second window at 22.05 kHz with a 512-sample hop is about 1,300 frames,
 * and every frame is one transform. Allocating twiddle factors and a bit-reversal
 * table per frame would cost more than the arithmetic; they are computed once in
 * the constructor and the transform runs in place on caller-owned buffers, so a
 * whole spectrogram allocates nothing after setup.
 *
 * @param size transform length. Must be a power of two.
 */
internal class Fft(val size: Int) {

    init {
        require(size > 0 && size and (size - 1) == 0) {
            "FFT size must be a power of two, was $size"
        }
    }

    /** Bit-reversal permutation, precomputed. */
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            var v = i
            var r = 0
            repeat(bits) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            table[i] = r
        }
    }

    // Twiddles for every stage, flattened. cos/sin split so the inner loop reads
    // two sequential arrays rather than striding one interleaved array.
    private val cos = FloatArray(size / 2)
    private val sin = FloatArray(size / 2)

    init {
        for (i in 0 until size / 2) {
            val angle = -2.0 * Math.PI * i / size
            cos[i] = Math.cos(angle).toFloat()
            sin[i] = Math.sin(angle).toFloat()
        }
    }

    /**
     * In-place complex FFT. [re] and [im] are both [size] long and are modified.
     */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size) {
            "buffers must be $size long, were ${re.size}/${im.size}"
        }

        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= size) {
            val half = len shr 1
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cos[k]
                    val wi = sin[k]
                    val jh = j + half
                    val xr = re[jh] * wr - im[jh] * wi
                    val xi = re[jh] * wi + im[jh] * wr
                    re[jh] = re[j] - xr
                    im[jh] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Magnitude-squared of the first `size/2 + 1` bins of a real signal.
     *
     * The upper half of a real transform is the conjugate mirror of the lower,
     * so it carries no information and is not computed. [out] must be at least
     * `size / 2 + 1` long; [scratchRe]/[scratchIm] are working buffers of [size].
     */
    fun powerSpectrum(
        frame: FloatArray,
        out: FloatArray,
        scratchRe: FloatArray,
        scratchIm: FloatArray
    ) {
        System.arraycopy(frame, 0, scratchRe, 0, size)
        java.util.Arrays.fill(scratchIm, 0f)
        transform(scratchRe, scratchIm)
        for (i in 0..size / 2) {
            val r = scratchRe[i]
            val m = scratchIm[i]
            out[i] = r * r + m * m
        }
    }
}

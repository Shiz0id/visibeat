package com.visibeat.radio

import com.visibeat.radio.dsp.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The spectrogram is the part of this feature that breaks without saying so.
 *
 * A wrong hop length, a wrong mel scale or a missing log still produce an array
 * of the right shape holding numbers of the right magnitude; the model consumes
 * it, the vector normalises, the cosine computes, and the radio plays something
 * unrelated. Nothing throws. These tests exist because "it ran" carries no
 * information here.
 *
 * They check internal correctness — that the transform is the transform it
 * claims to be. They cannot check the thing that actually matters, which is
 * whether it matches the preprocessing the model was *trained* with. See
 * `matchesReferenceImplementation` at the bottom for how to close that gap.
 */
class MelSpectrogramTest {

    // ---------------------------------------------------------------- FFT

    @Test
    fun `fft of a pure tone puts its energy in one bin`() {
        val n = 1024
        val fft = Fft(n)
        val bin = 64
        val signal = FloatArray(n) { i -> sin(2.0 * PI * bin * i / n).toFloat() }

        val power = FloatArray(n / 2 + 1)
        fft.powerSpectrum(signal, power, FloatArray(n), FloatArray(n))

        val peak = power.indices.maxByOrNull { power[it] }!!
        assertEquals(bin, peak)

        // And the rest is numerically nothing, not merely smaller.
        val total = power.sum()
        assertTrue(
            "peak should hold essentially all the energy, held ${power[peak] / total}",
            power[peak] / total > 0.99f
        )
    }

    @Test
    fun `fft preserves energy`() {
        // Parseval: a bug in the butterflies or the twiddles shows up here even
        // when the peak lands in the right place.
        val n = 256
        val fft = Fft(n)
        val rnd = java.util.Random(7)
        val signal = FloatArray(n) { rnd.nextGaussian().toFloat() }

        var timeEnergy = 0.0
        for (x in signal) timeEnergy += x.toDouble() * x

        val re = signal.copyOf()
        val im = FloatArray(n)
        fft.transform(re, im)
        var freqEnergy = 0.0
        for (i in 0 until n) freqEnergy += re[i].toDouble() * re[i] + im[i].toDouble() * im[i]

        assertEquals(timeEnergy, freqEnergy / n, timeEnergy * 1e-4)
    }

    @Test
    fun `fft rejects a non power of two`() {
        val e = runCatching { Fft(1000) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    // ------------------------------------------------------------ framing

    @Test
    fun `frame count matches librosa arithmetic`() {
        // 1 + (padded - nFft) / hop, with centre padding of nFft/2 either side.
        val config = MelConfig(sampleRate = 22050, nFft = 1024, hopLength = 512, center = true)
        assertEquals(1 + (22050 + 1024 - 1024) / 512, config.frameCount(22050))

        val uncentred = config.copy(center = false)
        assertEquals(1 + (22050 - 1024) / 512, uncentred.frameCount(22050))
    }

    @Test
    fun `a signal shorter than one window yields no frames rather than throwing`() {
        val config = MelConfig(nFft = 1024, hopLength = 512, center = false)
        val mel = MelSpectrogramExtractor(config).compute(FloatArray(100))
        assertEquals(0, mel.nFrames)
    }

    @Test
    fun `output is nMels by frames`() {
        val config = MelConfig(sampleRate = 22050, nMels = 64, nFft = 1024, hopLength = 512)
        val pcm = FloatArray(22050) { i -> sin(2.0 * PI * 440 * i / 22050).toFloat() }
        val mel = MelSpectrogramExtractor(config).compute(pcm)

        assertEquals(64, mel.nMels)
        assertEquals(config.frameCount(pcm.size), mel.nFrames)
        assertEquals(64 * mel.nFrames, mel.data.size)
    }

    // ------------------------------------------------------------ mel scale

    @Test
    fun `a tone lands in the mel band containing its frequency`() {
        val sr = 22050
        val config = MelConfig(
            sampleRate = sr, nMels = 64, nFft = 1024, hopLength = 512,
            logCompression = LogCompression.None
        )
        val extractor = MelSpectrogramExtractor(config)
        val pcm = FloatArray(sr) { i -> sin(2.0 * PI * 1000.0 * i / sr).toFloat() }
        val mel = extractor.compute(pcm)

        // Energy per mel band, summed over time.
        val perBand = FloatArray(mel.nMels)
        for (m in 0 until mel.nMels) {
            var acc = 0f
            for (f in 0 until mel.nFrames) acc += mel[m, f]
            perBand[m] = acc
        }
        val peak = perBand.indices.maxByOrNull { perBand[it] }!!

        // Which band should hold 1 kHz, computed from the scale rather than
        // hard-coded, so the assertion follows the config.
        val melMin = 2595.0 * Math.log10(1.0 + 0.0 / 700.0)
        val melMax = 2595.0 * Math.log10(1.0 + (sr / 2.0) / 700.0)
        val mel1k = 2595.0 * Math.log10(1.0 + 1000.0 / 700.0)
        val expected = ((mel1k - melMin) / (melMax - melMin) * (config.nMels + 1)).toInt() - 1

        assertTrue(
            "1 kHz peaked in band $peak, expected near $expected",
            abs(peak - expected) <= 1
        )
    }

    @Test
    fun `htk and slaney disagree, which is why the scale is configurable`() {
        // Not a correctness check — a demonstration that picking the wrong one
        // silently changes every vector. Both runs succeed; they just differ.
        val sr = 22050
        val pcm = FloatArray(sr) { i ->
            (sin(2.0 * PI * 300.0 * i / sr) + 0.5 * sin(2.0 * PI * 3000.0 * i / sr)).toFloat()
        }
        val base = MelConfig(sampleRate = sr, nMels = 40, logCompression = LogCompression.None)

        val htk = MelSpectrogramExtractor(base.copy(melScale = MelScale.HTK)).compute(pcm)
        val slaney = MelSpectrogramExtractor(base.copy(melScale = MelScale.SLANEY)).compute(pcm)

        assertEquals(htk.data.size, slaney.data.size)
        var maxDiff = 0f
        for (i in htk.data.indices) maxDiff = maxOf(maxDiff, abs(htk.data[i] - slaney.data[i]))
        assertTrue("the two scales should not agree", maxDiff > 1e-3f)
    }

    @Test
    fun `slaney normalisation scales filters without reordering them`() {
        val sr = 22050
        val pcm = FloatArray(sr) { i -> sin(2.0 * PI * 800.0 * i / sr).toFloat() }
        val base = MelConfig(sampleRate = sr, nMels = 40, logCompression = LogCompression.None)

        val plain = MelSpectrogramExtractor(base).compute(pcm)
        val normed = MelSpectrogramExtractor(base.copy(slaneyNorm = true)).compute(pcm)

        fun peakBand(m: MelSpectrogram): Int =
            (0 until m.nMels).maxByOrNull { band ->
                (0 until m.nFrames).map { m[band, it] }.sum()
            }!!

        assertEquals(peakBand(plain), peakBand(normed))
        assertNotEquals(plain.data[0], normed.data[0])
    }

    // ---------------------------------------------------------- compression

    @Test
    fun `decibel compression floors at topDb below the peak`() {
        val sr = 22050
        val config = MelConfig(
            sampleRate = sr, nMels = 32,
            logCompression = LogCompression.Decibels(topDb = 80f)
        )
        val pcm = FloatArray(sr) { i -> sin(2.0 * PI * 500.0 * i / sr).toFloat() }
        val mel = MelSpectrogramExtractor(config).compute(pcm)

        val max = mel.data.max()
        val min = mel.data.min()
        assertTrue("dynamic range should be clamped to 80 dB, was ${max - min}", max - min <= 80.001f)
    }

    @Test
    fun `log compression never produces a non-finite value on silence`() {
        // Digital silence is a real input — leading gaps, gaps between tracks —
        // and ln(0) would put -Inf into a tensor, which propagates to NaN in the
        // embedding and then poisons every cosine computed against it.
        val config = MelConfig(nMels = 32, logCompression = LogCompression.NaturalLog())
        val mel = MelSpectrogramExtractor(config).compute(FloatArray(22050))
        assertTrue(mel.data.all { it.isFinite() })
    }

    @Test
    fun `reflect padding leaks less at the edge than zero padding`() {
        // Zero-padding a centred STFT puts a step at both ends, and a step is
        // broadband — it smears energy across the whole spectrum of the first
        // and last frames, a click that is not in the audio. Reflection removes
        // the step.
        //
        // It does not make the edge clean. Mirroring is an even reflection, so a
        // sine crossing zero comes back with its slope reversed and leaves a
        // cusp, which is still broadband, just less of it. Every implementation
        // has this; the first frame of a spectrogram is always a bit of a lie.
        // The claim worth testing is the comparison, not an absolute.
        val sr = 22050
        val base = MelConfig(
            sampleRate = sr, nMels = 32, center = true,
            logCompression = LogCompression.None
        )
        // Phase-shifted deliberately. A sine starting at zero is the one signal
        // where zero-padding wins, because there is no step to create — the
        // first version of this test used one and measured that accident rather
        // than the property. Music does not begin at a zero crossing.
        val pcm = FloatArray(sr) { i -> sin(2.0 * PI * 440.0 * i / sr + 1.2).toFloat() }

        fun edgeLeak(mode: PadMode): Float {
            val mel = MelSpectrogramExtractor(base.copy(padMode = mode)).compute(pcm)
            return (mel.nMels * 3 / 4 until mel.nMels).map { mel[it, 0] }.sum()
        }

        val reflect = edgeLeak(PadMode.REFLECT)
        val constant = edgeLeak(PadMode.CONSTANT)
        assertTrue(
            "reflect leaked $reflect, zero-pad leaked $constant",
            reflect < constant
        )
    }

    @Test
    fun `both pad modes leave the interior alone`() {
        // Whatever the edges do, the middle of the signal must be identical —
        // otherwise the pad mode is affecting more than it should.
        val sr = 22050
        val base = MelConfig(sampleRate = sr, nMels = 32, logCompression = LogCompression.None)
        val pcm = FloatArray(sr) { i -> sin(2.0 * PI * 440.0 * i / sr + 1.2).toFloat() }

        val a = MelSpectrogramExtractor(base.copy(padMode = PadMode.REFLECT)).compute(pcm)
        val b = MelSpectrogramExtractor(base.copy(padMode = PadMode.CONSTANT)).compute(pcm)

        val mid = a.nFrames / 2
        for (m in 0 until a.nMels) {
            assertEquals(a[m, mid], b[m, mid], 1e-6f)
        }
    }

    // ------------------------------------------------------------- reshaping

    @Test
    fun `fitTo pads and truncates without disturbing the mel axis`() {
        val mel = MelSpectrogram(3, 4, floatArrayOf(
            1f, 2f, 3f, 4f,
            5f, 6f, 7f, 8f,
            9f, 10f, 11f, 12f
        ))

        val padded = mel.fitTo(6)
        assertEquals(6, padded.nFrames)
        assertEquals(1f, padded[0, 0], 0f)
        assertEquals(4f, padded[0, 3], 0f)
        assertEquals(0f, padded[0, 5], 0f)
        assertEquals(9f, padded[2, 0], 0f)

        val cut = mel.fitTo(2)
        assertEquals(2, cut.nFrames)
        assertEquals(5f, cut[1, 0], 0f)
        assertEquals(6f, cut[1, 1], 0f)
    }

    @Test
    fun `transposed reorders to frame major`() {
        val mel = MelSpectrogram(2, 3, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        // [[1,2,3],[4,5,6]] read frame-first is [1,4, 2,5, 3,6]
        assertTrue(floatArrayOf(1f, 4f, 2f, 5f, 3f, 6f).contentEquals(mel.transposed()))
    }

    /**
     * The test this suite cannot write for you.
     *
     * Everything above proves the transform is self-consistent. None of it
     * proves it matches the model's training preprocessing, and that is the only
     * property that makes the radio work. To check it, once:
     *
     *   1. Take one track. Compute its log-mel in Python with the exact code the
     *      model's authors used — librosa or torchaudio, their parameters.
     *   2. Dump the array to CSV, drop it in `src/test/resources`.
     *   3. Run the same PCM through [MelSpectrogramExtractor] and assert the
     *      arrays agree to about 1e-3.
     *
     * If they do not, the difference is almost always one of: mel scale (HTK vs
     * Slaney), filter normalisation, log base, centre padding, or window
     * periodicity. All five are fields on [MelConfig] precisely because each has
     * been someone's silent failure.
     */
    @Test
    fun matchesReferenceImplementation() {
        // Deliberately empty. See the note above — this needs a reference array
        // from the model's own preprocessing, which cannot be synthesised here.
    }
}

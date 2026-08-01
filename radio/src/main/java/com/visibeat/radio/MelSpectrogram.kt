package com.visibeat.radio

import com.visibeat.radio.dsp.Fft
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Which formula maps hertz to mels. They disagree, and models are trained on one. */
enum class MelScale {
    /** `2595 * log10(1 + f/700)`. torchaudio's default; most PyTorch exports. */
    HTK,

    /** Linear below 1 kHz, logarithmic above. librosa's default (`htk=False`). */
    SLANEY
}

/**
 * What fills the space either side when [MelConfig.center] pads the signal.
 *
 * Worth an explicit choice because the reference implementations disagree *and*
 * one of them changed its mind: torchaudio pads by reflection, librosa's `stft`
 * defaulted to `'reflect'` and now defaults to `'constant'`. So "we used
 * librosa" does not settle it — the version matters, and the difference lands in
 * the first and last few frames of every single track.
 */
enum class PadMode {
    /** Mirror the signal, edge sample excluded. torchaudio's default. */
    REFLECT,

    /** Zeros. librosa's current `stft` default. */
    CONSTANT
}

/** How the power spectrogram is compressed before it reaches the model. */
sealed interface LogCompression {
    /** `ln(x + eps)`. */
    data class NaturalLog(val eps: Float = 1e-5f) : LogCompression

    /** `log10(x + eps)`. */
    data class Log10(val eps: Float = 1e-10f) : LogCompression

    /**
     * `10*log10(x/ref)`, floored at [topDb] below the peak — librosa's
     * `power_to_db`. [topDb] null disables the floor.
     */
    data class Decibels(val topDb: Float? = 80f, val ref: Ref = Ref.Max) : LogCompression {
        sealed interface Ref {
            /** Relative to the loudest bin, so the result is level-invariant. */
            object Max : Ref
            data class Fixed(val value: Float) : Ref
        }
    }

    /** Feed the model raw power. Rare, but some graphs log internally. */
    object None : LogCompression
}

/**
 * Every parameter of the spectrogram, all of which must match the model.
 *
 * This is the single highest-risk surface in the feature, and it fails
 * silently. Every field here has a plausible-looking default; a mismatch in any
 * one of them — 64 mels instead of 128, HTK instead of Slaney, natural log
 * instead of decibels, a hop of 320 instead of 512 — produces a tensor of
 * exactly the right shape full of numbers of exactly the right magnitude. The
 * model consumes it, returns a vector, the vector normalises, cosine similarity
 * computes, the radio plays. It just plays something unrelated to the seed, and
 * nothing anywhere reports an error.
 *
 * There is no defending against that in code. The defence is to read the
 * model's own preprocessing config and transcribe it here, and to check the
 * result against a reference implementation once — see the note on
 * `MelSpectrogramTest` for the cheap way to do that.
 */
data class MelConfig(
    val sampleRate: Int = 22_050,
    val nFft: Int = 1024,
    val hopLength: Int = 512,
    /** Window length, zero-padded up to [nFft] when shorter. */
    val winLength: Int = 1024,
    val nMels: Int = 128,
    val fMin: Float = 0f,
    /** Upper edge of the top filter. Null means Nyquist. */
    val fMax: Float? = null,
    /** 2.0 for a power spectrogram, 1.0 for magnitude. */
    val power: Float = 2.0f,
    /**
     * Pad by `nFft/2` either side so frame *t* is centred on sample
     * `t * hopLength`. librosa and torchaudio both do this by default, which
     * means a pipeline that does not is offset by half a window against the one
     * the model was trained on.
     */
    val center: Boolean = true,
    val padMode: PadMode = PadMode.REFLECT,
    val melScale: MelScale = MelScale.HTK,
    /**
     * Scale each filter by its bandwidth so filters carry equal area rather
     * than equal peak. librosa's `norm='slaney'`, on by default there; off in
     * torchaudio's `MelSpectrogram` unless asked for.
     */
    val slaneyNorm: Boolean = false,
    val logCompression: LogCompression = LogCompression.NaturalLog()
) {
    val effectiveFMax: Float get() = fMax ?: (sampleRate / 2f)

    /** Frames produced for [samples] input, matching librosa's arithmetic. */
    fun frameCount(samples: Int): Int {
        val padded = if (center) samples + 2 * (nFft / 2) else samples
        return if (padded < nFft) 0 else 1 + (padded - nFft) / hopLength
    }
}

/**
 * A computed spectrogram: [nMels] by [nFrames], row-major, mel-major.
 *
 * Flat rather than `Array<FloatArray>` because it exists to be handed to a
 * tensor. A jagged array would have to be copied and flattened on the way into
 * ONNX Runtime, on a hot path, for no benefit — [get] and [toArray2D] cover the
 * cases where a caller genuinely wants two dimensions.
 */
class MelSpectrogram(
    val nMels: Int,
    val nFrames: Int,
    val data: FloatArray
) {
    init {
        require(data.size == nMels * nFrames) {
            "expected ${nMels * nFrames} values, got ${data.size}"
        }
    }

    operator fun get(mel: Int, frame: Int): Float = data[mel * nFrames + frame]

    fun toArray2D(): Array<FloatArray> =
        Array(nMels) { m -> FloatArray(nFrames) { f -> data[m * nFrames + f] } }

    /**
     * Same content laid out frame-major, for the frame-major layouts.
     * A transpose, so it allocates; only sequence-first models pay for it.
     */
    fun transposed(): FloatArray {
        val out = FloatArray(data.size)
        for (m in 0 until nMels) {
            val src = m * nFrames
            for (f in 0 until nFrames) out[f * nMels + m] = data[src + f]
        }
        return out
    }

    /**
     * Pads with zeros or truncates to [frames], for fixed-shape graphs.
     * Returns `this` when the length already matches.
     */
    fun fitTo(frames: Int): MelSpectrogram {
        if (frames == nFrames) return this
        val out = FloatArray(nMels * frames)
        val copy = min(frames, nFrames)
        for (m in 0 until nMels) {
            System.arraycopy(data, m * nFrames, out, m * frames, copy)
        }
        return MelSpectrogram(nMels, frames, out)
    }
}

/**
 * Turns mono PCM into a log-mel spectrogram.
 *
 * Stateful and single-threaded by design: the window, the filterbank and every
 * scratch buffer are allocated once in the constructor and reused across frames
 * and across calls. Build one per worker thread; do not share one between
 * coroutines.
 */
class MelSpectrogramExtractor(val config: MelConfig) {

    private val fft = Fft(config.nFft)
    private val bins = config.nFft / 2 + 1

    /** Hann, periodic (`sym=False`) — what `scipy.signal.get_window` returns. */
    private val window = FloatArray(config.winLength) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / config.winLength)).toFloat()
    }

    /**
     * Triangular filterbank, stored sparsely.
     *
     * A dense `nMels x bins` matrix would be 128 x 513 floats, of which about
     * 98% are zero, and the multiply would spend nearly all its time adding
     * zero. Each filter keeps only its own span.
     */
    private val filterStart = IntArray(config.nMels)
    private val filterWeights = arrayOfNulls<FloatArray>(config.nMels)

    private val frameBuf = FloatArray(config.nFft)
    private val powerBuf = FloatArray(bins)
    private val scratchRe = FloatArray(config.nFft)
    private val scratchIm = FloatArray(config.nFft)

    init {
        buildFilterbank()
    }

    private fun hzToMel(hz: Float): Float = when (config.melScale) {
        MelScale.HTK -> 2595f * log10(1f + hz / 700f)
        MelScale.SLANEY -> {
            val fSp = 200f / 3f
            if (hz < 1000f) {
                hz / fSp
            } else {
                val minLogHz = 1000f
                val minLogMel = minLogHz / fSp
                val logStep = ln(6.4f) / 27f
                minLogMel + ln(hz / minLogHz) / logStep
            }
        }
    }

    private fun melToHz(mel: Float): Float = when (config.melScale) {
        MelScale.HTK -> 700f * (10f.pow(mel / 2595f) - 1f)
        MelScale.SLANEY -> {
            val fSp = 200f / 3f
            val minLogHz = 1000f
            val minLogMel = minLogHz / fSp
            if (mel < minLogMel) {
                mel * fSp
            } else {
                val logStep = ln(6.4f) / 27f
                minLogHz * exp(logStep * (mel - minLogMel))
            }
        }
    }

    private fun buildFilterbank() {
        val melMin = hzToMel(config.fMin)
        val melMax = hzToMel(config.effectiveFMax)
        // nMels + 2 edges: each filter spans from one edge to the one two along.
        val edges = FloatArray(config.nMels + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (config.nMels + 1))
        }
        val binHz = config.sampleRate.toFloat() / config.nFft

        for (m in 0 until config.nMels) {
            val left = edges[m]
            val centre = edges[m + 1]
            val right = edges[m + 2]

            var lo = Math.ceil((left / binHz).toDouble()).toInt().coerceAtLeast(0)
            var hi = Math.floor((right / binHz).toDouble()).toInt().coerceAtMost(bins - 1)
            if (hi < lo) {
                // A filter narrower than one bin. Keep it — dropping it would
                // shift every downstream index — but let it contribute nothing.
                lo = 0; hi = -1
            }

            val weights = FloatArray(max(0, hi - lo + 1))
            // Equal area rather than equal peak, when asked for.
            val norm = if (config.slaneyNorm) 2f / (right - left) else 1f
            for (b in lo..hi) {
                val hz = b * binHz
                val w = when {
                    hz < left || hz > right -> 0f
                    hz <= centre -> if (centre > left) (hz - left) / (centre - left) else 0f
                    else -> if (right > centre) (right - hz) / (right - centre) else 0f
                }
                weights[b - lo] = w * norm
            }
            filterStart[m] = lo
            filterWeights[m] = weights
        }
    }

    /**
     * @param pcm mono samples at [MelConfig.sampleRate], nominally in [-1, 1]
     */
    fun compute(pcm: FloatArray): MelSpectrogram {
        val padding = if (config.center) config.nFft / 2 else 0
        val padded = when {
            padding == 0 -> pcm
            config.padMode == PadMode.CONSTANT -> zeroPad(pcm, padding)
            else -> reflectPad(pcm, padding)
        }
        val frames = config.frameCount(pcm.size)
        if (frames <= 0) return MelSpectrogram(config.nMels, 0, FloatArray(0))

        val out = FloatArray(config.nMels * frames)
        val winOffset = (config.nFft - config.winLength) / 2

        for (f in 0 until frames) {
            val offset = f * config.hopLength
            java.util.Arrays.fill(frameBuf, 0f)
            for (i in 0 until config.winLength) {
                val idx = offset + i
                if (idx < padded.size) frameBuf[winOffset + i] = padded[idx] * window[i]
            }

            fft.powerSpectrum(frameBuf, powerBuf, scratchRe, scratchIm)

            // powerSpectrum gives magnitude squared; anything else is a power of it.
            if (config.power != 2.0f) {
                val e = config.power / 2.0f
                for (i in 0 until bins) powerBuf[i] = powerBuf[i].toDouble().pow(e.toDouble()).toFloat()
            }

            for (m in 0 until config.nMels) {
                val w = filterWeights[m] ?: continue
                val start = filterStart[m]
                var acc = 0f
                for (i in w.indices) acc += w[i] * powerBuf[start + i]
                out[m * frames + f] = acc
            }
        }

        applyCompression(out)
        return MelSpectrogram(config.nMels, frames, out)
    }

    private fun applyCompression(values: FloatArray) {
        when (val c = config.logCompression) {
            is LogCompression.None -> Unit
            is LogCompression.NaturalLog -> for (i in values.indices) {
                values[i] = ln(values[i] + c.eps)
            }
            is LogCompression.Log10 -> for (i in values.indices) {
                values[i] = log10(values[i] + c.eps)
            }
            is LogCompression.Decibels -> {
                val ref = when (val r = c.ref) {
                    is LogCompression.Decibels.Ref.Fixed -> abs(r.value)
                    LogCompression.Decibels.Ref.Max -> {
                        var peak = 0f
                        for (v in values) if (v > peak) peak = v
                        peak
                    }
                }
                val amin = 1e-10f
                val refSafe = max(amin, ref)
                var maxDb = Float.NEGATIVE_INFINITY
                for (i in values.indices) {
                    val db = 10f * log10(max(amin, values[i])) - 10f * log10(refSafe)
                    values[i] = db
                    if (db > maxDb) maxDb = db
                }
                val topDb = c.topDb
                if (topDb != null && maxDb.isFinite()) {
                    val floor = maxDb - topDb
                    for (i in values.indices) if (values[i] < floor) values[i] = floor
                }
            }
        }
    }

    private fun zeroPad(x: FloatArray, pad: Int): FloatArray {
        val out = FloatArray(x.size + 2 * pad)
        System.arraycopy(x, 0, out, pad, x.size)
        return out
    }

    /**
     * Mirrors the signal at both ends, excluding the edge sample.
     *
     * Removes the *step* at the boundary that zeros would create, which is what
     * smears broadband energy across the first and last frames. It does not make
     * the edge clean: mirroring is an even reflection, so a signal crossing zero
     * with a slope comes back with the slope reversed and leaves a cusp. The
     * first frame of a spectrogram is always somewhat suspect, in every
     * implementation — reflection makes it less wrong, not right.
     */
    private fun reflectPad(x: FloatArray, pad: Int): FloatArray {
        if (x.isEmpty()) return FloatArray(2 * pad)
        val out = FloatArray(x.size + 2 * pad)
        System.arraycopy(x, 0, out, pad, x.size)
        for (i in 0 until pad) {
            // Clamped so a signal shorter than the padding still behaves.
            out[pad - 1 - i] = x[min(i + 1, x.size - 1)]
            out[pad + x.size + i] = x[max(0, x.size - 2 - i)]
        }
        return out
    }
}

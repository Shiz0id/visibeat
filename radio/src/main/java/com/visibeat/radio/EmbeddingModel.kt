package com.visibeat.radio

import java.io.Closeable

/**
 * How a spectrogram is laid out when it reaches the model.
 *
 * Getting this wrong does not throw — ONNX Runtime will happily consume a
 * [1, 128, 1292] tensor where the graph wanted [1, 1, 128, 1292] if the total
 * element count matches, and returns a vector that is confidently meaningless.
 * It is an explicit choice for that reason.
 */
enum class TensorLayout {
    /** `[1, nMels, nFrames]` — most CLAP-family audio encoders. */
    BATCH_MEL_TIME,

    /** `[1, 1, nMels, nFrames]` — anything treating the spectrogram as an image. */
    BATCH_CHANNEL_MEL_TIME,

    /** `[1, nFrames, nMels]` — sequence-first encoders. */
    BATCH_TIME_MEL,

    /** `[1, 1, nFrames, nMels]` — what the Transformers.js CLAP export takes. */
    BATCH_CHANNEL_TIME_MEL
}

/**
 * Everything about the audio a model expects, in one place.
 *
 * This is the contract that has to match the model's *training* preprocessing,
 * not merely be internally consistent. See [MelConfig] for why that distinction
 * is the whole feature.
 */
data class AudioInputSpec(
    val mel: MelConfig = MelConfig(),

    /** Length of audio fed to the model, in seconds. */
    val windowSeconds: Float = 30f,

    /**
     * Where in the track the window starts, as a fraction of duration.
     *
     * A quarter in, by default. Tracks open with silence, fades, spoken intros
     * and applause far more often than they do at the 25% mark, and an
     * embedding of two seconds of room tone followed by 28 seconds of quiet is
     * a perfectly good embedding of the wrong thing.
     */
    val startFraction: Float = 0.25f,

    /**
     * Length of one segment actually fed to the model, in seconds.
     *
     * Null feeds the whole window at once. CLAP-family models were trained on
     * fixed ten-second excerpts, and the published inference recipe cuts the
     * audio into overlapping segments, embeds each, averages, and normalises.
     * Feeding thirty seconds to a graph expecting ten either fails on shape or,
     * worse, succeeds against a dynamic axis and returns something the model
     * never learned to produce.
     */
    val segmentSeconds: Float? = null,

    /** Overlap between consecutive segments, 0 to <1. 0.5 is CLAP's recipe. */
    val segmentOverlap: Float = 0f,

    /**
     * Round the float samples through int16 before the spectrogram.
     *
     * Not superstition. The reference pipelines decode to int16 and divide by
     * 32768, so the model was trained on quantised input; a float decode path
     * gives it slightly cleaner audio than it has ever seen. The difference is
     * tiny per sample and systematic across every track, which is the kind of
     * bias that shifts a whole embedding space rather than adding noise to it.
     */
    val quantizeToInt16: Boolean = false,

    val layout: TensorLayout = TensorLayout.BATCH_MEL_TIME,

    /**
     * Exact frame count the graph requires, or null if it accepts any length.
     *
     * Fixed-shape exports are common. Shorter input is zero-padded and longer
     * is truncated, both at the end.
     */
    val fixedFrames: Int? = null,

    /** Tensor input name in the graph. Null uses the graph's first input. */
    val inputName: String? = null,

    /** Tensor output name. Null uses the graph's first output. */
    val outputName: String? = null
)

/**
 * A model that turns a spectrogram into a vector.
 *
 * The only thing between the pipeline and ONNX Runtime. Everything upstream —
 * decoding, resampling, the mel transform — is driven by [spec], and everything
 * downstream is driven by [dimension], so replacing this with a different
 * export, a different architecture, or a stub for tests touches nothing else.
 */
interface EmbeddingModel : Closeable {

    /**
     * Stable identity of these weights, stored against every vector produced.
     *
     * Change it whenever the weights or the preprocessing change. Vectors from
     * two models share no space — the cosine between them is a number, not a
     * similarity — so this is what stops a model swap from silently degrading
     * the radio to shuffle. Include the preprocessing in the identity, not just
     * the checkpoint: the same weights fed 64-mel input are a different model.
     */
    val id: String

    /** Length of the returned vector. 128 and 512 are the usual cases. */
    val dimension: Int

    val spec: AudioInputSpec

    /**
     * Runs one spectrogram through the graph.
     *
     * Returns the raw model output. Normalisation is deliberately not done here
     * — [AudioEmbeddingEngine] owns it, so every vector in the database is
     * normalised by exactly one piece of code.
     *
     * Blocking. Callers are responsible for the dispatcher.
     */
    fun embed(mel: MelSpectrogram): FloatArray
}

/**
 * A model that can take several spectrograms at once.
 *
 * Optional. Batching helps on NNAPI and GPU execution providers, where per-call
 * overhead dominates for small graphs; on CPU it is usually a wash. The indexer
 * uses it when offered and falls back to a loop when not, so a model author
 * never has to implement it.
 */
interface BatchedEmbeddingModel : EmbeddingModel {
    fun embedBatch(mels: List<MelSpectrogram>): List<FloatArray>
}

/**
 * Audio that could not be turned into a spectrogram. Never fatal to a scan.
 *
 * @param transient the failure is about *now*, not about the file — a hardware
 *   decoder already in use, a resource the platform will hand over later. The
 *   distinction matters because the indexer records permanent failures so it
 *   stops retrying them, and recording a temporary one condemns a perfectly
 *   good track on the strength of a moment's contention.
 */
class AudioDecodeException(
    message: String,
    cause: Throwable? = null,
    val transient: Boolean = false
) : Exception(message, cause)

/** What came of trying to embed one track. */
sealed interface EmbedOutcome {
    data class Success(val vector: FloatArray) : EmbedOutcome

    /** The file cannot be read and will not start being readable. Record it. */
    object Unreadable : EmbedOutcome

    /** Temporarily unavailable. Leave no trace; try again on the next run. */
    object Busy : EmbedOutcome
}

/** The model loaded or ran but did not produce a usable vector. */
class EmbeddingException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

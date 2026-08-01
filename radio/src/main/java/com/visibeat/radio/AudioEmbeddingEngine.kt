package com.visibeat.radio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.sqrt

/**
 * Decode → spectrogram → model → unit vector, off the main thread.
 *
 * Holds the one expensive thing in the feature: a loaded model. Loading is tens
 * of megabytes of weights and a graph optimisation pass, so it happens once and
 * is shared.
 *
 * Not a Kotlin `object`. A process-wide singleton with a `Context` in it is the
 * shape that leaks, and it cannot be swapped in a test. The application graph
 * owns exactly one of these — the same arrangement the rest of this app already
 * uses for the database and the feeds.
 */
class AudioEmbeddingEngine(
    private val model: EmbeddingModel,
    private val windowReader: AudioWindowReader,
    /** Spectrograms and inference: arithmetic, so the compute pool. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Decoding: blocks on a hardware codec, so the IO pool. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Closeable {

    val modelId: String get() = model.id
    val dimension: Int get() = model.dimension

    /**
     * One extractor, reused.
     *
     * It holds the FFT tables, the filterbank and every scratch buffer, which
     * is the whole point of building it once — but that also makes it unsafe to
     * share between concurrent callers. [mutex] below serialises access rather
     * than the alternative of allocating a fresh 128x513 filterbank per track.
     */
    private val extractor = MelSpectrogramExtractor(model.spec.mel)

    /**
     * Serialises inference.
     *
     * ORT sessions tolerate concurrent `run` calls, but concurrency there is a
     * false economy: the session already parallelises internally across its
     * intra-op threads, so two coroutines calling in simply contend for the same
     * cores while doubling peak memory. One at a time, with ORT owning the
     * parallelism, is both faster and easier to reason about. It also makes the
     * shared [extractor] above safe.
     */
    private val mutex = Mutex()

    /**
     * Embeds one track.
     *
     * @return a unit-length vector, or null if the audio could not be read
     * @throws EmbeddingException if the model itself fails — unlike a bad file,
     *   that is not something to skip past, because it will fail for every
     *   other track too
     */
    suspend fun embedTrack(uriString: String, durationMs: Long?): EmbedOutcome {
        val pcm = withContext(ioDispatcher) {
            try {
                Result.success(windowReader.read(uriString, durationMs, model.spec))
            } catch (e: AudioDecodeException) {
                Result.failure(e)
            }
        }

        pcm.exceptionOrNull()?.let { failure ->
            val decode = failure as? AudioDecodeException
            return if (decode?.transient == true) EmbedOutcome.Busy else EmbedOutcome.Unreadable
        }

        return EmbedOutcome.Success(embedPcm(pcm.getOrThrow()))
    }

    /**
     * For callers that already have mono PCM at the model's rate.
     *
     * When the spec asks for segments, the window is cut into overlapping
     * excerpts, each is embedded, and the results are averaged before a single
     * normalisation. That order matters: normalising each segment first and then
     * averaging weights every segment equally regardless of how confident the
     * model was, which is not what the published recipe does.
     */
    suspend fun embedPcm(pcm: FloatArray): FloatArray = withContext(computeDispatcher) {
        mutex.withLock {
            val segments = segment(pcm, model.spec)
            if (segments.isEmpty()) throw EmbeddingException("no audio to embed")

            val sum = DoubleArray(model.dimension)
            for (segment in segments) {
                val mel = extractor.compute(segment).let { m ->
                    model.spec.fixedFrames?.let(m::fitTo) ?: m
                }
                val raw = try {
                    model.embed(mel)
                } catch (e: EmbeddingException) {
                    throw e
                } catch (t: Throwable) {
                    throw EmbeddingException("inference failed for ${model.id}", t)
                }
                if (raw.size != model.dimension) {
                    throw EmbeddingException(
                        "${model.id} returned ${raw.size} values, expected ${model.dimension}"
                    )
                }
                for (i in raw.indices) sum[i] += raw[i].toDouble()
            }

            val mean = FloatArray(model.dimension) { (sum[it] / segments.size).toFloat() }
            l2Normalize(mean)
        }
    }

    /**
     * Cuts the analysis window into the excerpts the model was trained on.
     *
     * A short tail is kept rather than dropped — a track that yields two and a
     * half segments should contribute all of what it has, and the extractor
     * pads to [AudioInputSpec.fixedFrames] anyway.
     */
    private fun segment(pcm: FloatArray, spec: AudioInputSpec): List<FloatArray> {
        val seconds = spec.segmentSeconds ?: return listOf(pcm)
        val length = (seconds * spec.mel.sampleRate).toInt()
        if (length <= 0 || pcm.size <= length) return listOf(pcm)

        val step = (length * (1f - spec.segmentOverlap.coerceIn(0f, 0.95f))).toInt()
            .coerceAtLeast(1)
        val out = ArrayList<FloatArray>()
        var start = 0
        while (start < pcm.size) {
            val end = minOf(start + length, pcm.size)
            // Anything shorter than a third of a segment is a scrap, not a slice.
            if (end - start < length / 3 && out.isNotEmpty()) break
            out += pcm.copyOfRange(start, end)
            if (end == pcm.size) break
            start += step
        }
        return out
    }

    override fun close() {
        model.close()
    }

    companion object {
        /**
         * Scales to unit length, in place, and returns the same array.
         *
         * Done once here so that cosine similarity downstream is a bare dot
         * product. A zero vector is left alone rather than turned into NaN —
         * silence embeds to something near zero often enough to matter, and one
         * NaN in the index poisons every comparison against it.
         */
        fun l2Normalize(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x.toDouble() * x
            val norm = sqrt(sum)
            if (norm > 1e-12) {
                val inv = (1.0 / norm).toFloat()
                for (i in v.indices) v[i] *= inv
            }
            return v
        }
    }
}

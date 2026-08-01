package com.visibeat.radio.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.visibeat.radio.AudioInputSpec
import com.visibeat.radio.BatchedEmbeddingModel
import com.visibeat.radio.EmbeddingException
import com.visibeat.radio.MelSpectrogram
import com.visibeat.radio.TensorLayout
import java.nio.FloatBuffer

/**
 * An [com.visibeat.radio.EmbeddingModel] backed by ONNX Runtime.
 *
 * The only file in the project that knows ONNX exists. Everything else —
 * decoding, spectrograms, the vector index, the queue rules — is written
 * against the interface, so replacing this with TFLite, ExecuTorch or a
 * different export is a one-class change and touches no callers.
 *
 * @param spec must describe how this checkpoint was *trained*, not merely
 *   something self-consistent. See MelConfig for why that is the whole game.
 */
class OnnxEmbeddingModel private constructor(
    source: ModelSource,
    override val id: String,
    override val dimension: Int,
    override val spec: AudioInputSpec,
    threads: Int = DEFAULT_THREADS
) : BatchedEmbeddingModel {

    /** Where the graph comes from. See the factories on the companion. */
    sealed interface ModelSource {
        /** The whole graph in one buffer. Fine for self-contained exports. */
        class Bytes(val value: ByteArray) : ModelSource

        /**
         * A path on disk, for graphs with external weights.
         *
         * ONNX stores tensors outside the `.onnx` when a graph is large or when
         * the exporter simply chose to — AudioMuse's DCLAP ships
         * `model_epoch_36.onnx` next to `model_epoch_36.onnx.data`. The `.onnx`
         * holds only a *relative filename* pointing at the other file, which ORT
         * resolves against the model's own directory. Load such a graph from a
         * byte array and it constructs, reports its inputs and outputs correctly,
         * and then fails at run time or returns nothing meaningful, because the
         * weights were never there. Assets have no directory to resolve against,
         * so the pair has to be staged to real files first.
         */
        class Path(val value: String) : ModelSource
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        session = try {
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // NNAPI is deliberately not enabled. On a graph this size it
                // frequently loses to CPU — the driver partitions around any
                // unsupported operator and pays a copy at every boundary — and
                // when it does run, some vendor implementations return subtly
                // different values, which is indistinguishable from a broken
                // index. Enable it only after checking both the timing and the
                // vectors against the CPU provider on real hardware.
            }
            when (source) {
                is ModelSource.Bytes -> env.createSession(source.value, options)
                is ModelSource.Path -> env.createSession(source.value, options)
            }
        } catch (t: Throwable) {
            throw EmbeddingException("cannot create ORT session for $id", t)
        }

        val inputCount = session.inputNames.size
        if (inputCount != 1 && spec.inputName == null) {
            throw EmbeddingException(
                "graph has $inputCount inputs; set AudioInputSpec.inputName to pick one"
            )
        }
    }

    private val inputName: String = spec.inputName ?: session.inputNames.first()
    private val outputName: String = spec.outputName ?: session.outputNames.first()

    override fun embed(mel: MelSpectrogram): FloatArray = embedBatch(listOf(mel)).first()

    override fun embedBatch(mels: List<MelSpectrogram>): List<FloatArray> {
        if (mels.isEmpty()) return emptyList()
        val frames = mels.first().nFrames
        val nMels = mels.first().nMels
        require(mels.all { it.nFrames == frames && it.nMels == nMels }) {
            "a batch must be uniform; use AudioInputSpec.fixedFrames to pad"
        }

        val shape = shapeFor(mels.size, nMels, frames)
        val buffer = FloatBuffer.allocate(mels.size * nMels * frames)
        for (m in mels) {
            buffer.put(
                when (spec.layout) {
                    TensorLayout.BATCH_TIME_MEL,
                    TensorLayout.BATCH_CHANNEL_TIME_MEL -> m.transposed()
                    else -> m.data
                }
            )
        }
        buffer.rewind()

        return try {
            OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { results ->
                    val value = results.get(outputName).orElseThrow {
                        EmbeddingException("no output '$outputName'")
                    }
                    readVectors(value.value, mels.size)
                }
            }
        } catch (e: EmbeddingException) {
            throw e
        } catch (t: Throwable) {
            throw EmbeddingException("ORT run failed for $id", t)
        }
    }

    private fun shapeFor(batch: Int, nMels: Int, frames: Int): LongArray =
        when (spec.layout) {
            TensorLayout.BATCH_MEL_TIME -> longArrayOf(batch.toLong(), nMels.toLong(), frames.toLong())
            TensorLayout.BATCH_CHANNEL_MEL_TIME ->
                longArrayOf(batch.toLong(), 1L, nMels.toLong(), frames.toLong())
            TensorLayout.BATCH_TIME_MEL -> longArrayOf(batch.toLong(), frames.toLong(), nMels.toLong())
            TensorLayout.BATCH_CHANNEL_TIME_MEL ->
                longArrayOf(batch.toLong(), 1L, frames.toLong(), nMels.toLong())
        }

    /**
     * Unwraps the output, which ORT hands back as nested Java arrays.
     *
     * Both shapes appear in the wild: `[batch, dim]` from a graph that kept its
     * batch axis, and a bare `[dim]` from one exported with a fixed batch of 1.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readVectors(value: Any?, batch: Int): List<FloatArray> = when (value) {
        is Array<*> -> {
            val rows = value as Array<FloatArray>
            if (rows.size != batch) {
                throw EmbeddingException("expected $batch vectors, got ${rows.size}")
            }
            rows.map { it.copyOf() }
        }
        is FloatArray -> {
            if (batch != 1) {
                throw EmbeddingException("graph returned one vector for a batch of $batch")
            }
            listOf(value.copyOf())
        }
        else -> throw EmbeddingException("unexpected output type ${value?.javaClass}")
    }

    override fun close() {
        runCatching { session.close() }
        // The environment is a process-wide singleton owned by ORT. Closing it
        // here would tear it down for anything else that ever creates a session.
    }

    companion object {
        /**
         * Two threads, not all of them.
         *
         * This runs while music is playing. Saturating every core makes the
         * indexer finish sooner and makes the app stutter doing it, which is the
         * wrong trade for work the user never asked to happen now.
         */
        private val DEFAULT_THREADS =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)

        /** A single self-contained `.onnx` in `assets/`. */
        fun fromAsset(
            context: Context,
            assetName: String,
            id: String,
            dimension: Int,
            spec: AudioInputSpec
        ): OnnxEmbeddingModel {
            val bytes = try {
                context.assets.open(assetName).use { it.readBytes() }
            } catch (t: Throwable) {
                throw EmbeddingException("cannot read model asset '$assetName'", t)
            }
            return OnnxEmbeddingModel(ModelSource.Bytes(bytes), id, dimension, spec)
        }

        /**
         * A graph plus its external weight files, staged out of `assets/`.
         *
         * Copies once into `filesDir/models/<id>/` and reuses it after that, so
         * the cost is paid on first launch rather than on every model load. Both
         * files must keep the names the graph refers to — the reference inside
         * the `.onnx` is a literal filename.
         *
         * @param assetNames the graph first, then every external data file
         */
        fun fromAssetsWithExternalData(
            context: Context,
            assetNames: List<String>,
            id: String,
            dimension: Int,
            spec: AudioInputSpec
        ): OnnxEmbeddingModel {
            require(assetNames.isNotEmpty()) { "need at least the graph" }
            val dir = java.io.File(context.filesDir, "models/${id.replace('/', '_')}")
            if (!dir.exists() && !dir.mkdirs()) {
                throw EmbeddingException("cannot create model directory ${dir.absolutePath}")
            }

            assetNames.forEach { name ->
                val target = java.io.File(dir, name.substringAfterLast('/'))
                // Size is a good enough staleness check: these files are
                // immutable per release, and hashing 25 MB on every cold start
                // to learn nothing is not worth the milliseconds.
                val expected = runCatching {
                    context.assets.openFd(name).use { it.length }
                }.getOrDefault(-1L)
                if (target.exists() && (expected < 0 || target.length() == expected)) return@forEach

                try {
                    context.assets.open(name).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (t: Throwable) {
                    throw EmbeddingException("cannot stage model asset '$name'", t)
                }
            }

            val graph = java.io.File(dir, assetNames.first().substringAfterLast('/'))
            return OnnxEmbeddingModel(
                ModelSource.Path(graph.absolutePath), id, dimension, spec
            )
        }
    }
}

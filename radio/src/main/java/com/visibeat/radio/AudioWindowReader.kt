package com.visibeat.radio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.visibeat.radio.dsp.Resampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Pulls a fixed window of mono PCM out of a local track.
 *
 * MediaExtractor and MediaCodec rather than a library: they are already on the
 * device, they handle every container and codec the platform's own player
 * handles, and adding a decoder would mean shipping one that handles fewer.
 *
 * The result is always mono, always at the requested rate, and always exactly
 * the requested length — padded with silence if the file is shorter, so the
 * spectrogram downstream has a predictable frame count.
 */
class AudioWindowReader(private val context: Context) {

    /**
     * @param uriString MediaStore content URI or SAF document URI, as stored on
     *   the track row — both work, [MediaExtractor.setDataSource] takes either
     * @param durationMs from the library if known; the container is consulted
     *   when it is not
     * @throws AudioDecodeException for anything unreadable. Callers scanning a
     *   library are expected to catch this per track and carry on: a corrupt
     *   file, a DRM-wrapped file or a format with no decoder on this device are
     *   all normal, and none of them should stop an index run.
     */
    fun read(
        uriString: String,
        durationMs: Long?,
        spec: AudioInputSpec
    ): FloatArray {
        val targetRate = spec.mel.sampleRate
        val targetSamples = (spec.windowSeconds * targetRate).toInt()

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, Uri.parse(uriString), null)
            } catch (t: Throwable) {
                throw AudioDecodeException("cannot open $uriString", t)
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("no audio track in $uriString")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw AudioDecodeException("no mime for $uriString")
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val totalUs = durationMs?.takeIf { it > 0 }?.times(1000)
                ?: format.runCatching { getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

            // Start a quarter in, but never so late that the window runs off the
            // end — on a two-minute track a 30-second window from 25% is fine,
            // on a 35-second one it is not.
            val windowUs = (spec.windowSeconds * 1_000_000L).toLong()
            val startUs = if (totalUs > windowUs) {
                ((totalUs * spec.startFraction).toLong()).coerceAtMost(totalUs - windowUs)
            } else {
                0L
            }.coerceAtLeast(0L)

            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: MediaCodec.CodecException) {
                throw e.asDecodeException("cannot create decoder for $mime")
            } catch (t: Throwable) {
                // No such codec on this device. That will still be true tomorrow.
                throw AudioDecodeException("no decoder for $mime", t)
            }
            codec = decoder
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
            } catch (e: MediaCodec.CodecException) {
                throw e.asDecodeException("cannot start decoder for $mime")
            }

            // Decode at the source rate; resample once at the end, where the
            // filter can see the whole signal.
            val wanted = ((spec.windowSeconds + 0.5f) * sourceRate).toInt()
            val mono = decodeMono(extractor, decoder, channels, wanted, startUs)

            // A decode that produced nothing must not become a valid-looking
            // vector. `fit` pads with silence, so a codec that yielded zero
            // samples — a bad seek, an unsupported profile, a truncated file —
            // hands back a full-length buffer of zeros. That is a perfectly
            // good spectrogram of silence; the model embeds it, the vector
            // normalises, a row is written, and the track counts as analysed.
            // Every such track lands in the same corner of the space, so they
            // become each other's nearest neighbours and the radio plays a set
            // of files it could not actually read.
            if (peak(mono) < SILENCE_FLOOR) {
                throw AudioDecodeException("decoded to silence: $uriString")
            }

            val resampled = Resampler.resample(mono, sourceRate, targetRate)
            val fitted = fit(resampled, targetSamples)
            return if (spec.quantizeToInt16) quantize(fitted) else fitted
        } catch (e: AudioDecodeException) {
            throw e
        } catch (e: MediaCodec.CodecException) {
            throw e.asDecodeException("decode failed for $uriString")
        } catch (t: Throwable) {
            throw AudioDecodeException("decode failed for $uriString", t)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Synchronous decode loop, stopping once [wanted] mono samples are in hand.
     *
     * Synchronous rather than callback mode on purpose: this runs on a worker
     * with nothing else to do, and the async API would add a handler thread and
     * a completion latch per track for no gain.
     */
    private fun decodeMono(
        extractor: MediaExtractor,
        codec: MediaCodec,
        channels: Int,
        wanted: Int,
        startUs: Long
    ): FloatArray {
        val out = FloatArray(wanted)
        var written = 0
        var sawInputEnd = false
        var sawOutputEnd = false
        val info = MediaCodec.BufferInfo()
        // The seek lands on the nearest sync frame, which can be earlier than
        // asked. Samples before the target are decoded and dropped.
        var dropUs = startUs

        var pcmEncoding = ENCODING_PCM_16BIT
        var outChannels = channels

        while (!sawOutputEnd && written < wanted) {
            if (!sawInputEnd) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    outChannels = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                        .getOrDefault(channels)
                    pcmEncoding = runCatching { f.getInteger(KEY_PCM_ENCODING) }
                        .getOrDefault(ENCODING_PCM_16BIT)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> {
                    if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            if (info.presentationTimeUs + FRAME_SLACK_US >= dropUs) {
                                written += appendMono(
                                    buffer, pcmEncoding, outChannels, out, written
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEnd = true
                        }
                    }
                }
            }
        }

        return if (written == wanted) out else out.copyOf(written)
    }

    /**
     * Downmixes one output buffer into [dest] and returns how many frames landed.
     *
     * Handles both 16-bit and float output: [MediaFormat.KEY_PCM_ENCODING] is
     * only advertised from API 24 and some decoders emit float, so assuming
     * 16-bit reads float samples as pairs of garbage integers.
     */
    private fun appendMono(
        buffer: ByteBuffer,
        pcmEncoding: Int,
        channels: Int,
        dest: FloatArray,
        offset: Int
    ): Int {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val ch = channels.coerceAtLeast(1)
        val written: Int

        if (pcmEncoding == ENCODING_PCM_24BIT_PACKED || pcmEncoding == ENCODING_PCM_32BIT) {
            // Hi-res output. Not a curiosity: 24-bit is what a decoder hands
            // back for the 96 kHz FLAC that a well-stocked library is full of,
            // and reading three-byte samples as two-byte shorts does not fail —
            // it produces loud, structured noise that embeds perfectly happily
            // into a vector describing nothing.
            val bytesPerSample = if (pcmEncoding == ENCODING_PCM_32BIT) 4 else 3
            val stride = bytesPerSample * ch
            val frames = min(buffer.remaining() / stride, dest.size - offset)
            val base = buffer.position()
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until ch) {
                    val at = base + i * stride + c * bytesPerSample
                    // Little-endian, sign carried by the top byte. Both encodings
                    // are read from their most significant three bytes, so the
                    // scale is the same and 32-bit simply discards its low byte.
                    val hi = buffer.get(at + bytesPerSample - 1).toInt()
                    val mid = buffer.get(at + bytesPerSample - 2).toInt() and 0xFF
                    val lo = buffer.get(at + bytesPerSample - 3).toInt() and 0xFF
                    acc += ((hi shl 16) or (mid shl 8) or lo) / 8388608f
                }
                dest[offset + i] = acc / ch
            }
            written = frames
        } else if (pcmEncoding == ENCODING_PCM_FLOAT) {
            val fb = buffer.asFloatBuffer()
            val frames = min(fb.remaining() / ch, dest.size - offset)
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until ch) acc += fb.get(i * ch + c)
                dest[offset + i] = acc / ch
            }
            written = frames
        } else if (pcmEncoding == ENCODING_PCM_16BIT) {
            val sb = buffer.asShortBuffer()
            val frames = min(sb.remaining() / ch, dest.size - offset)
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until ch) acc += sb.get(i * ch + c) / 32768f
                dest[offset + i] = acc / ch
            }
            written = frames
        } else {
            // Better to skip the track than to embed a misinterpretation of it.
            throw AudioDecodeException("unsupported pcm encoding $pcmEncoding")
        }
        return written
    }

    /**
     * Rounds through 16-bit, matching what the reference pipeline sees.
     *
     * A float decode path hands the model marginally cleaner audio than it was
     * ever trained on. Per sample that is nothing; across a whole library it is
     * a systematic offset in one direction, which moves the embedding space
     * rather than adding noise to it.
     */
    private fun quantize(x: FloatArray): FloatArray {
        for (i in x.indices) {
            val clamped = x[i].coerceIn(-1f, 1f)
            x[i] = Math.round(clamped * 32767f).toShort() / 32768f
        }
        return x
    }

    /**
     * Reads the platform's own verdict on whether this is worth retrying.
     *
     * `isTransient` means the codec is busy — very often the music player
     * holding the one hardware instance this device has for the format the
     * indexer just reached. `isRecoverable` means the codec needs resetting,
     * which is equally not the file's fault.
     */
    private fun MediaCodec.CodecException.asDecodeException(
        message: String
    ): AudioDecodeException = AudioDecodeException(
        message = "$message (diagnostic ${diagnosticInfo})",
        cause = this,
        transient = isTransient || isRecoverable
    )

    private fun peak(x: FloatArray): Float {
        var p = 0f
        for (v in x) {
            val a = if (v < 0f) -v else v
            if (a > p) p = a
        }
        return p
    }

    /** Pads with silence or truncates, so the frame count is never a surprise. */
    private fun fit(x: FloatArray, length: Int): FloatArray {
        if (x.size == length) return x
        val out = FloatArray(length)
        System.arraycopy(x, 0, out, 0, min(x.size, length))
        return out
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        /** Tolerance when discarding pre-seek frames, about one AAC frame. */
        const val FRAME_SLACK_US = 25_000L
        const val KEY_PCM_ENCODING = "pcm-encoding"
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
        /** API 31+. Three bytes per sample, little-endian, signed. */
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22

        /**
         * Below this peak amplitude the window carries no signal.
         *
         * About -80 dBFS. Deliberately not exactly zero: a codec can emit a
         * handful of dither-level samples and still have decoded nothing
         * useful, and no real recording is this quiet for thirty seconds.
         */
        const val SILENCE_FLOOR = 1e-4f
    }
}

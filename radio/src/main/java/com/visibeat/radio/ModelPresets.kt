package com.visibeat.radio

/**
 * Preprocessing transcribed from each model's own published configuration.
 *
 * Not defaults, not "sensible values" — transcriptions. Every number below was
 * read off the model's config and is wrong for any other model, including close
 * relatives. DCLAP is a distillation of LAION CLAP into the same 512-dimensional
 * space, and it still disagrees with its teacher on the number of mel bands, the
 * FFT size and the lower frequency bound. Nothing detects that; both configs
 * produce a tensor the other model will happily consume.
 *
 * Sources are named against each preset so they can be re-checked when a model
 * is updated.
 */
object ModelPresets {

    /**
     * AudioMuse-AI Distilled CLAP.
     *
     * github.com/NeptuneHub/AudioMuse-AI-DCLAP — a ~7M-parameter student
     * distilled from LAION CLAP's ~80M audio tower, shipping as ONNX with the
     * teacher's unmodified text encoder alongside it.
     *
     * Three things about this preset are not guesses and matter:
     *
     *  - `n_mels = 128`, `n_fft = 2048`, `fMin = 0`. All three differ from the
     *    HuggingFace CLAP feature extractor. Using [LAION_CLAP] here silently
     *    produces garbage.
     *  - The log step is `librosa.power_to_db(ref=1.0, amin=1e-10, top_db=None)`
     *    — a *fixed* reference, not peak-relative, and no floor. Peak-relative
     *    would make every embedding level-invariant, which is not what this was
     *    trained on.
     *  - Audio is embedded as 10-second segments at 50% overlap, averaged, then
     *    normalised. Not one long window. See [AudioInputSpec.segmentSeconds].
     */
    val DCLAP = AudioInputSpec(
        mel = MelConfig(
            sampleRate = 48_000,
            nFft = 2048,
            hopLength = 480,
            winLength = 2048,
            nMels = 128,
            fMin = 0f,
            fMax = 14_000f,
            power = 2.0f,
            center = true,
            padMode = PadMode.REFLECT,
            melScale = MelScale.SLANEY,
            slaneyNorm = true,
            logCompression = LogCompression.Decibels(
                topDb = null,
                ref = LogCompression.Decibels.Ref.Fixed(1.0f)
            )
        ),
        // Analysis window, from which segments are cut. Long enough to cover a
        // verse and a chorus rather than betting everything on one ten-second
        // slice of whatever happens to be at the 25% mark.
        windowSeconds = 30f,
        startFraction = 0.25f,
        segmentSeconds = 10f,
        segmentOverlap = 0.5f,
        layout = TensorLayout.BATCH_CHANNEL_MEL_TIME,
        inputName = "mel_spectrogram",
        // 10 s at 48 kHz with hop 480 and centre padding.
        fixedFrames = 1001,
        quantizeToInt16 = true
    )

    /** DCLAP's identity. Bump the suffix whenever this preset changes. */
    const val DCLAP_ID = "audiomuse-dclap-e36/mel128-nfft2048-db"
    const val DCLAP_DIM = 512

    /**
     * LAION CLAP, the teacher, run directly.
     *
     * Transcribed from `laion/clap-htsat-unfused`'s `preprocessor_config.json`,
     * which is what the HuggingFace `ClapFeatureExtractor` — and therefore
     * Xenova's ONNX export — actually applies.
     *
     * The fallback if DCLAP's licence does not work out: the base weights are
     * CC0, and the audio tower exports to about 34 MB quantised. Slower and
     * larger than DCLAP for the same 512 dimensions, but with no licence
     * question attached to it at all.
     */
    val LAION_CLAP = AudioInputSpec(
        mel = MelConfig(
            sampleRate = 48_000,
            nFft = 1024,
            hopLength = 480,
            winLength = 1024,
            nMels = 64,
            fMin = 50f,
            fMax = 14_000f,
            power = 2.0f,
            center = true,
            padMode = PadMode.REFLECT,
            melScale = MelScale.SLANEY,
            slaneyNorm = true,
            logCompression = LogCompression.Decibels(
                topDb = null,
                ref = LogCompression.Decibels.Ref.Fixed(1.0f)
            )
        ),
        windowSeconds = 30f,
        startFraction = 0.25f,
        segmentSeconds = 10f,
        segmentOverlap = 0.5f,
        // Transformers.js feeds [batch, 1, frames, mels] — frame-major, and the
        // opposite of DCLAP. Verify against the exported graph before trusting it.
        layout = TensorLayout.BATCH_CHANNEL_TIME_MEL,
        inputName = "input_features",
        fixedFrames = 1001,
        quantizeToInt16 = true
    )

    const val LAION_CLAP_ID = "laion-clap-htsat-unfused/mel64-nfft1024-db"
    const val LAION_CLAP_DIM = 512
}

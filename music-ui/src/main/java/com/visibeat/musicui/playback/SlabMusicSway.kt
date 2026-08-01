package com.visibeat.musicui.playback

/**
 * Turns the visualiser's band levels into a gentle rotation for the cube.
 *
 * Feeds the same additive rotation channel the drag gesture uses, so the two
 * simply sum: you can hold the cube turned and still feel the music moving it.
 *
 * The cube already reacted to audio before this, but only through a single bass
 * scalar applied to both axes at once — which pumps the cube along one diagonal.
 * A pulse, not a rotation. The trick to making it read as *turning* is driving
 * the two axes from two different things:
 *
 *  - **yaw** from the balance between treble and bass, so a bright passage leans
 *    one way and a bass-heavy one leans the other
 *  - **pitch** from how present the mid-band is against the whole spectrum
 *
 * Because those move semi-independently the cube traces a small wandering figure
 * instead of oscillating on a line.
 *
 * All the inputs are the per-band levels the slab already computes for the
 * etched visualiser bars, each independently peak-normalised — so this measures
 * *spectral contrast*, not loudness, and does not need its own FFT pass or its
 * own normalisers.
 *
 * One consequence worth knowing: a perfectly flat spectrum produces no sway at
 * all, because there is no contrast to read. That is the honest behaviour — if
 * the levels ever arrive flat or empty, the cube falls back to its idle drift
 * rather than pretending to respond.
 */
internal object SlabMusicSway {

    /** Left/right turn at full spectral contrast. */
    const val MAX_YAW_DEG = 10f

    /** Up/down nod at full mid-band prominence. */
    const val MAX_PITCH_DEG = 6f

    // Band split, as fractions of the spectrum. The bars are log-spaced, so
    // these are roughly bass / mids / highs by ear rather than by frequency.
    private const val LOW_END = 0.30f
    private const val MID_END = 0.65f

    /**
     * Mean level across a fraction of the spectrum.
     *
     * Always covers at least one band, so a narrow window on a short array
     * cannot divide by zero.
     */
    fun bandMean(levels: FloatArray, fromFraction: Float, toFraction: Float): Float {
        if (levels.isEmpty()) return 0f
        val size = levels.size
        val start = (fromFraction * size).toInt().coerceIn(0, size - 1)
        val end = (toFraction * size).toInt().coerceIn(start + 1, size)
        var sum = 0f
        for (i in start until end) sum += levels[i]
        return sum / (end - start)
    }

    /**
     * Left/right rotation. Positive when the mix is bright, negative when it is
     * bass-heavy, zero when the spectrum is flat.
     */
    fun yawDegrees(levels: FloatArray, maxDeg: Float = MAX_YAW_DEG): Float {
        if (levels.isEmpty()) return 0f
        val low = bandMean(levels, 0f, LOW_END)
        val high = bandMean(levels, MID_END, 1f)
        return (high - low).coerceIn(-1f, 1f) * maxDeg
    }

    /**
     * Up/down rotation, from how far the mid-band stands out from the whole
     * spectrum. Vocals and guitars sitting forward in a mix nod the cube up.
     */
    fun pitchDegrees(levels: FloatArray, maxDeg: Float = MAX_PITCH_DEG): Float {
        if (levels.isEmpty()) return 0f
        val mid = bandMean(levels, LOW_END, MID_END)
        val whole = bandMean(levels, 0f, 1f)
        return (mid - whole).coerceIn(-1f, 1f) * maxDeg
    }
}

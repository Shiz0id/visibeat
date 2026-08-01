package com.visibeat.musicui.playback

/**
 * Converts finger travel into cube rotation for the now-playing slab.
 *
 * Lives outside `NowPlayingSlab` so the composable's own edit stays to a handful
 * of lines, and so the arithmetic can be tested without a renderer.
 *
 * The offsets produced here are *added* to the pose the slab already animates
 * from bass energy — the cube keeps pulsing while you hold it turned, rather
 * than the drag taking over.
 */
internal object SlabDragRotation {

    /**
     * Degrees of rotation per pixel of drag.
     *
     * At this rate a ~70px drag reaches the limit, so the whole range is inside
     * a comfortable thumb movement.
     */
    const val DEGREES_PER_PX = 0.30f

    /**
     * How far the cube can be turned from its resting pose.
     *
     * Kept small deliberately. The material's gloss, rim light and side-face
     * shading were tuned around the base tilt, and the further the cube turns
     * the further those are from the look they were built for. Twenty degrees is
     * enough to feel like you are handling an object and not enough to expose a
     * face nobody art-directed.
     */
    const val MAX_DEGREES = 20f

    /**
     * Adds one drag delta to an accumulated angle, clamped to [limit].
     *
     * Clamping the accumulated value rather than the delta means dragging past
     * the limit and back responds immediately, instead of having to unwind
     * invisible slack first.
     */
    fun accumulate(
        current: Float,
        deltaPx: Float,
        degreesPerPx: Float = DEGREES_PER_PX,
        limit: Float = MAX_DEGREES
    ): Float = (current + deltaPx * degreesPerPx).coerceIn(-limit, limit)
}

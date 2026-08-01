package com.visibeat.musicui.playback

/**
 * Decides which audio session the visualiser should capture, and when an
 * already-attached Visualizer has to be torn down and re-attached.
 *
 * This is where the cube's motion was lost. `Visualizer(0)` captures the global
 * output mix, which ordinary apps may not read on Android 10+ — the object
 * constructs without complaint and then delivers nothing but zeroes. The old
 * code read the session id once, at the instant playback started, before the
 * service had published a real one; it bound to 0, and every later chance to
 * rebind was gated behind `visualizer == null`. So the app captured silence for
 * the rest of the session, bass energy stayed pinned at zero, and the cube
 * settled into its base pose and stopped.
 *
 * Pulled out of PlaybackManager so the rules can be tested without an audio
 * session, a media session, or a device.
 */
internal object VisualizerBinding {

    /** No usable session id. Attaching here means capturing the output mix. */
    const val OUTPUT_MIX = 0

    /**
     * The session the visualiser should be capturing: the first real id on
     * offer, or the output mix when every source has come up empty.
     *
     * Sources are passed in priority order, most trustworthy first. In practice
     * that is the playback service's own field, then the session extras, then
     * whatever a Player listener reported — the last of which a MediaController
     * never actually receives, and only guards against a future media3 that
     * does forward it.
     */
    fun preferredSessionId(sourcesInPriorityOrder: List<Int>): Int =
        sourcesInPriorityOrder.firstOrNull { it != OUTPUT_MIX } ?: OUTPUT_MIX

    /**
     * Whether the currently attached Visualizer has to be replaced.
     *
     * @param boundSessionId the session the live Visualizer is on, or null if
     *   there is no Visualizer yet
     */
    fun shouldRebind(boundSessionId: Int?, preferredSessionId: Int): Boolean = when {
        // Nothing attached — always (re)bind.
        boundSessionId == null -> true
        // Already on the right stream.
        boundSessionId == preferredSessionId -> false
        // On the output mix with nothing better available: keep what we have
        // rather than churning through teardowns that land in the same place.
        preferredSessionId == OUTPUT_MIX -> false
        // A real session id has since become available. This is the case the
        // alpha never handled.
        else -> true
    }

    /**
     * Session ids to attempt, best first. The output mix is always last: it is a
     * fallback that may silently yield silence, never a first choice.
     */
    fun candidates(preferredSessionId: Int): List<Int> =
        if (preferredSessionId == OUTPUT_MIX) listOf(OUTPUT_MIX)
        else listOf(preferredSessionId, OUTPUT_MIX)
}

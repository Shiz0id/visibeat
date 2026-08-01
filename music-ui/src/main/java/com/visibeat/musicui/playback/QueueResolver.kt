package com.visibeat.musicui.playback

import com.visibeat.viewengine.TimelineItemRow

/**
 * Turns a screen's list of rows into a queue the player can actually hold.
 *
 * Not every row is playable: a track can resolve into the library with no
 * MediaStore URI (dismissed, moved, or ingested from a source that never gave
 * one). Leaving those in the playlist would make ExoPlayer stall on an
 * unplayable item, but dropping them naively shifts every index after them — so
 * tapping the fifth row would start the fourth track.
 *
 * Pulled out of PlaybackManager so this can be tested without a media session.
 */
internal object QueueResolver {

    /**
     * @param tracks the list exactly as the screen displays it
     * @param startIndex the index the user tapped, in that displayed list
     * @return the playable subset paired with the index of the tapped track
     *   inside it, or null when nothing in the list can be played
     */
    fun resolve(tracks: List<TimelineItemRow>, startIndex: Int): Pair<List<TimelineItemRow>, Int>? {
        val requested = tracks.getOrNull(startIndex)
        val playable = tracks.filter { !it.mediaStoreUri.isNullOrBlank() }
        if (playable.isEmpty()) return null

        // Re-find the tapped track by identity rather than by position.
        val resolvedIndex = requested
            ?.let { r -> playable.indexOfFirst { it.trackId == r.trackId } }
            ?.takeIf { it >= 0 }
            ?: 0

        return playable to resolvedIndex
    }
}

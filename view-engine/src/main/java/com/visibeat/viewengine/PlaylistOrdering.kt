package com.visibeat.viewengine

/** How the playlist list is ordered below the pinned section. */
enum class PlaylistSort {
    /** Most recent activity first — opened or edited, whichever is later. */
    RECENTS,
    /** A–Z, case- and accent-insensitively enough for a music library. */
    NAME
}

/**
 * Pin-and-sort ordering for the playlists screen.
 *
 * Kept as a pure function rather than SQL for two reasons: pinning has to
 * survive a change of sort mode (a pinned playlist stays at the top whether you
 * are sorting by name or by recency, which no single ORDER BY expresses
 * cleanly), and switching sort mode should not re-run a query.
 */
object PlaylistOrdering {

    /**
     * Pinned playlists first, then the rest by [sort].
     *
     * Pinned entries keep their own order — most recently pinned first — so the
     * top of the list does not reshuffle when the sort mode below it changes.
     */
    fun order(playlists: List<PlaylistRow>, sort: PlaylistSort): List<PlaylistRow> {
        val (pinned, unpinned) = playlists.partition { it.isPinned }
        return pinned.sortedWith(pinnedComparator) + unpinned.sortedWith(comparatorFor(sort))
    }

    private val pinnedComparator: Comparator<PlaylistRow> =
        compareByDescending<PlaylistRow> { it.pinnedAt ?: 0L }
            .thenBy { it.name.lowercase() }
            .thenBy { it.playlistId }

    private fun comparatorFor(sort: PlaylistSort): Comparator<PlaylistRow> = when (sort) {
        // Name, then recency as the tiebreak so two playlists called "Singing"
        // land in a stable, meaningful order rather than an arbitrary one.
        PlaylistSort.NAME ->
            compareBy<PlaylistRow> { it.name.lowercase() }
                .thenByDescending { it.lastActivityAt }
                .thenBy { it.playlistId }

        PlaylistSort.RECENTS ->
            compareByDescending<PlaylistRow> { it.lastActivityAt }
                .thenBy { it.name.lowercase() }
                .thenBy { it.playlistId }
    }
}

package com.visibeat.musicui.playlist

import com.visibeat.viewengine.PlaylistOrdering
import com.visibeat.viewengine.PlaylistRow
import com.visibeat.viewengine.PlaylistSort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pin-above-sort contract. The whole point of pinning is that it survives a
 * change of sort mode, which is the one thing a single SQL ORDER BY cannot
 * express cleanly.
 */
class PlaylistOrderingTest {

    private fun playlist(
        id: Long,
        name: String,
        updatedAt: Long = 0L,
        lastOpenedAt: Long? = null,
        pinnedAt: Long? = null
    ) = PlaylistRow(
        playlistId = id,
        name = name,
        createdAt = 0L,
        updatedAt = updatedAt,
        lastOpenedAt = lastOpenedAt,
        pinnedAt = pinnedAt,
        trackCount = 0,
        coverArtPath = null,
        coverReleaseId = null,
        coverAlbumId = null
    )

    private fun names(rows: List<PlaylistRow>) = rows.map { it.name }

    // ── name sort ─────────────────────────────────────────

    @Test
    fun `name sort is alphabetical and case-insensitive`() {
        val rows = listOf(
            playlist(1, "zebra"),
            playlist(2, "Apple"),
            playlist(3, "banana")
        )
        assertEquals(
            listOf("Apple", "banana", "zebra"),
            names(PlaylistOrdering.order(rows, PlaylistSort.NAME))
        )
    }

    @Test
    fun `name sort breaks ties by recency`() {
        val rows = listOf(
            playlist(1, "Singing", updatedAt = 100),
            playlist(2, "Singing", updatedAt = 900)
        )
        assertEquals(
            listOf(2L, 1L),
            PlaylistOrdering.order(rows, PlaylistSort.NAME).map { it.playlistId }
        )
    }

    // ── recents sort ──────────────────────────────────────

    @Test
    fun `recents sort puts the most recent activity first`() {
        val rows = listOf(
            playlist(1, "Old", updatedAt = 100),
            playlist(2, "New", updatedAt = 900),
            playlist(3, "Middle", updatedAt = 500)
        )
        assertEquals(
            listOf("New", "Middle", "Old"),
            names(PlaylistOrdering.order(rows, PlaylistSort.RECENTS))
        )
    }

    @Test
    fun `recents counts opening as activity, not just editing`() {
        // A playlist you listen to constantly but never edit must not sink.
        val played = playlist(1, "Played", updatedAt = 10, lastOpenedAt = 900)
        val edited = playlist(2, "Edited", updatedAt = 500)
        assertEquals(
            listOf("Played", "Edited"),
            names(PlaylistOrdering.order(listOf(edited, played), PlaylistSort.RECENTS))
        )
    }

    @Test
    fun `recents uses whichever of open and edit is later`() {
        val row = playlist(1, "Both", updatedAt = 700, lastOpenedAt = 200)
        assertEquals(700L, row.lastActivityAt)

        val other = playlist(2, "Other", updatedAt = 100, lastOpenedAt = 800)
        assertEquals(800L, other.lastActivityAt)
    }

    @Test
    fun `a never-opened playlist falls back to its edit time`() {
        assertEquals(400L, playlist(1, "x", updatedAt = 400, lastOpenedAt = null).lastActivityAt)
    }

    // ── pinning ───────────────────────────────────────────

    @Test
    fun `pinned playlists come first under name sort`() {
        val rows = listOf(
            playlist(1, "Apple"),
            playlist(2, "Zebra", pinnedAt = 50)
        )
        assertEquals(
            listOf("Zebra", "Apple"),
            names(PlaylistOrdering.order(rows, PlaylistSort.NAME))
        )
    }

    @Test
    fun `pinned playlists come first under recents sort`() {
        val rows = listOf(
            playlist(1, "Fresh", updatedAt = 900),
            playlist(2, "Stale", updatedAt = 1, pinnedAt = 50)
        )
        assertEquals(
            listOf("Stale", "Fresh"),
            names(PlaylistOrdering.order(rows, PlaylistSort.RECENTS))
        )
    }

    @Test
    fun `pinned order is stable across a change of sort mode`() {
        // This is the requirement pinning exists for: the top of the list must
        // not reshuffle when the sort below it changes.
        val rows = listOf(
            playlist(1, "Zebra", updatedAt = 10, pinnedAt = 100),
            playlist(2, "Apple", updatedAt = 900, pinnedAt = 200),
            playlist(3, "Middle", updatedAt = 500)
        )
        val byName = PlaylistOrdering.order(rows, PlaylistSort.NAME).take(2)
        val byRecents = PlaylistOrdering.order(rows, PlaylistSort.RECENTS).take(2)
        assertEquals(names(byName), names(byRecents))
        // Most recently pinned leads.
        assertEquals(listOf("Apple", "Zebra"), names(byName))
    }

    @Test
    fun `unpinning returns a playlist to the sorted body`() {
        val pinned = playlist(1, "Zebra", updatedAt = 10, pinnedAt = 100)
        val ordered = PlaylistOrdering.order(
            listOf(pinned, playlist(2, "Apple", updatedAt = 5)),
            PlaylistSort.NAME
        )
        assertEquals(listOf("Zebra", "Apple"), names(ordered))

        val unpinned = PlaylistOrdering.order(
            listOf(pinned.copy(pinnedAt = null), playlist(2, "Apple", updatedAt = 5)),
            PlaylistSort.NAME
        )
        assertEquals(listOf("Apple", "Zebra"), names(unpinned))
    }

    @Test
    fun `ordering never drops or duplicates a playlist`() {
        val rows = (1L..12L).map { id ->
            playlist(
                id = id,
                name = "Playlist ${(id * 7) % 12}",
                updatedAt = (id * 13) % 100,
                pinnedAt = if (id % 4 == 0L) id * 10 else null
            )
        }
        for (sort in PlaylistSort.entries) {
            val ordered = PlaylistOrdering.order(rows, sort)
            assertEquals(rows.size, ordered.size)
            assertEquals(rows.map { it.playlistId }.toSet(), ordered.map { it.playlistId }.toSet())
        }
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<PlaylistRow>(), PlaylistOrdering.order(emptyList(), PlaylistSort.NAME))
    }
}

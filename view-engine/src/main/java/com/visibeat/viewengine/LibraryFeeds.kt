package com.visibeat.viewengine

import androidx.compose.runtime.Stable

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The library-wide queries, subscribed once for the whole app.
 *
 * Every screen used to call `libraryDao.observeX()` itself, inside its own
 * composable. Navigation removes a screen from the composition, which cancels
 * its collectors, which unsubscribes the Room flows — so coming back re-ran
 * every query from scratch. That is why leaving a page and returning to it felt
 * like it re-rendered: it did, and it re-read the database to do it.
 *
 * [SharingStarted.WhileSubscribed] with a stop timeout is what fixes it. The
 * upstream query stays warm for [KEEP_ALIVE_MS] after the last collector goes
 * away, so ordinary back-and-forth navigation replays the cached value
 * instantly, while a screen nobody has visited for a while still lets its query
 * go. Room's invalidation tracker keeps the cached value fresh in the meantime,
 * so this trades a little memory for the query — never for correctness.
 *
 * Only queries whose results are the *same for the whole app* belong here.
 * Anything keyed on a search term, a playlist or an artist is per-screen by
 * nature and stays where it is.
 */
/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
class LibraryFeeds(
    private val libraryDao: LibraryDao,
    private val playHistoryDao: PlayHistoryDao,
    private val playlistDao: PlaylistDao,
    private val scope: CoroutineScope
) {
    private fun <T> Flow<T>.shared(initial: T): StateFlow<T> =
        stateIn(scope, SharingStarted.WhileSubscribed(KEEP_ALIVE_MS), initial)

    // ── Counts, shown on Home, Library and Settings ───────────
    val trackCount: StateFlow<Int> = libraryDao.observeTrackCount().shared(0)
    val artistCount: StateFlow<Int> = libraryDao.observeArtistCount().shared(0)
    val albumCount: StateFlow<Int> = libraryDao.observeAlbumCount().shared(0)
    val playedTrackCount: StateFlow<Int> = playHistoryDao.observePlayedTrackCount().shared(0)

    // ── Shelves ───────────────────────────────────────────────
    val recentTracks: StateFlow<List<TimelineItemRow>> =
        libraryDao.observeRecentTracks(RECENT_LIMIT).shared(emptyList())
    /**
     * Albums by total plays.
     *
     * Replaced a recently-added album shelf that sat directly under the
     * Recently Added track shelf and said much the same thing twice.
     */
    val topAlbums: StateFlow<List<TimelineItemRow>> =
        libraryDao.observeTopAlbumsByPlays(SHELF_LIMIT).shared(emptyList())
    val topArtists: StateFlow<List<LibraryArtistRow>> =
        libraryDao.observeTopArtists(SHELF_LIMIT).shared(emptyList())
    val recentlyPlayed: StateFlow<List<TimelineItemRow>> =
        playHistoryDao.observeRecentlyPlayed(SHELF_LIMIT).shared(emptyList())

    // ── Full collections. The expensive ones, and the ones most
    //    worth keeping warm across a drill-down and back. ──────
    val allTracks: StateFlow<List<TimelineItemRow>> =
        libraryDao.observeAllTracks().shared(emptyList())
    val allAlbums: StateFlow<List<TimelineItemRow>> =
        libraryDao.observeAllAlbums().shared(emptyList())
    val allArtists: StateFlow<List<LibraryArtistRow>> =
        libraryDao.observeAllArtists().shared(emptyList())

    val playlists: StateFlow<List<PlaylistRow>> =
        playlistDao.observePlaylists().shared(emptyList())

    private companion object {
        /**
         * How long a query stays subscribed after its last reader leaves.
         *
         * Long enough to cover navigating into an album and straight back out,
         * short enough that a screen you have finished with stops costing
         * anything. Also covers a configuration change, which tears the whole
         * composition down and rebuilds it.
         */
        const val KEEP_ALIVE_MS = 5_000L
        const val RECENT_LIMIT = 30
        const val SHELF_LIMIT = 20
    }
}

package com.visibeat.viewengine

import androidx.compose.runtime.Stable

import androidx.compose.runtime.Immutable

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.visibeat.musicdb.PlaylistEntity
import com.visibeat.musicdb.PlaylistTrackCrossRef
import kotlinx.coroutines.flow.Flow

/**
 * A playlist as the library screen needs it: identity, size, pin state, and one
 * cover to draw.
 */
@Immutable
data class PlaylistRow(
    val playlistId: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long?,
    val pinnedAt: Long?,
    val trackCount: Int,
    val coverArtPath: String?,
    val coverReleaseId: Long?,
    val coverAlbumId: Long?
) {
    val isPinned: Boolean get() = pinnedAt != null

    /**
     * The later of "last opened" and "last edited".
     *
     * Sorting by open time alone would bury a playlist you just built but have
     * not played; sorting by edit time alone would bury one you play constantly.
     */
    val lastActivityAt: Long get() = maxOf(lastOpenedAt ?: 0L, updatedAt)

    /**
     * Cover art for Coil, resolved the same way track art is — including
     * resolving once per row rather than once per read. See
     * [TimelineItemRow.artModel] for why that distinction matters.
     */
    val artModel: Any? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resolveArtModel(coverArtPath, coverReleaseId, coverAlbumId)
    }
}

/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
@Dao
interface PlaylistDao {

    // ── Reads ─────────────────────────────────────────────

    /**
     * Every playlist with its size and cover.
     *
     * Deliberately unordered beyond a stable tiebreak: pin-and-sort ordering is
     * applied in Kotlin by [PlaylistOrdering] so the rules are testable without
     * a database, and so switching sort mode does not re-run the query.
     */
    @Query("""
        SELECT
            p.playlistId,
            p.name,
            p.createdAt,
            p.updatedAt,
            p.lastOpenedAt,
            p.pinnedAt,
            (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.playlistId) AS trackCount,
            (SELECT rt.artPath FROM playlist_tracks pt
                JOIN resolved_tracks rt ON rt.trackId = pt.trackId
                WHERE pt.playlistId = p.playlistId AND rt.artPath IS NOT NULL
                ORDER BY pt.position ASC LIMIT 1) AS coverArtPath,
            (SELECT rt.releaseId FROM playlist_tracks pt
                JOIN resolved_tracks rt ON rt.trackId = pt.trackId
                WHERE pt.playlistId = p.playlistId AND rt.releaseId IS NOT NULL
                ORDER BY pt.position ASC LIMIT 1) AS coverReleaseId,
            (SELECT rt.mediaStoreAlbumId FROM playlist_tracks pt
                JOIN resolved_tracks rt ON rt.trackId = pt.trackId
                WHERE pt.playlistId = p.playlistId AND rt.mediaStoreAlbumId IS NOT NULL
                ORDER BY pt.position ASC LIMIT 1) AS coverAlbumId
        FROM playlists p
        ORDER BY p.playlistId ASC
    """)
    fun observePlaylists(): Flow<List<PlaylistRow>>

    @Query("""
        SELECT
            p.playlistId,
            p.name,
            p.createdAt,
            p.updatedAt,
            p.lastOpenedAt,
            p.pinnedAt,
            (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.playlistId) AS trackCount,
            (SELECT rt.artPath FROM playlist_tracks pt
                JOIN resolved_tracks rt ON rt.trackId = pt.trackId
                WHERE pt.playlistId = p.playlistId AND rt.artPath IS NOT NULL
                ORDER BY pt.position ASC LIMIT 1) AS coverArtPath,
            (SELECT rt.releaseId FROM playlist_tracks pt
                JOIN resolved_tracks rt ON rt.trackId = pt.trackId
                WHERE pt.playlistId = p.playlistId AND rt.releaseId IS NOT NULL
                ORDER BY pt.position ASC LIMIT 1) AS coverReleaseId,
            (SELECT rt.mediaStoreAlbumId FROM playlist_tracks pt
                JOIN resolved_tracks rt ON rt.trackId = pt.trackId
                WHERE pt.playlistId = p.playlistId AND rt.mediaStoreAlbumId IS NOT NULL
                ORDER BY pt.position ASC LIMIT 1) AS coverAlbumId
        FROM playlists p
        WHERE p.playlistId = :playlistId
        LIMIT 1
    """)
    fun observePlaylist(playlistId: Long): Flow<PlaylistRow?>

    /** Tracks in playlist order. */
    @Query("""
        SELECT
            rt.trackId,
            rt.effectiveReleaseDateEpochMs,
            rt.effectiveTitle,
            rt.effectiveAlbumTitle,
            rt.effectiveArtistDisplay,
            rt.releaseId,
            rt.primaryArtistId,
            rt.mediaStoreAlbumId,
            rt.mediaStoreUri,
            rt.artPath
        FROM playlist_tracks pt
        JOIN resolved_tracks rt ON rt.trackId = pt.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
    """)
    fun observeTracks(playlistId: Long): Flow<List<TimelineItemRow>>

    @Query("""
        SELECT
            rt.trackId,
            rt.effectiveReleaseDateEpochMs,
            rt.effectiveTitle,
            rt.effectiveAlbumTitle,
            rt.effectiveArtistDisplay,
            rt.releaseId,
            rt.primaryArtistId,
            rt.mediaStoreAlbumId,
            rt.mediaStoreUri,
            rt.artPath
        FROM playlist_tracks pt
        JOIN resolved_tracks rt ON rt.trackId = pt.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
    """)
    suspend fun getTracks(playlistId: Long): List<TimelineItemRow>

    /** Which playlists already contain a track — drives the picker's checkmarks. */
    @Query("SELECT playlistId FROM playlist_tracks WHERE trackId = :trackId")
    fun observePlaylistIdsContaining(trackId: Long): Flow<List<Long>>

    // ── Playlist lifecycle ────────────────────────────────

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    suspend fun createPlaylist(name: String, now: Long = System.currentTimeMillis()): Long =
        insertPlaylist(
            PlaylistEntity(
                name = name.trim().ifBlank { "Untitled playlist" },
                createdAt = now,
                updatedAt = now
            )
        )

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE playlistId = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String, now: Long)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylistRow(playlistId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deletePlaylistMembership(playlistId: Long)

    /**
     * There are no foreign keys between these tables, so membership has to be
     * cleared explicitly or it would outlive the playlist as orphan rows.
     */
    @Transaction
    suspend fun deletePlaylist(playlistId: Long) {
        deletePlaylistMembership(playlistId)
        deletePlaylistRow(playlistId)
    }

    @Query("UPDATE playlists SET pinnedAt = :pinnedAt WHERE playlistId = :playlistId")
    suspend fun setPinned(playlistId: Long, pinnedAt: Long?)

    suspend fun togglePinned(playlistId: Long, currentlyPinned: Boolean) =
        setPinned(playlistId, if (currentlyPinned) null else System.currentTimeMillis())

    @Query("UPDATE playlists SET lastOpenedAt = :now WHERE playlistId = :playlistId")
    suspend fun touchOpened(playlistId: Long, now: Long)

    // ── Membership ────────────────────────────────────────

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(rows: List<PlaylistTrackCrossRef>)

    @Query("UPDATE playlists SET updatedAt = :now WHERE playlistId = :playlistId")
    suspend fun touchUpdated(playlistId: Long, now: Long)

    /**
     * Appends [trackIds] to the end. Tracks already present are ignored rather
     * than duplicated or moved.
     */
    @Transaction
    suspend fun addTracks(playlistId: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val now = System.currentTimeMillis()
        var position = nextPosition(playlistId)
        insertMembership(
            trackIds.distinct().map { trackId ->
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = position++,
                    addedAt = now
                )
            }
        )
        touchUpdated(playlistId, now)
    }

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deleteMembership(playlistId: Long, trackId: Long)

    @Transaction
    suspend fun removeTrack(playlistId: Long, trackId: Long) {
        deleteMembership(playlistId, trackId)
        touchUpdated(playlistId, System.currentTimeMillis())
    }
}

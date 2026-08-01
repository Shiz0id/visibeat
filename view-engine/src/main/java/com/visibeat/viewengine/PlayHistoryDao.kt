package com.visibeat.viewengine

import androidx.compose.runtime.Stable

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.visibeat.musicdb.PlayHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * What has actually been listened to.
 *
 * Every "recent" shelf in the app was previously ordered by `trackId DESC` —
 * ingest order, i.e. recently *added*. Nothing recorded playback, so a library
 * you had played for months looked identical to one you had just scanned.
 */
/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
@Dao
interface PlayHistoryDao {

    @Query("UPDATE play_history SET lastPlayedAt = :at, playCount = playCount + 1 WHERE trackId = :trackId")
    suspend fun touchPlay(trackId: Long, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlay(row: PlayHistoryEntity)

    /**
     * Update-then-insert rather than an UPSERT: `ON CONFLICT DO UPDATE` needs
     * SQLite 3.24, which only ships from API 30, and this module supports 24.
     */
    @Transaction
    suspend fun recordPlay(trackId: Long, at: Long = System.currentTimeMillis()) {
        if (touchPlay(trackId, at) == 0) {
            insertPlay(PlayHistoryEntity(trackId = trackId, lastPlayedAt = at, playCount = 1))
        }
    }

    /** Most recently played tracks, newest first. */
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
        FROM play_history ph
        JOIN resolved_tracks rt ON rt.trackId = ph.trackId
        ORDER BY ph.lastPlayedAt DESC
        LIMIT :limit
    """)
    fun observeRecentlyPlayed(limit: Int = 20): Flow<List<TimelineItemRow>>

    /**
     * Most recently played releases, one row each.
     *
     * Grouping by release stops a shelf turning into ten consecutive tracks off
     * the same album you just listened through.
     */
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
        FROM play_history ph
        JOIN resolved_tracks rt ON rt.trackId = ph.trackId
        WHERE rt.releaseId IS NOT NULL
        GROUP BY rt.releaseId
        HAVING ph.lastPlayedAt = MAX(ph.lastPlayedAt)
        ORDER BY MAX(ph.lastPlayedAt) DESC
        LIMIT :limit
    """)
    fun observeRecentlyPlayedReleases(limit: Int = 20): Flow<List<TimelineItemRow>>

    @Query("SELECT COUNT(*) FROM play_history")
    fun observePlayedTrackCount(): Flow<Int>

    @Query("DELETE FROM play_history")
    suspend fun clearHistory()
}

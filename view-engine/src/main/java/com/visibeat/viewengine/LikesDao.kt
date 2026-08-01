package com.visibeat.viewengine

import androidx.compose.runtime.Stable
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.visibeat.musicdb.LikedArtistEntity
import com.visibeat.musicdb.LikedReleaseEntity
import com.visibeat.musicdb.LikedTrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Likes, for tracks and for releases.
 *
 * Two independent collections rather than one: liking an album marks the album,
 * and liking a song adds it to Liked Songs. Neither implies the other, so the
 * album header's control and a track row's control never fight over the same
 * state.
 */
@Stable
@Dao
interface LikesDao {

    // ── Tracks ────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLikedTrack(row: LikedTrackEntity)

    @Query("DELETE FROM liked_tracks WHERE trackId = :trackId")
    suspend fun deleteLikedTrack(trackId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE trackId = :trackId)")
    suspend fun isTrackLiked(trackId: Long): Boolean

    /**
     * Every liked track id.
     *
     * A set the UI holds once beats a query per row: album and playlist lists
     * render dozens of rows that each need to know their own like state, and a
     * per-row Flow would mean a subscription each.
     */
    @Query("SELECT trackId FROM liked_tracks")
    fun observeLikedTrackIds(): Flow<List<Long>>

    /**
     * One track's liked state.
     *
     * Separate from [observeLikedTrackIds] on purpose: a list holds the whole
     * set once and asks it per row, but the player follows a single track and
     * would otherwise re-read every id in the library on every like anywhere.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE trackId = :trackId)")
    fun observeTrackLiked(trackId: Long): Flow<Boolean>

    @Transaction
    suspend fun setTrackLiked(trackId: Long, liked: Boolean, now: Long = System.currentTimeMillis()) {
        if (liked) insertLikedTrack(LikedTrackEntity(trackId, now)) else deleteLikedTrack(trackId)
    }

    @Transaction
    suspend fun toggleTrackLiked(trackId: Long): Boolean {
        val next = !isTrackLiked(trackId)
        setTrackLiked(trackId, next)
        return next
    }

    /** Liked songs, most recently liked first. */
    @Query(
        """
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
        FROM liked_tracks lt
        JOIN resolved_tracks rt ON rt.trackId = lt.trackId
        ORDER BY lt.likedAt DESC
        """
    )
    fun observeLikedTracks(): Flow<List<TimelineItemRow>>

    @Query("SELECT COUNT(*) FROM liked_tracks")
    fun observeLikedTrackCount(): Flow<Int>

    // ── Releases ──────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLikedRelease(row: LikedReleaseEntity)

    @Query("DELETE FROM liked_releases WHERE releaseId = :releaseId")
    suspend fun deleteLikedRelease(releaseId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_releases WHERE releaseId = :releaseId)")
    suspend fun isReleaseLiked(releaseId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM liked_releases WHERE releaseId = :releaseId)")
    fun observeReleaseLiked(releaseId: Long): Flow<Boolean>

    @Transaction
    suspend fun toggleReleaseLiked(releaseId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val next = !isReleaseLiked(releaseId)
        if (next) insertLikedRelease(LikedReleaseEntity(releaseId, now)) else deleteLikedRelease(releaseId)
        return next
    }

    /**
     * Liked albums, most recently liked first.
     *
     * Art comes from any track on the release rather than the resolved release
     * cache, which carries no artwork of its own.
     */
    @Query(
        """
        SELECT
            MIN(rt.trackId) AS trackId,
            MIN(rt.effectiveReleaseDateEpochMs) AS effectiveReleaseDateEpochMs,
            MIN(rt.effectiveTitle) AS effectiveTitle,
            rt.effectiveAlbumTitle AS effectiveAlbumTitle,
            MIN(rt.effectiveArtistDisplay) AS effectiveArtistDisplay,
            rt.releaseId AS releaseId,
            MIN(rt.primaryArtistId) AS primaryArtistId,
            MIN(rt.mediaStoreAlbumId) AS mediaStoreAlbumId,
            MIN(rt.mediaStoreUri) AS mediaStoreUri,
            MIN(rt.artPath) AS artPath
        FROM liked_releases lr
        JOIN resolved_tracks rt ON rt.releaseId = lr.releaseId
        GROUP BY rt.releaseId
        ORDER BY MAX(lr.likedAt) DESC
        """
    )
    fun observeLikedReleases(): Flow<List<TimelineItemRow>>

    @Query("SELECT COUNT(*) FROM liked_releases")
    fun observeLikedReleaseCount(): Flow<Int>

    // ── Artists ───────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLikedArtist(row: LikedArtistEntity)

    @Query("DELETE FROM liked_artists WHERE artistId = :artistId")
    suspend fun deleteLikedArtist(artistId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_artists WHERE artistId = :artistId)")
    suspend fun isArtistLiked(artistId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM liked_artists WHERE artistId = :artistId)")
    fun observeArtistLiked(artistId: Long): Flow<Boolean>

    @Transaction
    suspend fun toggleArtistLiked(artistId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val next = !isArtistLiked(artistId)
        if (next) insertLikedArtist(LikedArtistEntity(artistId, now)) else deleteLikedArtist(artistId)
        return next
    }

    /** Followed artists, most recently followed first. */
    @Query(
        """
        SELECT
            a.artistId AS artistId,
            a.displayName AS artistName,
            (SELECT COUNT(DISTINCT ta.trackId) FROM track_artist ta WHERE ta.artistId = a.artistId) AS trackCount,
            (SELECT ai.imageUrl FROM artist_images ai WHERE ai.artistId = a.artistId) AS imageUrl,
            (SELECT rt.artPath FROM resolved_tracks rt
                JOIN track_artist ta2 ON ta2.trackId = rt.trackId
                WHERE ta2.artistId = a.artistId AND rt.artPath IS NOT NULL LIMIT 1) AS fallbackArtPath,
            (SELECT rt.mediaStoreAlbumId FROM resolved_tracks rt
                JOIN track_artist ta3 ON ta3.trackId = rt.trackId
                WHERE ta3.artistId = a.artistId AND rt.mediaStoreAlbumId IS NOT NULL LIMIT 1) AS fallbackAlbumId
        FROM liked_artists la
        JOIN artists a ON a.artistId = la.artistId
        ORDER BY la.likedAt DESC
        """
    )
    fun observeLikedArtists(): Flow<List<LibraryArtistRow>>

    @Query("SELECT COUNT(*) FROM liked_artists")
    fun observeLikedArtistCount(): Flow<Int>
}

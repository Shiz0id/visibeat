package com.visibeat.viewengine

import androidx.compose.runtime.Stable

import androidx.compose.runtime.Immutable

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for Home, Search, and Library screens.
 */
/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
@Dao
interface LibraryDao {

    // ── Stats ──────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM resolved_tracks")
    fun observeTrackCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM artists WHERE artistId IN (SELECT artistId FROM track_artist)")
    fun observeArtistCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT releaseId) FROM resolved_tracks WHERE releaseId IS NOT NULL")
    fun observeAlbumCount(): Flow<Int>

    // ── Recently Added (by track creation / last seen) ────

    @Query("""
        SELECT
            trackId,
            effectiveReleaseDateEpochMs,
            effectiveTitle,
            effectiveAlbumTitle,
            effectiveArtistDisplay,
            releaseId,
            primaryArtistId,
            mediaStoreAlbumId,
            mediaStoreUri,
            artPath
        FROM resolved_tracks
        ORDER BY trackId DESC
        LIMIT :limit
    """)
    fun observeRecentTracks(limit: Int = 30): Flow<List<TimelineItemRow>>

    @Query("""
        SELECT
            trackId,
            effectiveReleaseDateEpochMs,
            effectiveTitle,
            effectiveAlbumTitle,
            effectiveArtistDisplay,
            releaseId,
            primaryArtistId,
            mediaStoreAlbumId,
            mediaStoreUri,
            artPath
        FROM resolved_tracks
        ORDER BY trackId DESC
        LIMIT :limit
    """)
    suspend fun getRecentTracks(limit: Int = 30): List<TimelineItemRow>

    // ── Distinct albums (unique by releaseId) ─────────────

    @Query("""
        SELECT
            MIN(trackId) AS trackId,
            MIN(effectiveReleaseDateEpochMs) AS effectiveReleaseDateEpochMs,
            MIN(effectiveTitle) AS effectiveTitle,
            effectiveAlbumTitle,
            MIN(effectiveArtistDisplay) AS effectiveArtistDisplay,
            releaseId,
            MIN(primaryArtistId) AS primaryArtistId,
            MIN(mediaStoreAlbumId) AS mediaStoreAlbumId,
            MIN(mediaStoreUri) AS mediaStoreUri,
            MIN(artPath) AS artPath
        FROM resolved_tracks
        WHERE releaseId IS NOT NULL AND effectiveAlbumTitle IS NOT NULL
        GROUP BY releaseId
        ORDER BY effectiveAlbumTitle COLLATE NOCASE ASC
    """)
    fun observeAllAlbums(): Flow<List<TimelineItemRow>>

    /**
     * Albums ranked by how much you have played them.
     *
     * The sum of `playCount` over every track on the release — a release with no
     * plays at all has no row in `play_history`, so an inner join is what keeps
     * the shelf to things you have actually listened to rather than padding it
     * with the rest of the library in arbitrary order.
     *
     * Note this favours albums with many tracks: a thirty-track compilation
     * played once outranks a ten-track album played twice. Ranking by
     * plays x duration would not, but track durations are null for anything
     * imported from a folder, so that is a change for when SafScanner learns to
     * read them.
     */
    @Query("""
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
        FROM resolved_tracks rt
        JOIN play_history ph ON ph.trackId = rt.trackId
        WHERE rt.releaseId IS NOT NULL AND rt.effectiveAlbumTitle IS NOT NULL
        GROUP BY rt.releaseId
        ORDER BY SUM(ph.playCount) DESC, rt.effectiveAlbumTitle COLLATE NOCASE
        LIMIT :limit
    """)
    fun observeTopAlbumsByPlays(limit: Int = 20): Flow<List<TimelineItemRow>>

    // ── Distinct artists ──────────────────────────────────

    @Query("""
        SELECT
            a.artistId AS artistId,
            a.displayName AS artistName,
            COUNT(DISTINCT ta.trackId) AS trackCount,
            MAX(ai.imageUrl) AS imageUrl,
            COALESCE(
                MAX(CASE WHEN ta.role IN ('PRIMARY', 'ALBUM_ARTIST') THEN rt.artPath END),
                MAX(rt.artPath)
            ) AS fallbackArtPath,
            COALESCE(
                MAX(CASE WHEN ta.role IN ('PRIMARY', 'ALBUM_ARTIST') THEN rt.mediaStoreAlbumId END),
                MAX(rt.mediaStoreAlbumId)
            ) AS fallbackAlbumId
        FROM artists a
        JOIN track_artist ta ON ta.artistId = a.artistId
        LEFT JOIN resolved_tracks rt ON rt.trackId = ta.trackId
        LEFT JOIN artist_images ai ON ai.artistId = a.artistId
        GROUP BY a.artistId
        HAVING COUNT(DISTINCT ta.trackId) > 0
        ORDER BY a.displayName COLLATE NOCASE ASC
    """)
    fun observeAllArtists(): Flow<List<LibraryArtistRow>>

    // ── All tracks ────────────────────────────────────────

    @Query("""
        SELECT
            trackId,
            effectiveReleaseDateEpochMs,
            effectiveTitle,
            effectiveAlbumTitle,
            effectiveArtistDisplay,
            releaseId,
            primaryArtistId,
            mediaStoreAlbumId,
            mediaStoreUri,
            artPath
        FROM resolved_tracks
        ORDER BY effectiveTitle COLLATE NOCASE ASC
    """)
    fun observeAllTracks(): Flow<List<TimelineItemRow>>

    // ── Search ────────────────────────────────────────────

    @Query("""
        SELECT
            trackId,
            effectiveReleaseDateEpochMs,
            effectiveTitle,
            effectiveAlbumTitle,
            effectiveArtistDisplay,
            releaseId,
            primaryArtistId,
            mediaStoreAlbumId,
            mediaStoreUri,
            artPath
        FROM resolved_tracks
        WHERE effectiveTitle LIKE '%' || :query || '%'
           OR effectiveAlbumTitle LIKE '%' || :query || '%'
           OR effectiveArtistDisplay LIKE '%' || :query || '%'
        ORDER BY
            CASE WHEN effectiveTitle LIKE :query || '%' THEN 0
                 WHEN effectiveArtistDisplay LIKE :query || '%' THEN 1
                 WHEN effectiveAlbumTitle LIKE :query || '%' THEN 2
                 ELSE 3 END,
            effectiveTitle COLLATE NOCASE ASC
        LIMIT :limit
    """)
    fun searchTracks(query: String, limit: Int = 50): Flow<List<TimelineItemRow>>

    @Query("""
        SELECT
            a.artistId AS artistId,
            a.displayName AS artistName,
            COUNT(DISTINCT ta.trackId) AS trackCount,
            MAX(ai.imageUrl) AS imageUrl,
            COALESCE(
                MAX(CASE WHEN ta.role IN ('PRIMARY', 'ALBUM_ARTIST') THEN rt.artPath END),
                MAX(rt.artPath)
            ) AS fallbackArtPath,
            COALESCE(
                MAX(CASE WHEN ta.role IN ('PRIMARY', 'ALBUM_ARTIST') THEN rt.mediaStoreAlbumId END),
                MAX(rt.mediaStoreAlbumId)
            ) AS fallbackAlbumId
        FROM artists a
        JOIN track_artist ta ON ta.artistId = a.artistId
        LEFT JOIN resolved_tracks rt ON rt.trackId = ta.trackId
        LEFT JOIN artist_images ai ON ai.artistId = a.artistId
        WHERE a.displayName LIKE '%' || :query || '%'
        GROUP BY a.artistId
        HAVING COUNT(DISTINCT ta.trackId) > 0
        ORDER BY
            CASE WHEN a.displayName LIKE :query || '%' THEN 0 ELSE 1 END,
            a.displayName COLLATE NOCASE ASC
        LIMIT :limit
    """)
    fun searchArtists(query: String, limit: Int = 20): Flow<List<LibraryArtistRow>>

    @Query("""
        SELECT
            MIN(trackId) AS trackId,
            MIN(effectiveReleaseDateEpochMs) AS effectiveReleaseDateEpochMs,
            MIN(effectiveTitle) AS effectiveTitle,
            effectiveAlbumTitle,
            MIN(effectiveArtistDisplay) AS effectiveArtistDisplay,
            releaseId,
            MIN(primaryArtistId) AS primaryArtistId,
            MIN(mediaStoreAlbumId) AS mediaStoreAlbumId,
            MIN(mediaStoreUri) AS mediaStoreUri,
            MIN(artPath) AS artPath
        FROM resolved_tracks
        WHERE releaseId IS NOT NULL
          AND effectiveAlbumTitle LIKE '%' || :query || '%'
        GROUP BY releaseId
        ORDER BY
            CASE WHEN effectiveAlbumTitle LIKE :query || '%' THEN 0 ELSE 1 END,
            effectiveAlbumTitle COLLATE NOCASE ASC
        LIMIT :limit
    """)
    fun searchAlbums(query: String, limit: Int = 20): Flow<List<TimelineItemRow>>

    // ── Home: random/shuffled selection for variety ───────

    @Query("""
        SELECT
            MIN(trackId) AS trackId,
            MIN(effectiveReleaseDateEpochMs) AS effectiveReleaseDateEpochMs,
            MIN(effectiveTitle) AS effectiveTitle,
            effectiveAlbumTitle,
            MIN(effectiveArtistDisplay) AS effectiveArtistDisplay,
            releaseId,
            MIN(primaryArtistId) AS primaryArtistId,
            MIN(mediaStoreAlbumId) AS mediaStoreAlbumId,
            MIN(mediaStoreUri) AS mediaStoreUri,
            MIN(artPath) AS artPath
        FROM resolved_tracks
        WHERE releaseId IS NOT NULL AND effectiveAlbumTitle IS NOT NULL
        GROUP BY releaseId
        ORDER BY releaseId DESC
        LIMIT :limit
    """)
    fun observeRecentAlbums(limit: Int = 20): Flow<List<TimelineItemRow>>

    @Query("""
        SELECT
            a.artistId AS artistId,
            a.displayName AS artistName,
            COUNT(DISTINCT ta.trackId) AS trackCount,
            MAX(ai.imageUrl) AS imageUrl,
            COALESCE(
                MAX(CASE WHEN ta.role IN ('PRIMARY', 'ALBUM_ARTIST') THEN rt.artPath END),
                MAX(rt.artPath)
            ) AS fallbackArtPath,
            COALESCE(
                MAX(CASE WHEN ta.role IN ('PRIMARY', 'ALBUM_ARTIST') THEN rt.mediaStoreAlbumId END),
                MAX(rt.mediaStoreAlbumId)
            ) AS fallbackAlbumId
        FROM artists a
        JOIN track_artist ta ON ta.artistId = a.artistId
        LEFT JOIN resolved_tracks rt ON rt.trackId = ta.trackId
        LEFT JOIN artist_images ai ON ai.artistId = a.artistId
        GROUP BY a.artistId
        HAVING COUNT(DISTINCT ta.trackId) > 0
        ORDER BY trackCount DESC
        LIMIT :limit
    """)
    fun observeTopArtists(limit: Int = 20): Flow<List<LibraryArtistRow>>

    // ── Playback queues ───────────────────────────────────
    // The screens hold one representative row per album or artist, which is
    // enough to draw a tile but not enough to play one. These fetch the real
    // track list behind a tile at the moment the user presses play.
    //
    // resolved_tracks carries no track number, so trackId ascending stands in
    // for album order — ingest writes tracks in file order, which is usually
    // the same thing.

    @Query("""
        SELECT
            trackId,
            effectiveReleaseDateEpochMs,
            effectiveTitle,
            effectiveAlbumTitle,
            effectiveArtistDisplay,
            releaseId,
            primaryArtistId,
            mediaStoreAlbumId,
            mediaStoreUri,
            artPath
        FROM resolved_tracks
        WHERE releaseId = :releaseId
        ORDER BY trackId ASC
    """)
    suspend fun getTracksForRelease(releaseId: Long): List<TimelineItemRow>

    @Query("""
        SELECT
            trackId,
            effectiveReleaseDateEpochMs,
            effectiveTitle,
            effectiveAlbumTitle,
            effectiveArtistDisplay,
            releaseId,
            primaryArtistId,
            mediaStoreAlbumId,
            mediaStoreUri,
            artPath
        FROM resolved_tracks
        WHERE trackId IN (SELECT trackId FROM track_artist WHERE artistId = :artistId)
        ORDER BY effectiveReleaseDateEpochMs DESC, trackId ASC
    """)
    suspend fun getTracksForArtist(artistId: Long): List<TimelineItemRow>

    @Query("""
        SELECT
            trackId,
            effectiveReleaseDateEpochMs,
            effectiveTitle,
            effectiveAlbumTitle,
            effectiveArtistDisplay,
            releaseId,
            primaryArtistId,
            mediaStoreAlbumId,
            mediaStoreUri,
            artPath
        FROM resolved_tracks
        WHERE mediaStoreUri IS NOT NULL
        ORDER BY trackId ASC
        LIMIT :limit
    """)
    suspend fun getAllPlayableTracks(limit: Int = 2000): List<TimelineItemRow>

    /** Display name for a single artist — used by the artist screen's header. */
    @Query("""
        SELECT
            a.artistId AS artistId,
            a.displayName AS artistName,
            COUNT(DISTINCT ta.trackId) AS trackCount,
            MAX(ai.imageUrl) AS imageUrl,
            COALESCE(
                MAX(CASE WHEN ta.role IN ('PRIMARY', 'ALBUM_ARTIST') THEN rt.artPath END),
                MAX(rt.artPath)
            ) AS fallbackArtPath,
            COALESCE(
                MAX(CASE WHEN ta.role IN ('PRIMARY', 'ALBUM_ARTIST') THEN rt.mediaStoreAlbumId END),
                MAX(rt.mediaStoreAlbumId)
            ) AS fallbackAlbumId
        FROM artists a
        LEFT JOIN track_artist ta ON ta.artistId = a.artistId
        LEFT JOIN resolved_tracks rt ON rt.trackId = ta.trackId
        LEFT JOIN artist_images ai ON ai.artistId = a.artistId
        WHERE a.artistId = :artistId
        GROUP BY a.artistId
        LIMIT 1
    """)
    suspend fun getArtist(artistId: Long): LibraryArtistRow?

    /** Newest album art for an artist, for the artist screen's hero. */
    @Query("""
        SELECT artPath FROM resolved_tracks
        WHERE primaryArtistId = :artistId AND artPath IS NOT NULL
        ORDER BY effectiveReleaseDateEpochMs DESC
        LIMIT 1
    """)
    suspend fun getArtistArtPath(artistId: Long): String?
}

/**
 * Row type for artist listings.
 */
@Immutable
data class LibraryArtistRow(
    val artistId: Long,
    val artistName: String,
    val trackCount: Int,
    /** Looked-up portrait, or null if none has been found for this artist. */
    val imageUrl: String? = null,
    /**
     * Album art to stand in for a missing portrait.
     *
     * Taken from a record the artist actually made where one exists, and only
     * from a guest appearance when it does not. Any credit used to qualify, so
     * Rihanna — who sings one verse on "LOYALTY." — wore Kendrick Lamar's *DAMN.*
     * sleeve as her avatar.
     *
     * A preference rather than a filter, because 117 artists in a real library
     * have nothing but guest credits. Excluding those outright would not fix
     * their avatar, it would delete it.
     */
    val fallbackArtPath: String? = null,
    val fallbackAlbumId: Long? = null
) {
    /**
     * The artist's album art, for Coil, or null if there is none.
     *
     * Deliberately does not fold [imageUrl] in. This is tier two, and the tiers
     * only cover each other if they stay distinct: a helper returning "portrait
     * if there is one, else album art" collapses to a single model, so a portrait
     * URL that fails to load takes the album art down with it and the avatar
     * drops all the way to initials.
     */
    val albumArtModel: Any?
        get() = resolveArtModel(fallbackArtPath, null, fallbackAlbumId)
}

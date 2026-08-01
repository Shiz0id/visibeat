package com.visibeat.viewengine.artist

import androidx.room.Dao
import androidx.room.Query

@Dao
interface ArtistAlbumTimelineDao {

  @Query(
    """
    WITH filtered AS (
      SELECT *
      FROM resolved_tracks
      -- Membership, not lead credit. Filtering on primaryArtistId meant an
      -- artist who only ever appears as a guest — which is most of them once
      -- collaboration credits are split apart — had a completely empty page.
      WHERE trackId IN (SELECT trackId FROM track_artist WHERE artistId = :artistId)
        AND effectiveReleaseDateEpochMs IS NOT NULL
        AND (:fromEpochMs IS NULL OR effectiveReleaseDateEpochMs >= :fromEpochMs)
        AND (:toEpochMs   IS NULL OR effectiveReleaseDateEpochMs <  :toEpochMs)
    ),
    grouped AS (
      SELECT
        (strftime('%s', strftime('%Y-%m-01 00:00:00', effectiveReleaseDateEpochMs/1000, 'unixepoch')) * 1000) AS bucketStartEpochMs,
        COUNT(DISTINCT releaseId) AS releaseCount
      FROM filtered
      WHERE releaseId IS NOT NULL
      GROUP BY bucketStartEpochMs
    )
    SELECT bucketStartEpochMs, releaseCount
    FROM grouped
    ORDER BY bucketStartEpochMs
    LIMIT :limit
    """
  )
  suspend fun listAlbumBucketsForArtist(
    artistId: Long,
    fromEpochMs: Long?,
    toEpochMs: Long?,
    limit: Int
  ): List<AlbumBucketRow>

  @Query(
    """
    WITH filtered AS (
      SELECT *
      FROM resolved_tracks
      WHERE trackId IN (SELECT trackId FROM track_artist WHERE artistId = :artistId)
        AND releaseId IS NOT NULL
        AND effectiveReleaseDateEpochMs IS NOT NULL
        AND effectiveReleaseDateEpochMs >= :bucketStartEpochMs
        AND effectiveReleaseDateEpochMs <  :bucketEndEpochMs
    ),
    album_rows AS (
      SELECT
        releaseId AS releaseId,
        MIN(effectiveReleaseDateEpochMs) AS effectiveReleaseDateEpochMs,
        MAX(effectiveAlbumTitle) AS albumTitle,
        MAX(effectiveArtistDisplay) AS albumArtistDisplay,
        COUNT(*) AS trackCount
      FROM filtered
      GROUP BY releaseId
    )
    SELECT
      releaseId,
      :bucketStartEpochMs AS bucketStartEpochMs,
      albumTitle,
      albumArtistDisplay,
      effectiveReleaseDateEpochMs,
      trackCount
    FROM album_rows
    ORDER BY effectiveReleaseDateEpochMs DESC
    LIMIT :limit
    """
  )
  suspend fun listAlbumCardsForBucket(
    artistId: Long,
    bucketStartEpochMs: Long,
    bucketEndEpochMs: Long,
    limit: Int
  ): List<AlbumCardRow>
}

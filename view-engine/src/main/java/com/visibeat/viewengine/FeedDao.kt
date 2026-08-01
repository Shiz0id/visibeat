package com.visibeat.viewengine

import androidx.room.Dao
import androidx.room.Query

@Dao
interface FeedDao {
  @Query(
    """
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
    WHERE 1=1
      AND (:fromEpochMs IS NULL OR effectiveReleaseDateEpochMs >= :fromEpochMs)
      AND (:toEpochMs   IS NULL OR effectiveReleaseDateEpochMs <  :toEpochMs)
      AND (:artistId    IS NULL OR primaryArtistId = :artistId)
      AND (:releaseId   IS NULL OR releaseId = :releaseId)
      AND (:genreLike   IS NULL OR effectiveGenreDisplay LIKE :genreLike)
      AND (:qualityFilterCount = 0 OR releaseDateQuality IN (:qualityFilters))
    ORDER BY
      CASE WHEN :sortDesc = 1 THEN effectiveReleaseDateEpochMs END DESC,
      CASE WHEN :sortDesc = 0 THEN effectiveReleaseDateEpochMs END ASC
    LIMIT :limit
    """
  )
  suspend fun listFeed(
    fromEpochMs: Long?,
    toEpochMs: Long?,
    artistId: Long?,
    releaseId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>,
    sortDesc: Int,
    limit: Int
  ): List<TimelineItemRow>
}

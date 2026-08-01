package com.visibeat.viewengine

import androidx.room.Dao
import androidx.room.Query
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow

/** A release as a branch of the timeline tree. */
@Immutable
data class TimelineAlbumRow(
  val releaseId: Long,
  val title: String?,
  val artistDisplay: String?,
  val trackCount: Int,
  val artPath: String?,
  val mediaStoreAlbumId: Long?
) {
  val artModel: Any? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    resolveArtModel(artPath, releaseId, mediaStoreAlbumId)
  }
}

@Dao
interface TimelineDao {

  // ------------------------------------------------------------
  // BUCKETS (Flow versions for reactive UI)
  // ------------------------------------------------------------

  /**
   * Month buckets (UTC) as Flow - auto-updates when data changes.
   */
  @Query(
    """
    WITH filtered AS (
      SELECT *
      FROM resolved_tracks
      WHERE 1=1
        AND (:fromEpochMs IS NULL OR effectiveReleaseDateEpochMs >= :fromEpochMs)
        AND (:toEpochMs   IS NULL OR effectiveReleaseDateEpochMs <  :toEpochMs)
        AND (:artistId    IS NULL OR primaryArtistId = :artistId)
        AND (:releaseId   IS NULL OR releaseId = :releaseId)
        AND (:genreLike   IS NULL OR effectiveGenreDisplay LIKE :genreLike)
        AND (
          :qualityFilterCount = 0 OR releaseDateQuality IN (:qualityFilters)
        )
        AND bucketMonthEpochMs IS NOT NULL
    ),
    grouped AS (
      SELECT
        bucketMonthEpochMs AS bucketStartEpochMs,
        COUNT(*) AS itemCount,
        COUNT(DISTINCT releaseId) AS distinctReleaseCount,
        COUNT(DISTINCT primaryArtistId) AS distinctArtistCount
      FROM filtered
      GROUP BY bucketStartEpochMs
    )
    SELECT bucketStartEpochMs, itemCount, distinctReleaseCount, distinctArtistCount
    FROM grouped
    ORDER BY bucketStartEpochMs
    """
  )
  fun observeMonthBuckets(
    fromEpochMs: Long?,
    toEpochMs: Long?,
    artistId: Long?,
    releaseId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>
  ): Flow<List<TimelineBucketRow>>

  /**
   * Month buckets (UTC). Groups on the precomputed, indexed month anchor.
   * Returns bucketStartEpochMs as the first day of the month at UTC midnight.
   */
  @Query(
    """
    WITH filtered AS (
      SELECT *
      FROM resolved_tracks
      WHERE 1=1
        AND (:fromEpochMs IS NULL OR effectiveReleaseDateEpochMs >= :fromEpochMs)
        AND (:toEpochMs   IS NULL OR effectiveReleaseDateEpochMs <  :toEpochMs)
        AND (:artistId    IS NULL OR primaryArtistId = :artistId)
        AND (:releaseId   IS NULL OR releaseId = :releaseId)
        AND (:genreLike   IS NULL OR effectiveGenreDisplay LIKE :genreLike)
        AND (
          :qualityFilterCount = 0 OR releaseDateQuality IN (:qualityFilters)
        )
        AND bucketMonthEpochMs IS NOT NULL
    ),
    grouped AS (
      SELECT
        -- Month start UTC: YYYY-MM-01 00:00:00Z
        bucketMonthEpochMs AS bucketStartEpochMs,
        COUNT(*) AS itemCount,
        COUNT(DISTINCT releaseId) AS distinctReleaseCount,
        COUNT(DISTINCT primaryArtistId) AS distinctArtistCount
      FROM filtered
      GROUP BY bucketStartEpochMs
    )
    SELECT bucketStartEpochMs, itemCount, distinctReleaseCount, distinctArtistCount
    FROM grouped
    ORDER BY bucketStartEpochMs
    """
  )
  suspend fun listMonthBuckets(
    fromEpochMs: Long?,
    toEpochMs: Long?,
    artistId: Long?,
    releaseId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>
  ): List<TimelineBucketRow>

  /**
   * Year buckets (UTC) as Flow - auto-updates when data changes.
   */
  @Query(
    """
    WITH filtered AS (
      SELECT *
      FROM resolved_tracks
      WHERE 1=1
        AND (:fromEpochMs IS NULL OR effectiveReleaseDateEpochMs >= :fromEpochMs)
        AND (:toEpochMs   IS NULL OR effectiveReleaseDateEpochMs <  :toEpochMs)
        AND (:artistId    IS NULL OR primaryArtistId = :artistId)
        AND (:releaseId   IS NULL OR releaseId = :releaseId)
        AND (:genreLike   IS NULL OR effectiveGenreDisplay LIKE :genreLike)
        AND (
          :qualityFilterCount = 0 OR releaseDateQuality IN (:qualityFilters)
        )
        AND bucketYearEpochMs IS NOT NULL
    ),
    grouped AS (
      SELECT
        bucketYearEpochMs AS bucketStartEpochMs,
        COUNT(*) AS itemCount,
        COUNT(DISTINCT releaseId) AS distinctReleaseCount,
        COUNT(DISTINCT primaryArtistId) AS distinctArtistCount
      FROM filtered
      GROUP BY bucketStartEpochMs
    )
    SELECT bucketStartEpochMs, itemCount, distinctReleaseCount, distinctArtistCount
    FROM grouped
    ORDER BY bucketStartEpochMs
    """
  )
  fun observeYearBuckets(
    fromEpochMs: Long?,
    toEpochMs: Long?,
    artistId: Long?,
    releaseId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>
  ): Flow<List<TimelineBucketRow>>

  /**
   * Year buckets (UTC). bucketStartEpochMs is Jan 1 00:00:00Z.
   */
  @Query(
    """
    WITH filtered AS (
      SELECT *
      FROM resolved_tracks
      WHERE 1=1
        AND (:fromEpochMs IS NULL OR effectiveReleaseDateEpochMs >= :fromEpochMs)
        AND (:toEpochMs   IS NULL OR effectiveReleaseDateEpochMs <  :toEpochMs)
        AND (:artistId    IS NULL OR primaryArtistId = :artistId)
        AND (:releaseId   IS NULL OR releaseId = :releaseId)
        AND (:genreLike   IS NULL OR effectiveGenreDisplay LIKE :genreLike)
        AND (
          :qualityFilterCount = 0 OR releaseDateQuality IN (:qualityFilters)
        )
        AND bucketYearEpochMs IS NOT NULL
    ),
    grouped AS (
      SELECT
        bucketYearEpochMs AS bucketStartEpochMs,
        COUNT(*) AS itemCount,
        COUNT(DISTINCT releaseId) AS distinctReleaseCount,
        COUNT(DISTINCT primaryArtistId) AS distinctArtistCount
      FROM filtered
      GROUP BY bucketStartEpochMs
    )
    SELECT bucketStartEpochMs, itemCount, distinctReleaseCount, distinctArtistCount
    FROM grouped
    ORDER BY bucketStartEpochMs
    """
  )
  suspend fun listYearBuckets(
    fromEpochMs: Long?,
    toEpochMs: Long?,
    artistId: Long?,
    releaseId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>
  ): List<TimelineBucketRow>

  /**
   * Day buckets (UTC) as Flow - auto-updates when data changes.
   */
  @Query(
    """
    WITH filtered AS (
      SELECT *
      FROM resolved_tracks
      WHERE 1=1
        AND (:fromEpochMs IS NULL OR effectiveReleaseDateEpochMs >= :fromEpochMs)
        AND (:toEpochMs   IS NULL OR effectiveReleaseDateEpochMs <  :toEpochMs)
        AND (:artistId    IS NULL OR primaryArtistId = :artistId)
        AND (:releaseId   IS NULL OR releaseId = :releaseId)
        AND (:genreLike   IS NULL OR effectiveGenreDisplay LIKE :genreLike)
        AND (
          :qualityFilterCount = 0 OR releaseDateQuality IN (:qualityFilters)
        )
        AND bucketDayEpochMs IS NOT NULL
    ),
    grouped AS (
      SELECT
        bucketDayEpochMs AS bucketStartEpochMs,
        COUNT(*) AS itemCount,
        COUNT(DISTINCT releaseId) AS distinctReleaseCount,
        COUNT(DISTINCT primaryArtistId) AS distinctArtistCount
      FROM filtered
      GROUP BY bucketStartEpochMs
    )
    SELECT bucketStartEpochMs, itemCount, distinctReleaseCount, distinctArtistCount
    FROM grouped
    ORDER BY bucketStartEpochMs
    """
  )
  fun observeDayBuckets(
    fromEpochMs: Long?,
    toEpochMs: Long?,
    artistId: Long?,
    releaseId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>
  ): Flow<List<TimelineBucketRow>>

  /**
   * Day buckets (UTC). bucketStartEpochMs is that day at 00:00:00Z.
   */
  @Query(
    """
    WITH filtered AS (
      SELECT *
      FROM resolved_tracks
      WHERE 1=1
        AND (:fromEpochMs IS NULL OR effectiveReleaseDateEpochMs >= :fromEpochMs)
        AND (:toEpochMs   IS NULL OR effectiveReleaseDateEpochMs <  :toEpochMs)
        AND (:artistId    IS NULL OR primaryArtistId = :artistId)
        AND (:releaseId   IS NULL OR releaseId = :releaseId)
        AND (:genreLike   IS NULL OR effectiveGenreDisplay LIKE :genreLike)
        AND (
          :qualityFilterCount = 0 OR releaseDateQuality IN (:qualityFilters)
        )
        AND bucketDayEpochMs IS NOT NULL
    ),
    grouped AS (
      SELECT
        bucketDayEpochMs AS bucketStartEpochMs,
        COUNT(*) AS itemCount,
        COUNT(DISTINCT releaseId) AS distinctReleaseCount,
        COUNT(DISTINCT primaryArtistId) AS distinctArtistCount
      FROM filtered
      GROUP BY bucketStartEpochMs
    )
    SELECT bucketStartEpochMs, itemCount, distinctReleaseCount, distinctArtistCount
    FROM grouped
    ORDER BY bucketStartEpochMs
    """
  )
  suspend fun listDayBuckets(
    fromEpochMs: Long?,
    toEpochMs: Long?,
    artistId: Long?,
    releaseId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>
  ): List<TimelineBucketRow>

  /**
   * The releases inside a bucket, complete.
   *
   * Deliberately unlimited and unfiltered by artwork, unlike the collage on a
   * bucket card — that is a preview and is allowed to be partial, this is what
   * you get when you open the bucket, and it has to be everything. A release
   * with no cover is still a release.
   *
   * Takes an explicit range rather than a bucket column so one query serves
   * every granularity.
   */
  @Query(
    """
    SELECT
      rt.releaseId AS releaseId,
      rt.effectiveAlbumTitle AS title,
      MIN(rt.effectiveArtistDisplay) AS artistDisplay,
      COUNT(*) AS trackCount,
      MIN(rt.artPath) AS artPath,
      MIN(rt.mediaStoreAlbumId) AS mediaStoreAlbumId
    FROM resolved_tracks rt
    WHERE rt.releaseId IS NOT NULL
      AND rt.effectiveReleaseDateEpochMs >= :bucketStartEpochMs
      AND rt.effectiveReleaseDateEpochMs <  :bucketEndEpochMs
      AND (:artistId  IS NULL OR rt.primaryArtistId = :artistId)
      AND (:genreLike IS NULL OR rt.effectiveGenreDisplay LIKE :genreLike)
      AND (:qualityFilterCount = 0 OR rt.releaseDateQuality IN (:qualityFilters))
    GROUP BY rt.releaseId
    ORDER BY rt.effectiveAlbumTitle COLLATE NOCASE
    """
  )
  suspend fun listAlbumsInBucket(
    bucketStartEpochMs: Long,
    bucketEndEpochMs: Long,
    artistId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>
  ): List<TimelineAlbumRow>

  /**
   * A release's tracks in album order, for expanding it inside the timeline.
   *
   * Same ordering as the album page — disc then track number, untagged last —
   * so a release reads identically wherever you open it.
   */
  @Query(
    """
    SELECT
      trackId, effectiveReleaseDateEpochMs, effectiveTitle, effectiveAlbumTitle,
      effectiveArtistDisplay, releaseId, primaryArtistId, mediaStoreAlbumId,
      mediaStoreUri, artPath, discNumber, trackNumber, mimeType
    FROM resolved_tracks
    WHERE releaseId = :releaseId
    ORDER BY
      discNumber IS NULL, discNumber,
      trackNumber IS NULL, trackNumber,
      effectiveTitle COLLATE NOCASE,
      trackId
    """
  )
  suspend fun listAlbumTracks(releaseId: Long): List<AlbumTrackRow>

  /**
   * Tracks the date axis cannot place at all.
   *
   * Every bucket query ends with `effectiveReleaseDateEpochMs IS NOT NULL`, so
   * these are simply absent from the timeline. That is the right call for a
   * date axis and the wrong thing to do silently — without a number the user has
   * no way to tell a small library from a badly tagged one.
   */
  @Query("SELECT COUNT(*) FROM resolved_tracks WHERE effectiveReleaseDateEpochMs IS NULL")
  fun observeUndatedTrackCount(): Flow<Int>

  /**
   * Fetch a single track by its ID for playback or details.
   */
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
    WHERE trackId = :trackId
    LIMIT 1
    """
  )
  suspend fun getTrackById(trackId: Long): TimelineItemRow?

  // ------------------------------------------------------------

  /**
   * Items for a month bucket preview. Caller passes the bucketStartEpochMs
   * and bucketEndEpochMs (next month start) to avoid tricky month math in SQL.
   */
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
      AND effectiveReleaseDateEpochMs IS NOT NULL
      AND effectiveReleaseDateEpochMs >= :bucketStartEpochMs
      AND effectiveReleaseDateEpochMs <  :bucketEndEpochMs
      AND (:artistId  IS NULL OR primaryArtistId = :artistId)
      AND (:releaseId IS NULL OR releaseId = :releaseId)
      AND (:genreLike IS NULL OR effectiveGenreDisplay LIKE :genreLike)
      AND (
        :qualityFilterCount = 0 OR releaseDateQuality IN (:qualityFilters)
      )
    ORDER BY effectiveReleaseDateEpochMs DESC
    LIMIT :limit
    """
  )
  suspend fun listItemsForBucket(
    bucketStartEpochMs: Long,
    bucketEndEpochMs: Long,
    artistId: Long?,
    releaseId: Long?,
    genreLike: String?,
    qualityFilterCount: Int,
    qualityFilters: List<String>,
    limit: Int
  ): List<TimelineItemRow>
}

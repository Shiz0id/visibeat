package com.visibeat.viewengine

import androidx.compose.runtime.Stable

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.TimeZone

/**
 * Which buckets a query actually shows, and in what order.
 *
 * Pulled out as a pure function because it was wrong in a way that only appears
 * on a large library. The SQL used to end `ORDER BY bucketStartEpochMs LIMIT 60`
 * — ascending — so it returned the sixty *oldest* buckets, and the engine then
 * reversed them for display. Past sixty distinct buckets (five years of months,
 * or two months of days) everything recent became unreachable, and the sort
 * toggle could not bring it back because it only reordered the same window.
 *
 * The limit is gone from the SQL entirely. It never saved any work — the grouped
 * CTE has to materialise in full before `ORDER BY … LIMIT` can pick from it — so
 * all it did was throw away the wrong rows. Capping happens here instead, at
 * whichever end the sort direction implies.
 */
internal object TimelineWindow {

  /**
   * @param ascending the DAO's natural order, oldest first
   * @param sort the direction the user asked for
   * @param limit how many buckets to keep, from the end [sort] starts at
   */
  fun apply(
    ascending: List<TimelineBucketRow>,
    sort: SortDirection,
    limit: Int
  ): List<TimelineBucketRow> {
    if (limit <= 0) return emptyList()
    // Newest-first takes the newest N; oldest-first takes the oldest N. Either
    // way the user starts at the end they asked to start at, and scrolls into
    // the library rather than away from it.
    val ordered = if (sort == SortDirection.DESC) ascending.asReversed() else ascending
    return if (ordered.size <= limit) ordered else ordered.subList(0, limit).toList()
  }
}

/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
class TimelineQueryEngine(
  private val timelineDao: TimelineDao
) {
  
  /**
   * Observe buckets as Flow - auto-updates when DB changes.
   * Use this for reactive UI that should refresh after enrichment.
   */
  fun observeBuckets(q: ViewQuery): Flow<List<TimelineBucketRow>> {
    val genreLike = q.genreContains?.trim()?.takeIf { it.isNotBlank() }?.let { "%$it%" }
    val qualityFilters = q.releaseDateQuality.toList()
    val qualityCount = qualityFilters.size

    val flow = when (q.bucket) {
      TimelineBucket.MONTH -> timelineDao.observeMonthBuckets(
        q.fromEpochMs, q.toEpochMs,
        q.artistId, q.releaseId,
        genreLike,
        qualityCount, qualityFilters
      )
      TimelineBucket.YEAR -> timelineDao.observeYearBuckets(
        q.fromEpochMs, q.toEpochMs,
        q.artistId, q.releaseId,
        genreLike,
        qualityCount, qualityFilters
      )
      TimelineBucket.DAY -> timelineDao.observeDayBuckets(
        q.fromEpochMs, q.toEpochMs,
        q.artistId, q.releaseId,
        genreLike,
        qualityCount, qualityFilters
      )
    }

    return flow.map { TimelineWindow.apply(it, q.sort, q.bucketLimit) }
  }

  suspend fun getBuckets(q: ViewQuery): List<TimelineBucketRow> {
    val genreLike = q.genreContains?.trim()?.takeIf { it.isNotBlank() }?.let { "%$it%" }
    val qualityFilters = q.releaseDateQuality.toList()
    val qualityCount = qualityFilters.size

    val buckets = when (q.bucket) {
      TimelineBucket.MONTH -> timelineDao.listMonthBuckets(
        q.fromEpochMs, q.toEpochMs,
        q.artistId, q.releaseId,
        genreLike,
        qualityCount, qualityFilters
      )
      TimelineBucket.YEAR -> timelineDao.listYearBuckets(
        q.fromEpochMs, q.toEpochMs,
        q.artistId, q.releaseId,
        genreLike,
        qualityCount, qualityFilters
      )
      TimelineBucket.DAY -> timelineDao.listDayBuckets(
        q.fromEpochMs, q.toEpochMs,
        q.artistId, q.releaseId,
        genreLike,
        qualityCount, qualityFilters
      )
    }

    return TimelineWindow.apply(buckets, q.sort, q.bucketLimit)
  }

  /** @see TimelineDao.observeUndatedTrackCount */
  fun observeUndatedTrackCount(): Flow<Int> = timelineDao.observeUndatedTrackCount()

  suspend fun getBucketPreviewItems(q: ViewQuery, bucketStartEpochMs: Long): List<TimelineItemRow> {
    val genreLike = q.genreContains?.trim()?.takeIf { it.isNotBlank() }?.let { "%$it%" }
    val qualityFilters = q.releaseDateQuality.toList()
    val qualityCount = qualityFilters.size

    val bucketEnd = when (q.bucket) {
      TimelineBucket.YEAR -> addUtc(bucketStartEpochMs, years = 1)
      TimelineBucket.MONTH -> addUtc(bucketStartEpochMs, months = 1)
      TimelineBucket.DAY -> addUtc(bucketStartEpochMs, days = 1)
    }

    return timelineDao.listItemsForBucket(
      bucketStartEpochMs = bucketStartEpochMs,
      bucketEndEpochMs = bucketEnd,
      artistId = q.artistId,
      releaseId = q.releaseId,
      genreLike = genreLike,
      qualityFilterCount = qualityCount,
      qualityFilters = qualityFilters,
      limit = q.itemsPerBucket
    )
  }

  /**
   * Every release inside a bucket. Complete, unlike the bucket card's collage.
   */
  suspend fun getAlbumsInBucket(q: ViewQuery, bucketStartEpochMs: Long): List<TimelineAlbumRow> {
    val genreLike = q.genreContains?.trim()?.takeIf { it.isNotBlank() }?.let { "%$it%" }
    val qualityFilters = q.releaseDateQuality.toList()

    val bucketEnd = when (q.bucket) {
      TimelineBucket.YEAR -> addUtc(bucketStartEpochMs, years = 1)
      TimelineBucket.MONTH -> addUtc(bucketStartEpochMs, months = 1)
      TimelineBucket.DAY -> addUtc(bucketStartEpochMs, days = 1)
    }

    return timelineDao.listAlbumsInBucket(
      bucketStartEpochMs = bucketStartEpochMs,
      bucketEndEpochMs = bucketEnd,
      artistId = q.artistId,
      genreLike = genreLike,
      qualityFilterCount = qualityFilters.size,
      qualityFilters = qualityFilters
    )
  }

  /** @see TimelineDao.listAlbumTracks */
  suspend fun getAlbumTracks(releaseId: Long): List<AlbumTrackRow> =
    timelineDao.listAlbumTracks(releaseId)

  private fun addUtc(epochMs: Long, years: Int = 0, months: Int = 0, days: Int = 0): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epochMs
    if (years != 0) cal.add(Calendar.YEAR, years)
    if (months != 0) cal.add(Calendar.MONTH, months)
    if (days != 0) cal.add(Calendar.DAY_OF_MONTH, days)
    // Ensure anchor stays at midnight UTC
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
  }
}

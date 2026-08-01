package com.visibeat.viewengine.artist

import androidx.compose.runtime.Stable

import com.visibeat.viewengine.SortDirection
import com.visibeat.viewengine.TimelineBucket
import java.util.Calendar
import java.util.TimeZone

/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
class ArtistAlbumTimelineEngine(
  private val dao: ArtistAlbumTimelineDao
) {
  suspend fun getBuckets(
    artistId: Long,
    fromEpochMs: Long? = null,
    toEpochMs: Long? = null,
    limit: Int = 60,
    sort: SortDirection = SortDirection.DESC
  ): List<AlbumBucketRow> {
    val buckets = dao.listAlbumBucketsForArtist(artistId, fromEpochMs, toEpochMs, limit)
    return if (sort == SortDirection.DESC) buckets.asReversed() else buckets
  }

  suspend fun getAlbumCardsForBucket(
    artistId: Long,
    bucketStartEpochMs: Long,
    bucket: TimelineBucket = TimelineBucket.MONTH,
    limit: Int = 30
  ): List<AlbumCardRow> {
    val end = computeBucketEndUtc(bucketStartEpochMs, bucket)
    return dao.listAlbumCardsForBucket(artistId, bucketStartEpochMs, end, limit)
  }

  private fun computeBucketEndUtc(bucketStartEpochMs: Long, bucket: TimelineBucket): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = bucketStartEpochMs
    when (bucket) {
      TimelineBucket.YEAR -> cal.add(Calendar.YEAR, 1)
      TimelineBucket.MONTH -> cal.add(Calendar.MONTH, 1)
      TimelineBucket.DAY -> cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
  }
}

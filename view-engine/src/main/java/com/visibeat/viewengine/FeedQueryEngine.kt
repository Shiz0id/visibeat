package com.visibeat.viewengine

import androidx.compose.runtime.Stable

/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
class FeedQueryEngine(private val feedDao: FeedDao) {
  suspend fun listFeedItems(q: ViewQuery): List<TimelineItemRow> {
    val genreLike = q.genreContains?.trim()?.takeIf { it.isNotBlank() }?.let { "%$it%" }
    val qualities = q.releaseDateQuality.toList()
    val sortDesc = if (q.sort == SortDirection.DESC) 1 else 0
    return feedDao.listFeed(
      fromEpochMs = q.fromEpochMs,
      toEpochMs = q.toEpochMs,
      artistId = q.artistId,
      releaseId = q.releaseId,
      genreLike = genreLike,
      qualityFilterCount = qualities.size,
      qualityFilters = qualities,
      sortDesc = sortDesc,
      limit = 5_000 // toy app: load plenty; add paging later
    )
  }
}

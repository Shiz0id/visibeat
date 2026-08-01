package com.visibeat.viewengine

import androidx.compose.runtime.Immutable

import com.visibeat.coredb.SubjectType

/**
 * Granularities the timeline can group by.
 *
 * WEEK used to sit between MONTH and DAY. It had no layout, was excluded from
 * the granularity control, was unreachable by pinch-zoom, and where it was
 * handled at all it was incoherent — the query engine gave it *month* buckets
 * while computing a *seven-day* preview range for them. Removed rather than
 * left as a trap; adding it back means giving it a real bucket query.
 */
enum class TimelineBucket { YEAR, MONTH, DAY }
enum class SortDirection { ASC, DESC }

/**
 * This is the "WinFS query object" equivalent.
 * UI builds this, QueryEngine runs it, ViewEngine renders it.
 */
/**
 * Immutable so Compose can skip.
 *
 * This module is compiled without the Compose compiler, so without the
 * annotation the compiler has no stability metadata for these types and must
 * assume they can change without notifying the composition. Everything
 * downstream inherits that: rows, lists and screens taking them became
 * non-skippable, re-executing on every parent recomposition regardless of
 * whether their inputs had changed.
 *
 * These are `val`-only data classes over primitives and Strings, so the promise
 * is true — but the compiler cannot see that from another module without help.
 */
@Immutable
data class ViewQuery(
  val subjectType: SubjectType = SubjectType.TRACK,

  // Timeline axis and ordering
  val bucket: TimelineBucket = TimelineBucket.MONTH,
  val sort: SortDirection = SortDirection.DESC,

  // Time range (epoch millis). Null means unbounded.
  val fromEpochMs: Long? = null,
  val toEpochMs: Long? = null,

  // Chips / facets (optional; add more as you go)
  val artistId: Long? = null,
  val releaseId: Long? = null,
  val genreContains: String? = null,     // simple string match for toy app
  val releaseDateQuality: Set<String> = emptySet(), // USER/TAGGED/VERIFIED/INFERRED/UNKNOWN

  // Pagination tuning
  /**
   * Most buckets to display, taken from the end [sort] starts at.
   *
   * Was 60, and applied in SQL at the *wrong* end — see [TimelineWindow]. Now
   * that it is applied correctly it can also afford to be generous: bucket rows
   * are four numbers each, and the limit never saved any query work, so the only
   * thing a small value bought was a truncated library. Two thousand covers a
   * century of months or five years of daily releases.
   */
  val bucketLimit: Int = 2_000,
  /**
   * Rows fetched for a bucket's preview.
   *
   * A sane floor; views that need more ask for more. Month cards distil their
   * preview into 30 unique releases, so 40 — the old value — could only fill the
   * grid if almost every track came from a different album.
   */
  val itemsPerBucket: Int = 60
)

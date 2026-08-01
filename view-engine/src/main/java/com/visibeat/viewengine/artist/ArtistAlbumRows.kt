package com.visibeat.viewengine.artist

import androidx.compose.runtime.Immutable

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
data class AlbumBucketRow(
  val bucketStartEpochMs: Long,
  val releaseCount: Int
)

@Immutable
data class AlbumCardRow(
  val releaseId: Long,
  val bucketStartEpochMs: Long,
  val albumTitle: String?,
  val albumArtistDisplay: String?,
  val effectiveReleaseDateEpochMs: Long?,
  val trackCount: Int
)

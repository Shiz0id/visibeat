package com.visibeat.viewengine

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// =====================================================
// Optional resolved cache for fast timeline + UI projections
// (Recommended once performance demands it.)
// =====================================================
@Entity(
  tableName = "resolved_tracks",
  indices = [
    Index(value = ["effectiveReleaseDateEpochMs"]),
    Index(value = ["releaseDateQuality"]),
    Index(value = ["releaseId"]),
    Index(value = ["primaryArtistId"]),
    // Library > Tracks orders every row by title, which was unindexed — a full
    // scan plus an external sort on every open, and exactly the cost that grows
    // with the size of someone's library. With this it is a covering index scan.
    //
    // There is deliberately no matching index on effectiveAlbumTitle: the albums
    // query groups by releaseId and only then orders by title, so the sort
    // survives any index on the sort column and SQLite never reads it. Verified
    // with EXPLAIN QUERY PLAN rather than assumed. That sort is over one row per
    // album rather than one per track, so it is a fraction of the cost anyway.
    Index(value = ["effectiveTitle"]),
    // The timeline groups by these. Grouping on strftime() of the epoch, which
    // is what it used to do, is unindexable by construction: every bucket query
    // was a full scan of the table plus a sort, on every emission of a Flow that
    // re-runs on any database change.
    Index(value = ["bucketDayEpochMs"]),
    Index(value = ["bucketMonthEpochMs"]),
    Index(value = ["bucketYearEpochMs"])
  ]
)
data class ResolvedTrackEntity(
  @PrimaryKey val trackId: Long,

  // Timeline ordering anchor (epoch used for ordering; truth stays in observations)
  val effectiveReleaseDateEpochMs: Long?, // normalized start-of-day/month/year anchor
  val releaseDateQuality: String,         // USER/VERIFIED/TAGGED/INFERRED/UNKNOWN

  // Fast display fields (derived from observations)
  val effectiveTitle: String?,
  val effectiveAlbumTitle: String?,
  val effectiveArtistDisplay: String?,
  val effectiveGenreDisplay: String?,

  // Resolved relationships (optional but useful)
  val releaseId: Long?,
  val primaryArtistId: Long?,
  val mediaStoreAlbumId: Long? = null,
  val mediaStoreUri: String? = null,
  val artPath: String? = null,

  /**
   * Position on the release. Both have been captured at ingest since the schema
   * was written — `parseDiscTrack` puts them in `track_release` — and nothing
   * ever read them, so an album could only ever be listed in ingest order.
   * Null for a file whose tags never said.
   */
  val discNumber: Int? = null,
  val trackNumber: Int? = null,

  /**
   * The file's MIME type, carried through so the album header can badge a
   * format without joining back to `tracks` for every row.
   */
  val mimeType: String? = null,

  /**
   * Precomputed bucket anchors: UTC midnight of the day, of the first of the
   * month, and of the first of the year that [effectiveReleaseDateEpochMs] falls
   * in. Null exactly when that is null.
   *
   * Stored rather than derived because the timeline's whole job is grouping by
   * them, and a computed grouping key cannot use an index. Writing three numbers
   * once per resolve turns every bucket query from a scan into an index scan.
   */
  val bucketDayEpochMs: Long? = null,
  val bucketMonthEpochMs: Long? = null,
  val bucketYearEpochMs: Long? = null
)

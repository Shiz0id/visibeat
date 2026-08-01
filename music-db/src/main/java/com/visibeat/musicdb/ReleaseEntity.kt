package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale
import com.visibeat.coredb.DateGranularity

@Entity(
  tableName = "releases",
  indices = [
    // Title *and* artist, and unique. Keyed on the title alone — which is what
    // this was — every "Greatest Hits", "Live" and "Demos" in the library
    // collapsed into a single release, because that is the path taken by every
    // file without MusicBrainz tags. Non-unique on top of that meant the
    // MusicBrainz branch could also add a second row for a title it already had.
    Index(value = ["titleNormalized", "artistNormalized"], unique = true),
    Index(value = ["createdAt"])
  ]
)
data class ReleaseEntity(
  @PrimaryKey(autoGenerate = true) val releaseId: Long = 0,

  // Display
  val title: String,
  val titleNormalized: String = title.trim().lowercase(Locale.US),
  /**
   * Album artist, normalised, or empty when the tags name nobody.
   *
   * Empty rather than null on purpose: SQLite treats every NULL in a unique
   * index as distinct, so a nullable column here would let untitled-artist
   * releases duplicate freely — exactly what the index exists to prevent.
   */
  val artistNormalized: String = "",
  val releaseType: String = "UNKNOWN",  // album/single/ep/compilation etc.

  // Optional convenience fields (also represented in observations)
  val primaryDateEpochMs: Long? = null,               // resolved anchor for ordering
  val primaryDateGranularity: DateGranularity = DateGranularity.NONE,

  // MusicBrainz enrichment
  val musicBrainzId: String? = null,                  // MBID for future lookups
  val dateSource: String = "LOCAL",                   // LOCAL, MUSICBRAINZ, USER

  val createdAt: Long,
  val lastSeenAt: Long,

  /**
   * How many times enrichment has tried and failed to match this release.
   *
   * Without it the candidate query cannot tell "not tried yet" from "cannot be
   * matched", so an unmatchable release stays eligible forever and occupies a
   * slot in every future batch. Fifty of those and the worker spends every run
   * re-failing the same fifty while the rest of the library waits behind them.
   */
  val enrichAttempts: Int = 0,

  /** When enrichment last tried, matched or not. */
  val lastEnrichAt: Long? = null,

  /** When genres were last pulled for this release, or null if never. */
  val genresFetchedAt: Long? = null
)

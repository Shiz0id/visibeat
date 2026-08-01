package com.visibeat.viewengine

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached resolution of a Release (Album/Single).
 * Aggregates metadata from across all tracks and observations linked to this release.
 */
@Entity(
  tableName = "resolved_releases",
  indices = [
    Index(value = ["effectiveDateEpochMs"]),
    Index(value = ["releaseDateQuality"])
  ]
)
data class ResolvedReleaseEntity(
  @PrimaryKey val releaseId: Long,

  // Display fields
  val effectiveTitle: String?,
  val effectiveArtistDisplay: String?, // "Varies" or primary artist name
  
  // Timeline ordering anchor
  val effectiveDateEpochMs: Long?,
  val releaseDateQuality: String,       // USER/VERIFIED/TAGGED/INFERRED/UNKNOWN

  // Stats
  val trackCount: Int = 0
)

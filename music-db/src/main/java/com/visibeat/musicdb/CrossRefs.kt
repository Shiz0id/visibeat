package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import com.visibeat.coredb.ArtistRole
import com.visibeat.coredb.MetaSource
import com.visibeat.coredb.Confidence

// =====================================================
// Relationship tables
// =====================================================
@Entity(
  tableName = "track_artist",
  primaryKeys = ["trackId", "artistId", "role"],
  indices = [
    Index(value = ["trackId"]),
    Index(value = ["artistId"]),
    Index(value = ["role"])
  ]
)
data class TrackArtistCrossRef(
  val trackId: Long,
  val artistId: Long,
  val role: ArtistRole,
  val source: MetaSource,
  val confidence: Confidence,
  val createdAt: Long
)

@Entity(
  tableName = "track_release",
  primaryKeys = ["trackId", "releaseId"],
  indices = [
    Index(value = ["trackId"]),
    Index(value = ["releaseId"])
  ]
)
data class TrackReleaseCrossRef(
  val trackId: Long,
  val releaseId: Long,
  val discNumber: Int? = null,
  val trackNumber: Int? = null,
  val source: MetaSource,
  val confidence: Confidence,
  val createdAt: Long
)

@Entity(
  tableName = "track_genre",
  primaryKeys = ["trackId", "genreId"],
  indices = [
    Index(value = ["trackId"]),
    Index(value = ["genreId"])
  ]
)
data class TrackGenreCrossRef(
  val trackId: Long,
  val genreId: Long,
  val source: MetaSource,
  val confidence: Confidence,
  val createdAt: Long
)

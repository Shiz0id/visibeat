package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.visibeat.coredb.IdentitySource
import com.visibeat.coredb.Confidence

// =====================================================
// Identity tables (local + MusicBrainz + Spotify)
// =====================================================
@Entity(
  tableName = "track_identities",
  indices = [
    Index(value = ["trackId"]),
    Index(value = ["source", "sourceKey"], unique = true)
  ]
)
data class TrackIdentityEntity(
  @PrimaryKey(autoGenerate = true) val identityId: Long = 0,
  val trackId: Long,
  val source: IdentitySource,
  val sourceKey: String,
  val confidence: Confidence,
  val createdAt: Long,
  val lastVerifiedAt: Long? = null
)

@Entity(
  tableName = "artist_identities",
  indices = [
    Index(value = ["artistId"]),
    Index(value = ["source", "sourceKey"], unique = true)
  ]
)
data class ArtistIdentityEntity(
  @PrimaryKey(autoGenerate = true) val identityId: Long = 0,
  val artistId: Long,
  val source: IdentitySource,
  val sourceKey: String,
  val confidence: Confidence,
  val createdAt: Long,
  val lastVerifiedAt: Long? = null
)

@Entity(
  tableName = "release_identities",
  indices = [
    Index(value = ["releaseId"]),
    Index(value = ["source", "sourceKey"], unique = true)
  ]
)
data class ReleaseIdentityEntity(
  @PrimaryKey(autoGenerate = true) val identityId: Long = 0,
  val releaseId: Long,
  val source: IdentitySource,
  val sourceKey: String,
  val confidence: Confidence,
  val createdAt: Long,
  val lastVerifiedAt: Long? = null
)

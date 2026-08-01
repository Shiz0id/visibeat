package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.visibeat.coredb.normalizeArtistName

@Entity(
  tableName = "artists",
  indices = [
    // Unique, which it was not before. The index existed but permitted
    // duplicates, so `OnConflictStrategy.IGNORE` on insert had no conflict to
    // ignore and ingest's check-then-insert could — and did — produce two rows
    // for the same artist.
    Index(value = ["displayNameNormalized"], unique = true),
    Index(value = ["createdAt"]),
    // Library > Artists orders by the display name, which was unindexed.
    Index(value = ["displayName"])
  ]
)
data class ArtistEntity(
  @PrimaryKey(autoGenerate = true) val artistId: Long = 0,
  val displayName: String,
  /**
   * Identity key: two names that normalise alike are the same artist.
   *
   * Now shares [normalizeArtistName] with the rest of the app instead of doing
   * its own `trim().lowercase()`, so spelling variants like Beyoncé/Beyonce
   * collapse to one artist rather than one row each.
   */
  val displayNameNormalized: String = normalizeArtistName(displayName),
  val createdAt: Long,
  val lastSeenAt: Long
)

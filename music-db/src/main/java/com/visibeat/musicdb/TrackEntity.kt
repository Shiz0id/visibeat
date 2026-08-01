package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.visibeat.coredb.IngestSourceType

@Entity(
  tableName = "tracks",
  indices = [
    Index(value = ["uriString"], unique = true),
    Index(value = ["ingestSourceType"]),
    Index(value = ["mediaStoreAudioId"]),
    Index(value = ["rootId"]),
    Index(value = ["addedToLibraryAt"]),
    Index(value = ["lastModifiedAt"])
  ]
)
data class TrackEntity(
  @PrimaryKey(autoGenerate = true) val trackId: Long = 0,

  // File identity (Tokarr-style canonical handle)
  val uriString: String,                 // MediaStore content URI OR SAF document URI
  val ingestSourceType: IngestSourceType,
  val mediaStoreAudioId: Long? = null,   // if MEDIASTORE
  val mediaStoreAlbumId: Long? = null,   // if MEDIASTORE (for album art)
  val rootId: Long? = null,              // if SAF_ROOT
  val documentId: String? = null,        // optional docId if you store it

  // File facts
  val fileName: String? = null,
  val mimeType: String? = null,
  val durationMs: Long? = null,
  val sizeBytes: Long? = null,
  val addedToLibraryAt: Long,            // Tokarr ingestion time
  val lastModifiedAt: Long,              // file mtime if available

  // Optional convenience (also resolvable from observations)
  val releaseIdHint: Long? = null,       // can be null until linked
  val primaryDateEpochMs: Long? = null,  // resolved release date anchor for ordering
  val primaryDateQuality: String = "UNKNOWN" // USER/VERIFIED/TAGGED/INFERRED/UNKNOWN
)

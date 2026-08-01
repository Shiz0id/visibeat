package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// =====================================================
// Core library roots (for SAF mode)
// =====================================================
@Entity(
  tableName = "library_roots",
  indices = [Index(value = ["rootUriString"], unique = true)]
)
data class LibraryRootEntity(
  @PrimaryKey(autoGenerate = true) val rootId: Long = 0,
  val rootUriString: String,   // SAF tree URI persisted
  val displayName: String?,
  val isEnabled: Boolean = true,
  val createdAt: Long,
  val lastScanAt: Long? = null
)

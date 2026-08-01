package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

@Entity(
  tableName = "genres",
  indices = [Index(value = ["nameNormalized"], unique = true)]
)
data class GenreEntity(
  @PrimaryKey(autoGenerate = true) val genreId: Long = 0,
  val name: String,
  val nameNormalized: String = name.trim().lowercase(Locale.US),
  val createdAt: Long,
  val lastSeenAt: Long
)

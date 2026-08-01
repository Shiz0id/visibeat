package com.visibeat.musicdb

import androidx.room.*

@Dao
interface GenreDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(genre: GenreEntity): Long

  @Query("SELECT * FROM genres WHERE nameNormalized = :norm LIMIT 1")
  suspend fun findByNorm(norm: String): GenreEntity?

  @Query("UPDATE genres SET lastSeenAt = :ts WHERE genreId = :id")
  suspend fun touch(id: Long, ts: Long): Int
}

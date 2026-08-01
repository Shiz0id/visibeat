package com.visibeat.musicdb

import androidx.room.*

@Dao
interface ArtistDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(artist: ArtistEntity): Long

  @Query("SELECT * FROM artists WHERE displayNameNormalized = :norm LIMIT 1")
  suspend fun findByNorm(norm: String): ArtistEntity?

  @Query("SELECT * FROM artists WHERE artistId = :id LIMIT 1")
  suspend fun getById(id: Long): ArtistEntity?

  @Query("UPDATE artists SET lastSeenAt = :ts WHERE artistId = :id")
  suspend fun touch(id: Long, ts: Long): Int

  @Query("SELECT * FROM artists ORDER BY artistId ASC")
  suspend fun listAll(): List<ArtistEntity>

  /**
   * Every artist's identity key.
   *
   * Ingest holds these in memory so the credit parser can ask "is this already
   * an artist?" for each candidate split without a query per name.
   */
  @Query("SELECT displayNameNormalized FROM artists")
  suspend fun listAllNormalized(): List<String>

  @Query("UPDATE artists SET displayName = :displayName, displayNameNormalized = :normalized WHERE artistId = :id")
  suspend fun rename(id: Long, displayName: String, normalized: String)

  @Query("DELETE FROM artists WHERE artistId = :id")
  suspend fun delete(id: Long)
}

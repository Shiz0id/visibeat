package com.visibeat.musicdb

import androidx.room.*

@Dao
interface TrackDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(track: TrackEntity): Long

  @Update
  suspend fun update(track: TrackEntity)

  @Query("SELECT * FROM tracks WHERE uriString = :uri LIMIT 1")
  suspend fun getByUri(uri: String): TrackEntity?

  @Query("SELECT * FROM tracks WHERE mediaStoreAudioId = :audioId LIMIT 1")
  suspend fun getByMediaStoreId(audioId: Long): TrackEntity?

  @Query("SELECT * FROM tracks WHERE trackId = :id LIMIT 1")
  suspend fun getById(id: Long): TrackEntity?

  /**
   * The same physical file reached by a second route — a folder scan finding
   * something MediaStore already indexed.
   *
   * Size is part of the key, not decoration. Matching on the filename alone made
   * every "01 - Intro.mp3" in the library the same track: the second album's copy
   * never got a row of its own, and the survivor kept pointing at the first
   * album's file while absorbing the second one's tags, artists and observations.
   * Two genuinely different recordings sharing both a name and an exact byte
   * count is vanishingly rare; sharing just a name is routine.
   */
  @Query("SELECT * FROM tracks WHERE fileName = :fileName AND sizeBytes = :sizeBytes LIMIT 1")
  suspend fun getByFileNameAndSize(fileName: String, sizeBytes: Long): TrackEntity?
}

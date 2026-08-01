package com.visibeat.musicdb

import androidx.room.*

@Dao
interface RelationshipDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertTrackArtist(ref: TrackArtistCrossRef)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertTrackRelease(ref: TrackReleaseCrossRef)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertTrackGenre(ref: TrackGenreCrossRef)

  @Query("SELECT * FROM track_release WHERE trackId = :trackId LIMIT 1")
  suspend fun getTrackRelease(trackId: Long): TrackReleaseCrossRef?

  @Query("SELECT * FROM track_artist WHERE trackId = :trackId")
  suspend fun listTrackArtists(trackId: Long): List<TrackArtistCrossRef>

  @Query("SELECT * FROM track_release WHERE releaseId = :releaseId")
  suspend fun listTracksForRelease(releaseId: Long): List<TrackReleaseCrossRef>
}

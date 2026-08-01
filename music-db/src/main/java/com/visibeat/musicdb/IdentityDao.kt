package com.visibeat.musicdb

import androidx.room.*
import com.visibeat.coredb.IdentitySource

@Dao
interface IdentityDao {
  // Track
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertTrackIdentity(i: TrackIdentityEntity): Long

  @Query("SELECT * FROM track_identities WHERE source = :source AND sourceKey = :key LIMIT 1")
  suspend fun findTrackIdentity(source: IdentitySource, key: String): TrackIdentityEntity?

  @Query("SELECT * FROM track_identities WHERE trackId = :trackId AND source = :source LIMIT 1")
  suspend fun getTrackIdentityFor(trackId: Long, source: IdentitySource): TrackIdentityEntity?

  @Query("SELECT * FROM track_identities WHERE source = 'MB_RECORDING' AND sourceKey = :mbRecordingId LIMIT 1")
  suspend fun findTrackByMbRecordingId(mbRecordingId: String): TrackIdentityEntity?

  // Artist
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertArtistIdentity(i: ArtistIdentityEntity): Long

  @Query("SELECT * FROM artist_identities WHERE source = :source AND sourceKey = :key LIMIT 1")
  suspend fun findArtistIdentity(source: IdentitySource, key: String): ArtistIdentityEntity?

  // Release
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertReleaseIdentity(i: ReleaseIdentityEntity): Long

  @Query("SELECT * FROM release_identities WHERE source = :source AND sourceKey = :key LIMIT 1")
  suspend fun findReleaseIdentity(source: IdentitySource, key: String): ReleaseIdentityEntity?
}

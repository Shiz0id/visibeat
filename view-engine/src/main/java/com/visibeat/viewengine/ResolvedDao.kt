package com.visibeat.viewengine

import androidx.room.*

@Dao
interface ResolvedCacheDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertResolvedTrack(r: ResolvedTrackEntity)

  @Query("SELECT * FROM resolved_tracks WHERE trackId = :trackId LIMIT 1")
  suspend fun getResolvedTrack(trackId: Long): ResolvedTrackEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertResolvedRelease(r: ResolvedReleaseEntity)

  @Query("SELECT * FROM resolved_releases WHERE releaseId = :releaseId LIMIT 1")
  suspend fun getResolvedRelease(releaseId: Long): ResolvedReleaseEntity?

  @Query("SELECT * FROM resolved_releases")
  suspend fun getAllResolvedReleases(): List<ResolvedReleaseEntity>
}

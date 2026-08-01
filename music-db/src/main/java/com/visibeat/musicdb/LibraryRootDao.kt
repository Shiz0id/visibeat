package com.visibeat.musicdb

import androidx.room.*

@Dao
interface LibraryRootDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertRoot(root: LibraryRootEntity): Long

  @Query("SELECT * FROM library_roots WHERE rootUriString = :uri LIMIT 1")
  suspend fun getByUri(uri: String): LibraryRootEntity?

  @Query("SELECT * FROM library_roots WHERE rootId = :id LIMIT 1")
  suspend fun getById(id: Long): LibraryRootEntity?

  @Query("UPDATE library_roots SET lastScanAt = :ts WHERE rootId = :rootId")
  suspend fun touchScan(rootId: Long, ts: Long): Int
}

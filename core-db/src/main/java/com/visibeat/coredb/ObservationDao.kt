package com.visibeat.coredb

import androidx.room.*

@Dao
interface ObservationDao {
  /**
   * Record something a source claimed. First observation of a given fact wins:
   * re-scanning the same file must not append another copy, and the original
   * `observedAt` is the honest one.
   */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertObservation(obs: MetadataObservationEntity): Long

  /**
   * Record something the *user* typed. Replaces rather than ignores.
   *
   * The distinction is load-bearing. Observations are unique on
   * (subjectType, subjectId, field, source, value), and [listBestFirst] breaks
   * ties within a confidence band by `observedAt DESC`. So with IGNORE, a user
   * who set a title to "Foo", changed it to "Bar", then changed it back to "Foo"
   * would have their last edit silently discarded: the old "Foo" row still
   * exists, keeps its original older timestamp, and loses the tie to "Bar"
   * forever. REPLACE re-inserts with a current timestamp, so the most recent
   * edit is the one that wins — which is the only behaviour a user can predict.
   */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertUserObservation(obs: MetadataObservationEntity): Long

  @Query("""
    SELECT * FROM metadata_observations
    WHERE subjectType = :subjectType AND subjectId = :subjectId AND field = :field
    ORDER BY 
      CASE confidence
        WHEN 'USER' THEN 0
        WHEN 'VERIFIED' THEN 1
        WHEN 'STRONG' THEN 2
        ELSE 3
      END,
      CASE source
        WHEN 'MUSICBRAINZ' THEN 0
        WHEN 'SPOTIFY' THEN 1
        WHEN 'FILE_TAG' THEN 2
        WHEN 'MEDIASTORE' THEN 3
        WHEN 'FILENAME' THEN 4
        WHEN 'FOLDER' THEN 5
        ELSE 6
      END,
      observedAt DESC
  """)
  suspend fun listBestFirst(subjectType: SubjectType, subjectId: Long, field: MetadataField): List<MetadataObservationEntity>
}

package com.visibeat.coredb

import androidx.room.*

@Dao
interface DismissalDao {
  @Query("""
    SELECT COUNT(*) FROM dismissed_suggestions
    WHERE subjectType = :subjectType AND subjectId = :subjectId
      AND field = :field AND source = :source AND valueHash = :valueHash
  """)
  suspend fun isDismissed(
    subjectType: SubjectType,
    subjectId: Long,
    field: MetadataField,
    source: MetaSource,
    valueHash: String
  ): Int
}

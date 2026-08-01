package com.visibeat.coredb

import androidx.room.Entity
import androidx.room.Index

@Entity(
  tableName = "dismissed_suggestions",
  primaryKeys = ["subjectType", "subjectId", "field", "source", "valueHash"],
  indices = [
    Index(value = ["subjectType", "subjectId", "field"]),
    Index(value = ["field", "source"])
  ]
)
data class DismissedSuggestionEntity(
  val subjectType: SubjectType,
  val subjectId: Long,
  val field: MetadataField,
  val source: MetaSource,
  val valueHash: String,     // hash(value) to avoid giant keys
  val dismissedAt: Long
)

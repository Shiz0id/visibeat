package com.visibeat.coredb

import androidx.room.TypeConverter

class Converters {
  @TypeConverter fun toSubjectType(v: String) = SubjectType.valueOf(v)
  @TypeConverter fun fromSubjectType(v: SubjectType) = v.name

  @TypeConverter fun toIngestSourceType(v: String) = IngestSourceType.valueOf(v)
  @TypeConverter fun fromIngestSourceType(v: IngestSourceType) = v.name

  @TypeConverter fun toMetaSource(v: String) = MetaSource.valueOf(v)
  @TypeConverter fun fromMetaSource(v: MetaSource) = v.name

  @TypeConverter fun toConfidence(v: String) = Confidence.valueOf(v)
  @TypeConverter fun fromConfidence(v: Confidence) = v.name

  @TypeConverter fun toIdentitySource(v: String) = IdentitySource.valueOf(v)
  @TypeConverter fun fromIdentitySource(v: IdentitySource) = v.name

  @TypeConverter fun toArtistRole(v: String) = ArtistRole.valueOf(v)
  @TypeConverter fun fromArtistRole(v: ArtistRole) = v.name

  @TypeConverter fun toDateGranularity(v: String) = DateGranularity.valueOf(v)
  @TypeConverter fun fromDateGranularity(v: DateGranularity) = v.name
}

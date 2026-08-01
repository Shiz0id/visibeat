package com.visibeat.viewengine

import com.visibeat.coredb.*
import com.visibeat.musicdb.*
import java.util.Calendar
import java.util.TimeZone

class MusicResolver(
  private val obsDao: ObservationDao,
  private val trackDao: TrackDao,
  private val artistDao: ArtistDao,
  private val releaseDao: ReleaseDao,
  private val genreDao: GenreDao,
  private val relDao: RelationshipDao,
  private val identityDao: IdentityDao
) {
  suspend fun resolveTrack(trackId: Long, releaseId: Long?, artistIdPrimary: Long?, artPath: String? = null): ResolvedTrackEntity {
    val title = bestValue(SubjectType.TRACK, trackId, MetadataField.TRACK_TITLE)
    val albumTitle = releaseId?.let { bestValue(SubjectType.RELEASE, it, MetadataField.RELEASE_TITLE) }
      ?: bestValue(SubjectType.TRACK, trackId, MetadataField.RELEASE_TITLE)

    val artistDisplay = artistIdPrimary?.let { artistDao.getById(it)?.displayName }
      ?: bestValue(SubjectType.TRACK, trackId, MetadataField.TRACK_ARTIST)

    val genre = bestValue(SubjectType.TRACK, trackId, MetadataField.GENRE)
    val trackBase = trackDao.getById(trackId)
    val mediaStoreAlbumId = trackBase?.mediaStoreAlbumId
    val mediaStoreUri = trackBase?.uriString

    // Position on the release, so albums can be listed in album order.
    val placement = relDao.getTrackRelease(trackId)

    // Default axis: Release Date
    // Priority: 1. USER observation, 2. MusicBrainz (from ReleaseEntity), 3. TAGGED/VERIFIED observations
    val userDateObs = (releaseId?.let { obsDao.listBestFirst(SubjectType.RELEASE, it, MetadataField.RELEASE_DATE).firstOrNull { it.confidence == Confidence.USER } }
      ?: obsDao.listBestFirst(SubjectType.TRACK, trackId, MetadataField.RELEASE_DATE).firstOrNull { it.confidence == Confidence.USER })

    val (epoch, quality) = if (userDateObs != null) {
      Pair(normalizeIsoToEpochAnchor(userDateObs.value, userDateObs.granularity), "USER")
    } else {
      // Check if release has MusicBrainz data (enriched) - prefer MB dates over file tags
      val mbRelease = releaseId?.let { releaseDao.getById(it) }
      if (mbRelease?.musicBrainzId != null && mbRelease.primaryDateEpochMs != null) {
        Pair(mbRelease.primaryDateEpochMs, "MUSICBRAINZ")
      } else {
        val dateObs = (releaseId?.let { bestObs(SubjectType.RELEASE, it, MetadataField.RELEASE_DATE) }
          ?: bestObs(SubjectType.TRACK, trackId, MetadataField.RELEASE_DATE))
        
        if (dateObs == null) {
          Pair(null, "UNKNOWN")
        } else {
          Pair(normalizeIsoToEpochAnchor(dateObs.value, dateObs.granularity), qualityOf(dateObs))
        }
      }
    }

    return ResolvedTrackEntity(
      trackId = trackId,
      effectiveReleaseDateEpochMs = epoch,
      releaseDateQuality = quality,
      effectiveTitle = title,
      effectiveAlbumTitle = albumTitle,
      effectiveArtistDisplay = artistDisplay,
      effectiveGenreDisplay = genre,
      releaseId = releaseId,
      primaryArtistId = artistIdPrimary,
      mediaStoreAlbumId = mediaStoreAlbumId,
      mediaStoreUri = mediaStoreUri,
      artPath = artPath,
      bucketDayEpochMs = epoch?.let { bucketAnchor(it, Calendar.DAY_OF_MONTH) },
      bucketMonthEpochMs = epoch?.let { bucketAnchor(it, Calendar.MONTH) },
      bucketYearEpochMs = epoch?.let { bucketAnchor(it, Calendar.YEAR) },
      discNumber = placement?.discNumber,
      trackNumber = placement?.trackNumber,
      mimeType = trackBase?.mimeType
    )
  }

  private suspend fun bestValue(st: SubjectType, id: Long, field: MetadataField): String? =
    bestObs(st, id, field)?.value

  private suspend fun bestObs(st: SubjectType, id: Long, field: MetadataField): MetadataObservationEntity? =
    obsDao.listBestFirst(st, id, field).firstOrNull()

  private fun qualityOf(obs: MetadataObservationEntity): String =
    when (obs.confidence) {
      Confidence.USER -> "USER"
      Confidence.VERIFIED -> if (obs.source == MetaSource.FILE_TAG) "TAGGED" else "VERIFIED"
      Confidence.STRONG -> "INFERRED"
      Confidence.WEAK -> "INFERRED"
    }

  /**
   * UTC midnight at the start of the day, month or year containing [epochMs].
   *
   * @param field [Calendar.DAY_OF_MONTH], [Calendar.MONTH] or [Calendar.YEAR] —
   *   the coarsest unit to keep.
   */
  private fun bucketAnchor(epochMs: Long, field: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epochMs
    if (field == Calendar.YEAR) cal.set(Calendar.MONTH, Calendar.JANUARY)
    if (field == Calendar.YEAR || field == Calendar.MONTH) cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
  }

  /**
   * Epoch is for ordering anchors only.
   * YEAR -> Jan 1, MONTH -> first of month, DAY -> that day. All at UTC midnight.
   */
  private fun normalizeIsoToEpochAnchor(iso: String, granularity: DateGranularity): Long? {
    val s = iso.trim()
    return try {
      val parts = s.split("-")
      val y = parts.getOrNull(0)?.toIntOrNull() ?: return null
      val m = parts.getOrNull(1)?.toIntOrNull() ?: 1
      val d = parts.getOrNull(2)?.toIntOrNull() ?: 1

      // UTC midnight
      val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
      cal.set(java.util.Calendar.YEAR, y)
      cal.set(java.util.Calendar.MONTH, (m - 1).coerceIn(0, 11))
      cal.set(java.util.Calendar.DAY_OF_MONTH, d.coerceIn(1, 31))
      cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
      cal.set(java.util.Calendar.MINUTE, 0)
      cal.set(java.util.Calendar.SECOND, 0)
      cal.set(java.util.Calendar.MILLISECOND, 0)
      cal.timeInMillis
    } catch (_: Throwable) {
      null
    }
  }
}

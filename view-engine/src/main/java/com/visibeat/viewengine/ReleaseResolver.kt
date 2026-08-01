package com.visibeat.viewengine

import com.visibeat.coredb.*
import com.visibeat.musicdb.*

class ReleaseResolver(
  private val obsDao: ObservationDao,
  private val trackDao: TrackDao,
  private val artistDao: ArtistDao,
  private val releaseDao: ReleaseDao,
  private val relDao: RelationshipDao
) {
  suspend fun resolveRelease(releaseId: Long): ResolvedReleaseEntity {
    val title = bestValue(SubjectType.RELEASE, releaseId, MetadataField.RELEASE_TITLE)
      ?: releaseDao.getById(releaseId)?.title

    // For simplicity in this toy app, we'll pick the first artist associated with any track in this release
    val tracks = relDao.listTracksForRelease(releaseId)
    val artistDisplay = if (tracks.isEmpty()) {
        "Unknown Artist"
    } else {
        val firstTrackId = tracks.first().trackId
        val artistId = relDao.listTrackArtists(firstTrackId)
            .firstOrNull { it.role == ArtistRole.ALBUM_ARTIST || it.role == ArtistRole.PRIMARY }
            ?.artistId
        
        val artistName = if (artistId != null) artistDao.getById(artistId)?.displayName else null
        artistName
            ?: bestValue(SubjectType.TRACK, firstTrackId, MetadataField.TRACK_ARTIST)
            ?: "Unknown Artist"
    }

    // Priority: 1. USER observation, 2. MusicBrainz (from ReleaseEntity), 3. TAGGED/VERIFIED observations
    val userDateObs = obsDao.listBestFirst(SubjectType.RELEASE, releaseId, MetadataField.RELEASE_DATE).firstOrNull { it.confidence == Confidence.USER }

    val (epoch, quality) = if (userDateObs != null) {
      Pair(normalizeIsoToEpochAnchor(userDateObs.value, userDateObs.granularity), "USER")
    } else {
      // Check if release has MusicBrainz data (enriched) - prefer MB dates over file tags
      val mbRelease = releaseDao.getById(releaseId)
      if (mbRelease?.musicBrainzId != null && mbRelease.primaryDateEpochMs != null) {
        Pair(mbRelease.primaryDateEpochMs, "MUSICBRAINZ")
      } else {
        val dateObs = bestObs(SubjectType.RELEASE, releaseId, MetadataField.RELEASE_DATE)
        if (dateObs == null) {
          Pair(null, "UNKNOWN")
        } else {
          Pair(normalizeIsoToEpochAnchor(dateObs.value, dateObs.granularity), qualityOf(dateObs))
        }
      }
    }

    return ResolvedReleaseEntity(
      releaseId = releaseId,
      effectiveTitle = title,
      effectiveArtistDisplay = artistDisplay,
      effectiveDateEpochMs = epoch,
      releaseDateQuality = quality,
      trackCount = tracks.size
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

  private fun normalizeIsoToEpochAnchor(iso: String, granularity: DateGranularity): Long? {
    val s = iso.trim()
    return try {
      val parts = s.split("-")
      val y = parts.getOrNull(0)?.toIntOrNull() ?: return null
      val m = parts.getOrNull(1)?.toIntOrNull() ?: 1
      val d = parts.getOrNull(2)?.toIntOrNull() ?: 1

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

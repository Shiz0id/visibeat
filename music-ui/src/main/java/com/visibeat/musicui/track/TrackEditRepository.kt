package com.visibeat.musicui.track

import androidx.compose.runtime.Stable

import androidx.room.withTransaction
import com.visibeat.coredb.*
import com.visibeat.musicdb.*
import com.visibeat.viewengine.MusicPimDb
import com.visibeat.viewengine.MusicResolver
import com.visibeat.viewengine.ResolvedCacheDao
import java.security.MessageDigest

data class TrackDetailModel(
  val trackId: Long,
  val title: String?,
  val artist: String?,
  val album: String?,
  val releaseDateRaw: String?, // best raw ISO string (partial ok)
  val genre: String?,
  val resolvedSummary: String,
  val musicBrainzId: String? = null,
  val musicBrainzDate: String? = null
)

data class MetadataEdit(val field: MetadataField, val value: String)

/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
class TrackEditRepository(
  private val db: MusicPimDb,
  private val obsDao: ObservationDao,
  private val trackDao: TrackDao,
  private val relDao: RelationshipDao,
  private val resolvedDao: ResolvedCacheDao,
  private val dismissalDao: DismissalDao,
  private val resolver: MusicResolver,
  private val releaseDao: ReleaseDao
) {
  suspend fun loadTrackDetail(trackId: Long): TrackDetailModel {
    // Pull "best" values (USER first thanks to ObservationDao ordering)
    val title = obsDao.listBestFirst(SubjectType.TRACK, trackId, MetadataField.TRACK_TITLE).firstOrNull()?.value
    val artist = obsDao.listBestFirst(SubjectType.TRACK, trackId, MetadataField.TRACK_ARTIST).firstOrNull()?.value
    val album = obsDao.listBestFirst(SubjectType.TRACK, trackId, MetadataField.RELEASE_TITLE).firstOrNull()?.value
    val dateObs = obsDao.listBestFirst(SubjectType.TRACK, trackId, MetadataField.RELEASE_DATE).firstOrNull()
    val genre = obsDao.listBestFirst(SubjectType.TRACK, trackId, MetadataField.GENRE).firstOrNull()?.value

    val releaseLink = relDao.getTrackRelease(trackId)
    val release = releaseLink?.let { releaseDao.getById(it.releaseId) }
    
    val mbDate = release?.primaryDateEpochMs?.let { epoch ->
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epoch
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        // Locale.ROOT, not the default: this is an ISO date that gets parsed
        // again, and in a locale with non-Latin digits (fa, ar-SA, some hi)
        // String.format would emit Arabic-Indic numerals and every date parser
        // downstream would reject it.
        val loc = java.util.Locale.ROOT
        when (release.primaryDateGranularity) {
          DateGranularity.YEAR -> String.format(loc, "%04d", y)
          DateGranularity.MONTH -> String.format(loc, "%04d-%02d", y, m)
          else -> String.format(loc, "%04d-%02d-%02d", y, m, d)
        }
    }

    val resolved = resolvedDao.getResolvedTrack(trackId)
    val summary = buildString {
      append(resolved?.effectiveArtistDisplay ?: artist ?: "Unknown Artist")
      append(" • ")
      append(resolved?.effectiveAlbumTitle ?: album ?: "Unknown Album")
      append(" • ")
      append(resolved?.effectiveTitle ?: title ?: "Unknown Title")
      if (resolved?.releaseDateQuality != null) {
        append("  (${resolved.releaseDateQuality})")
      }
    }

    return TrackDetailModel(
      trackId = trackId,
      title = title,
      artist = artist,
      album = album,
      releaseDateRaw = dateObs?.value,
      genre = genre,
      resolvedSummary = summary,
      musicBrainzId = release?.musicBrainzId,
      musicBrainzDate = mbDate
    )
  }

  suspend fun applyUserEdits(trackId: Long, edits: List<MetadataEdit>): Result<Unit> = runCatching {
    if (edits.isEmpty()) return@runCatching

    db.withTransaction {
      val now = System.currentTimeMillis()

      for (edit in edits) {
        // USER edits should *not* be blocked by dismissals.
        val granularity = if (isDateField(edit.field)) inferGranularity(edit.value) else DateGranularity.NONE

        // REPLACE, not IGNORE — see ObservationDao.upsertUserObservation. Setting
        // a field back to a value it once held has to count as the newest edit.
        obsDao.upsertUserObservation(
          MetadataObservationEntity(
            subjectType = SubjectType.TRACK,
            subjectId = trackId,
            field = edit.field,
            value = edit.value,
            granularity = granularity,
            source = MetaSource.USER,
            confidence = Confidence.USER,
            observedAt = now
          )
        )
      }

      // Re-resolve immediately so timeline/feed update
      val releaseLink = relDao.getTrackRelease(trackId)
      val artists = relDao.listTrackArtists(trackId)
      val primaryArtistId = artists.firstOrNull { it.role == ArtistRole.PRIMARY }?.artistId
        ?: artists.firstOrNull()?.artistId

      val resolved = resolver.resolveTrack(trackId, releaseLink?.releaseId, primaryArtistId)
      resolvedDao.upsertResolvedTrack(resolved)
    }
  }

  private fun isDateField(f: MetadataField): Boolean =
    f == MetadataField.RELEASE_DATE || f == MetadataField.ORIGINAL_RELEASE_DATE || f == MetadataField.RECORDING_DATE || f == MetadataField.YEAR

  private fun inferGranularity(raw: String): DateGranularity {
    val t = raw.trim()
    return when {
      Regex("""^\d{4}$""").matches(t) -> DateGranularity.YEAR
      Regex("""^\d{4}-\d{2}$""").matches(t) -> DateGranularity.MONTH
      Regex("""^\d{4}-\d{2}-\d{2}$""").matches(t) -> DateGranularity.DAY
      else -> DateGranularity.NONE
    }
  }
}

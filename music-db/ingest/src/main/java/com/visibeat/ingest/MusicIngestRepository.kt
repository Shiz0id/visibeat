package com.visibeat.ingest

import android.content.Context
import androidx.room.withTransaction
import com.visibeat.coredb.*
import com.visibeat.musicdb.*
import com.visibeat.viewengine.MusicPimDb
import com.visibeat.viewengine.MusicResolver
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class MusicIngestRepository(
  private val context: Context,
  private val db: MusicPimDb,
  private val trackDao: TrackDao,
  private val artistDao: ArtistDao,
  private val releaseDao: ReleaseDao,
  private val genreDao: GenreDao,
  private val identityDao: IdentityDao,
  private val relDao: RelationshipDao,
  private val obsDao: ObservationDao,
  private val dismissalDao: DismissalDao,
  private val resolvedDao: com.visibeat.viewengine.ResolvedCacheDao,
  private val resolver: MusicResolver,
  private val releaseResolver: com.visibeat.viewengine.ReleaseResolver
) {

  suspend fun upsertFromMediaStore(
    contentUriString: String,
    mediaStoreAudioId: Long,
    mediaStoreAlbumId: Long?,
    fileName: String?,
    mimeType: String?,
    durationMs: Long?,
    sizeBytes: Long?,
    fileModifiedAt: Long,
    addedToLibraryAt: Long,
    tags: TagBundle
  ): Long = db.withTransaction {
    val now = System.currentTimeMillis()

    // 1) Track upsert - Check multiple sources for existing track
    // First, check by MediaStore ID or URI
    var existing = trackDao.getByMediaStoreId(mediaStoreAudioId) ?: trackDao.getByUri(contentUriString)
    
    // If not found and we have an MB Recording ID, check by that
    if (existing == null && tags.mbRecordingId != null) {
      val mbIdentity = identityDao.findTrackByMbRecordingId(tags.mbRecordingId!!)
      if (mbIdentity != null) {
        existing = trackDao.getById(mbIdentity.trackId)
      }
    }

    // If still not found, check by file name *and* size, to recognise a file a
    // folder scan already ingested.
    //
    // A SAF document URI and a MediaStore content URI for the same file share
    // nothing — different schemes, different ids — so neither lookup above can
    // see across the two sources. The SAF path has had this fallback all along,
    // with a comment saying it cross-references MediaStore; it was only ever
    // implemented in one direction. So scanning a folder and *then* running a
    // MediaStore scan inserted a second row for every single track, which is how
    // an artist ends up with exactly twice the tracks they should have.
    if (existing == null && fileName != null && sizeBytes != null) {
      existing = trackDao.getByFileNameAndSize(fileName, sizeBytes)
    }

    val trackId = if (existing == null) {
      val id = trackDao.insert(
        TrackEntity(
          uriString = contentUriString,
          ingestSourceType = IngestSourceType.MEDIASTORE,
          mediaStoreAudioId = mediaStoreAudioId,
          mediaStoreAlbumId = mediaStoreAlbumId,
          rootId = null,
          documentId = null,
          fileName = fileName,
          mimeType = mimeType,
          durationMs = durationMs,
          sizeBytes = sizeBytes,
          addedToLibraryAt = addedToLibraryAt,
          lastModifiedAt = fileModifiedAt
        )
      )
      if (id == -1L) trackDao.getByUri(contentUriString)!!.trackId else id
    } else {
      // keep uri stable; update facts
      trackDao.update(
        existing.copy(
          uriString = contentUriString,
          // The row may have arrived from a folder scan. Its URI is now a
          // MediaStore one, so the source type has to agree or the record
          // describes a track that does not exist.
          ingestSourceType = IngestSourceType.MEDIASTORE,
          mediaStoreAudioId = mediaStoreAudioId,
          mediaStoreAlbumId = mediaStoreAlbumId,
          fileName = fileName,
          mimeType = mimeType,
          durationMs = durationMs,
          sizeBytes = sizeBytes,
          lastModifiedAt = fileModifiedAt
        )
      )
      existing.trackId
    }

    // 2) Local identities (always)
    ensureTrackIdentity(trackId, IdentitySource.LOCAL_URI, contentUriString, Confidence.VERIFIED, now)
    ensureTrackIdentity(trackId, IdentitySource.MEDIASTORE_ID, mediaStoreAudioId.toString(), Confidence.VERIFIED, now)

    // 3) External identities (Picard)
    ensureMbIdentities(trackId, tags, now)

    // 4) Upsert domain objects (Release/Artist) and relationships
    val releaseId = upsertReleaseFromTags(tags, now)
    val artistIdPrimary = upsertArtistsAndRelations(trackId, tags, now)

    if (releaseId != null) {
      val (disc, trackNo) = parseDiscTrack(tags.discNumberRaw, tags.trackNumberRaw)
      relDao.upsertTrackRelease(
        TrackReleaseCrossRef(
          trackId = trackId,
          releaseId = releaseId,
          discNumber = disc,
          trackNumber = trackNo,
          source = MetaSource.FILE_TAG,
          confidence = Confidence.VERIFIED,
          createdAt = now
        )
      )
    }

    // 5) Observations (wide ID3)
    writeObservationsForTrack(trackId, tags, now)

    // Optionally: write release observations too (album-level fields)
    if (releaseId != null) {
      writeObservationsForRelease(releaseId, tags, now)
      
      // 7) Resolve + cache release
      val resolvedRelease = releaseResolver.resolveRelease(releaseId)
      resolvedDao.upsertResolvedRelease(resolvedRelease)
    }

    // 6) Genre entity + crossref (optional but useful)
    tags.genre?.trim()?.takeIf { it.isNotBlank() }?.let { g ->
      val genreId = upsertGenre(g, now)
      relDao.upsertTrackGenre(
        TrackGenreCrossRef(
          trackId = trackId,
          genreId = genreId,
          source = MetaSource.FILE_TAG,
          confidence = Confidence.VERIFIED,
          createdAt = now
        )
      )
    }

    // 7) Save embedded art if available
    val artPath = if (releaseId != null) saveEmbeddedArt(releaseId, tags.embeddedArtBytes) else null

    // 8) Resolve + cache for timeline/view engine
    val resolved = resolver.resolveTrack(trackId, releaseId, artistIdPrimary, artPath)
    resolvedDao.upsertResolvedTrack(resolved)

    trackId
  }

  suspend fun upsertFromSaf(
    documentUriString: String,
    rootId: Long,
    fileName: String?,
    mimeType: String?,
    durationMs: Long?,
    sizeBytes: Long?,
    fileModifiedAt: Long,
    addedToLibraryAt: Long,
    tags: TagBundle
  ): Long = db.withTransaction {
    val now = System.currentTimeMillis()

    // Check multiple sources for existing track
    // First, check by URI
    var existing = trackDao.getByUri(documentUriString)
    
    // If not found and we have an MB Recording ID, check by that
    if (existing == null && tags.mbRecordingId != null) {
      val mbIdentity = identityDao.findTrackByMbRecordingId(tags.mbRecordingId!!)
      if (mbIdentity != null) {
        existing = trackDao.getById(mbIdentity.trackId)
      }
    }
    
    // If still not found, check by file name *and* size (cross-reference with
    // MediaStore). Both halves are required — see getByFileNameAndSize.
    if (existing == null && fileName != null && sizeBytes != null) {
      existing = trackDao.getByFileNameAndSize(fileName, sizeBytes)
    }
    
    val trackId = if (existing == null) {
      val id = trackDao.insert(
        TrackEntity(
          uriString = documentUriString,
          ingestSourceType = IngestSourceType.SAF_ROOT,
          mediaStoreAudioId = null,
          rootId = rootId,
          documentId = null,
          fileName = fileName,
          mimeType = mimeType,
          durationMs = durationMs,
          sizeBytes = sizeBytes,
          addedToLibraryAt = addedToLibraryAt,
          lastModifiedAt = fileModifiedAt
        )
      )
      if (id == -1L) trackDao.getByUri(documentUriString)!!.trackId else id
    } else {
      // Update existing track with SAF info if it came from MediaStore
      trackDao.update(
        existing.copy(
          rootId = rootId,
          fileName = fileName,
          mimeType = mimeType,
          durationMs = durationMs,
          sizeBytes = sizeBytes,
          lastModifiedAt = fileModifiedAt
        )
      )
      existing.trackId
    }

    ensureTrackIdentity(trackId, IdentitySource.LOCAL_URI, documentUriString, Confidence.VERIFIED, now)
    ensureTrackIdentity(trackId, IdentitySource.DOCUMENT_URI, documentUriString, Confidence.VERIFIED, now)

    ensureMbIdentities(trackId, tags, now)

    val releaseId = upsertReleaseFromTags(tags, now)
    val artistIdPrimary = upsertArtistsAndRelations(trackId, tags, now)

    if (releaseId != null) {
      val (disc, trackNo) = parseDiscTrack(tags.discNumberRaw, tags.trackNumberRaw)
      relDao.upsertTrackRelease(
        TrackReleaseCrossRef(
          trackId = trackId,
          releaseId = releaseId,
          discNumber = disc,
          trackNumber = trackNo,
          source = MetaSource.FILE_TAG,
          confidence = Confidence.VERIFIED,
          createdAt = now
        )
      )
    }

    writeObservationsForTrack(trackId, tags, now)
    if (releaseId != null) {
      writeObservationsForRelease(releaseId, tags, now)
      
      // Resolve + cache release
      val resolvedRelease = releaseResolver.resolveRelease(releaseId)
      resolvedDao.upsertResolvedRelease(resolvedRelease)
    }

    tags.genre?.trim()?.takeIf { it.isNotBlank() }?.let { g ->
      val genreId = upsertGenre(g, now)
      relDao.upsertTrackGenre(
        TrackGenreCrossRef(trackId, genreId, MetaSource.FILE_TAG, Confidence.VERIFIED, now)
      )
    }

    val artPath = if (releaseId != null) saveEmbeddedArt(releaseId, tags.embeddedArtBytes) else null
    val resolved = resolver.resolveTrack(trackId, releaseId, artistIdPrimary, artPath)
    resolvedDao.upsertResolvedTrack(resolved)

    trackId
  }

  // -------------------------
  // Album art persistence
  // -------------------------
  private fun saveEmbeddedArt(releaseId: Long, artBytes: ByteArray?): String? {
    val artDir = File(context.filesDir, "albumart")
    val artFile = File(artDir, "$releaseId.jpg")
    // Already extracted for this release
    if (artFile.exists()) return artFile.absolutePath
    // No art to save
    if (artBytes == null || artBytes.isEmpty()) return null
    return try {
      artDir.mkdirs()
      artFile.writeBytes(artBytes)
      artFile.absolutePath
    } catch (_: Exception) {
      null
    }
  }

  // -------------------------
  // Helpers
  // -------------------------
  private suspend fun ensureTrackIdentity(
    trackId: Long,
    source: IdentitySource,
    key: String,
    confidence: Confidence,
    now: Long
  ) {
    val existing = identityDao.getTrackIdentityFor(trackId, source)
    if (existing == null) {
      identityDao.insertTrackIdentity(
        TrackIdentityEntity(
          trackId = trackId,
          source = source,
          sourceKey = key,
          confidence = confidence,
          createdAt = now,
          lastVerifiedAt = if (confidence == Confidence.VERIFIED) now else null
        )
      )
    }
  }

  private suspend fun ensureMbIdentities(trackId: Long, tags: TagBundle, now: Long) {
    tags.mbRecordingId?.let { ensureTrackIdentity(trackId, IdentitySource.MB_RECORDING, it, Confidence.VERIFIED, now) }
    tags.mbReleaseId?.let { ensureTrackIdentity(trackId, IdentitySource.MB_RELEASE, it, Confidence.VERIFIED, now) }
    tags.mbReleaseGroupId?.let { ensureTrackIdentity(trackId, IdentitySource.MB_RELEASE_GROUP, it, Confidence.VERIFIED, now) }
  }

  /**
   * The artist half of a release's identity.
   *
   * Album artist first, falling back to the track artist, which is the rule
   * MediaStore itself uses. A compilation tagged without an ALBUMARTIST will
   * therefore split into one release per guest — visible, and fixable by
   * tagging it "Various Artists" — where keying on the title alone silently
   * merged genuinely different albums, which was neither.
   */
  private fun releaseArtistKey(tags: TagBundle): String =
    normalizeArtistName(tags.albumArtist ?: tags.artist ?: "")

  private suspend fun upsertReleaseFromTags(tags: TagBundle, now: Long): Long? {
    val artistNorm = releaseArtistKey(tags)

    // Prefer MB release identity
    tags.mbReleaseId?.let { mbid ->
      val existing = identityDao.findReleaseIdentity(IdentitySource.MB_RELEASE, mbid)
      if (existing != null) {
        releaseDao.touch(existing.releaseId, now)
        return existing.releaseId
      }

      // Create release row + identity
      val title = tags.album?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown Album"
      val titleNorm = title.lowercase(Locale.US)
      val releaseId = releaseDao.insert(
        ReleaseEntity(
          title = title,
          artistNormalized = artistNorm,
          releaseType = "UNKNOWN",
          primaryDateEpochMs = null,
          primaryDateGranularity = DateGranularity.NONE,
          createdAt = now,
          lastSeenAt = now
        )
      ).let {
        // -1 means a release with this identity already exists — created by a
        // sibling track that had no MusicBrainz tags. Adopt it and hang the
        // MBID off it, rather than starting a second row for the same album.
        if (it == -1L) {
          releaseDao.findByTitleAndArtist(titleNorm, artistNorm)?.releaseId ?: return null
        } else it
      }

      identityDao.insertReleaseIdentity(
        ReleaseIdentityEntity(
          releaseId = releaseId,
          source = IdentitySource.MB_RELEASE,
          sourceKey = mbid,
          confidence = Confidence.VERIFIED,
          createdAt = now,
          lastVerifiedAt = now
        )
      )

      // Optional: attach Release Group ID identity if present
      tags.mbReleaseGroupId?.let { rg ->
        identityDao.insertReleaseIdentity(
          ReleaseIdentityEntity(
            releaseId = releaseId,
            source = IdentitySource.MB_RELEASE_GROUP,
            sourceKey = rg,
            confidence = Confidence.VERIFIED,
            createdAt = now,
            lastVerifiedAt = now
          )
        )
      }

      return releaseId
    }

    // Fallback: identity from the tags — album title plus album artist.
    val title = tags.album?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val norm = title.lowercase(Locale.US)
    val existingByName = releaseDao.findByTitleAndArtist(norm, artistNorm)
    if (existingByName != null) {
      releaseDao.touch(existingByName.releaseId, now)
      return existingByName.releaseId
    }

    val releaseId = releaseDao.insert(
      ReleaseEntity(
        title = title,
        artistNormalized = artistNorm,
        releaseType = "UNKNOWN",
        primaryDateEpochMs = null,
        primaryDateGranularity = DateGranularity.NONE,
        createdAt = now,
        lastSeenAt = now
      )
    )
    // The index is unique now, so a lost race gets -1 back and re-reads rather
    // than quietly creating a second row for the same album.
    return if (releaseId == -1L) {
      releaseDao.findByTitleAndArtist(norm, artistNorm)?.releaseId
    } else releaseId
  }

  private suspend fun upsertArtistsAndRelations(trackId: Long, tags: TagBundle, now: Long): Long? {
    // The artist tag is a *credit*, not necessarily one artist. Previously the
    // whole string became a single artist, which is why the library grew rows
    // for "24kGoldn, DaBaby" and "20syl feat. Oddisee" alongside the real acts.
    val knownNames = knownArtistNames()
    val credit = ArtistCreditParser.parse(tags.artist) { isKnownArtistName(knownNames, it) }

    // Primary artist: prefer MB artist identity, fallback to the parsed lead
    val primaryArtistId = upsertArtist(credit?.primary ?: tags.artist, tags.mbArtistId, now)
    if (primaryArtistId != null) {
      relDao.upsertTrackArtist(
        TrackArtistCrossRef(
          trackId = trackId,
          artistId = primaryArtistId,
          role = ArtistRole.PRIMARY,
          source = MetaSource.FILE_TAG,
          confidence = Confidence.VERIFIED,
          createdAt = now
        )
      )
    }

    // Featured artists. ArtistRole.FEATURED has existed since the schema was
    // written and nothing ever wrote one, so a guest appearance was invisible
    // to the artist's own page.
    credit?.featured?.forEach { featuredName ->
      val featuredId = resolveArtistByName(featuredName, now) ?: return@forEach
      if (featuredId == primaryArtistId) return@forEach
      relDao.upsertTrackArtist(
        TrackArtistCrossRef(
          trackId = trackId,
          artistId = featuredId,
          role = ArtistRole.FEATURED,
          source = MetaSource.FILE_TAG,
          // Inferred by splitting a string, not read from a dedicated tag.
          confidence = Confidence.STRONG,
          createdAt = now
        )
      )
    }

    // Album artist
    val albumArtistId = upsertArtist(tags.albumArtist, tags.mbAlbumArtistId, now)
    if (albumArtistId != null) {
      relDao.upsertTrackArtist(
        TrackArtistCrossRef(
          trackId = trackId,
          artistId = albumArtistId,
          role = ArtistRole.ALBUM_ARTIST,
          source = MetaSource.FILE_TAG,
          confidence = Confidence.VERIFIED,
          createdAt = now
        )
      )
    }

    return primaryArtistId ?: albumArtistId
  }

  private suspend fun upsertArtist(nameRaw: String?, mbArtistId: String?, now: Long): Long? {
    mbArtistId?.let { mbid ->
      val existing = identityDao.findArtistIdentity(IdentitySource.MB_ARTIST, mbid)
      if (existing != null) {
        artistDao.touch(existing.artistId, now)
        return existing.artistId
      }

      // Resolve by name *before* inserting. Previously this branch inserted
      // unconditionally, so one track tagged with a MusicBrainz artist id and
      // another without produced two rows for the same artist — the direct
      // cause of duplicate entries in the artist list.
      val name = nameRaw?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
      val artistId = resolveArtistByName(name, now)
        ?: return@let

      identityDao.insertArtistIdentity(
        ArtistIdentityEntity(
          artistId = artistId,
          source = IdentitySource.MB_ARTIST,
          sourceKey = mbid,
          confidence = Confidence.VERIFIED,
          createdAt = now,
          lastVerifiedAt = now
        )
      )
      return artistId
    }

    return resolveArtistByName(nameRaw, now)
  }

  /**
   * Finds or creates the artist for a single name.
   *
   * The insert can still lose a race with a concurrent scan, but the normalised
   * name is unique now, so the loser gets -1 back and re-reads rather than
   * silently creating a second row.
   */
  private suspend fun resolveArtistByName(nameRaw: String?, now: Long): Long? {
    val name = ArtistCreditParser.clean(nameRaw) ?: return null
    val norm = normalizeArtistName(name)
    artistDao.findByNorm(norm)?.let {
      artistDao.touch(it.artistId, now)
      return it.artistId
    }
    val inserted = artistDao.insert(
      ArtistEntity(displayName = name, createdAt = now, lastSeenAt = now)
    )
    knownArtistNames?.add(norm)
    return if (inserted == -1L) artistDao.findByNorm(norm)?.artistId else inserted
  }

  /**
   * Identity keys of every artist already in the library.
   *
   * Held in memory for the duration of a scan because the credit parser asks
   * about each candidate split synchronously, and because a per-name query
   * would mean several extra round trips per track. Seeded once, then kept
   * current as [resolveArtistByName] creates artists.
   */
  private var knownArtistNames: MutableSet<String>? = null

  private suspend fun knownArtistNames(): MutableSet<String> =
    knownArtistNames ?: artistDao.listAllNormalized().toMutableSet().also {
      knownArtistNames = it
    }

  /**
   * Whether a name already exists as an artist in its own right.
   *
   * This is what lets the credit parser split "24kGoldn, DaBaby" — it only
   * takes a comma apart when the lead is somebody the library already knows.
   */
  private fun isKnownArtistName(cache: Set<String>, name: String): Boolean =
    normalizeArtistName(name) in cache

  private suspend fun upsertGenre(nameRaw: String, now: Long): Long {
    val norm = nameRaw.trim().lowercase(Locale.US)
    val existing = genreDao.findByNorm(norm)
    if (existing != null) {
      genreDao.touch(existing.genreId, now)
      return existing.genreId
    }
    val inserted = genreDao.insert(GenreEntity(name = nameRaw.trim(), createdAt = now, lastSeenAt = now))
    return if (inserted == -1L) genreDao.findByNorm(norm)!!.genreId else inserted
  }

  private suspend fun writeObservationsForTrack(trackId: Long, tags: TagBundle, now: Long) {
    val subjectType = SubjectType.TRACK

    // Core
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.TRACK_TITLE, tags.title, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.TRACK_ARTIST, tags.artist, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.TRACK_ALBUM_ARTIST, tags.albumArtist, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.RELEASE_TITLE, tags.album, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.GENRE, tags.genre, DateGranularity.NONE, now)

    // Numbers (raw + parsed-friendly)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.TRACK_NUMBER, tags.trackNumberRaw, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.DISC_NUMBER, tags.discNumberRaw, DateGranularity.NONE, now)

    // Dates
    tags.releaseDateRaw?.let {
      val g = dateGranularity(it)
      insertObsIfNotDismissed(subjectType, trackId, MetadataField.RELEASE_DATE, it, g, now)
    }
    tags.originalReleaseDateRaw?.let {
      val g = dateGranularity(it)
      insertObsIfNotDismissed(subjectType, trackId, MetadataField.ORIGINAL_RELEASE_DATE, it, g, now)
    }

    // IDs
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.ISRC, tags.isrc, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.BARCODE, tags.barcode, DateGranularity.NONE, now)

    // MusicBrainz as observations too (optional but handy for debugging)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.MB_RECORDING_ID, tags.mbRecordingId, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.MB_RELEASE_ID, tags.mbReleaseId, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.MB_RELEASE_GROUP_ID, tags.mbReleaseGroupId, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.MB_ARTIST_ID, tags.mbArtistId, DateGranularity.NONE, now)
    insertObsIfNotDismissed(subjectType, trackId, MetadataField.MB_ALBUM_ARTIST_ID, tags.mbAlbumArtistId, DateGranularity.NONE, now)

    // Extra frames: map “most ID3 tags” through a mapping table
    for ((k, v) in tags.extra) {
      val mapped = Id3FieldMap.mapExtraKeyToField(k) ?: MetadataField.CUSTOM_TAG
      insertObsIfNotDismissed(subjectType, trackId, mapped, if (mapped == MetadataField.CUSTOM_TAG) "$k=$v" else v, DateGranularity.NONE, now)
    }
  }

  private suspend fun writeObservationsForRelease(releaseId: Long, tags: TagBundle, now: Long) {
    val subjectType = SubjectType.RELEASE
    insertObsIfNotDismissed(subjectType, releaseId, MetadataField.RELEASE_TITLE, tags.album, DateGranularity.NONE, now)
    tags.releaseDateRaw?.let {
      insertObsIfNotDismissed(subjectType, releaseId, MetadataField.RELEASE_DATE, it, dateGranularity(it), now)
    }
    tags.mbReleaseId?.let {
      insertObsIfNotDismissed(subjectType, releaseId, MetadataField.MB_RELEASE_ID, it, DateGranularity.NONE, now)
    }
    tags.mbReleaseGroupId?.let {
      insertObsIfNotDismissed(subjectType, releaseId, MetadataField.MB_RELEASE_GROUP_ID, it, DateGranularity.NONE, now)
    }
  }

  private suspend fun insertObsIfNotDismissed(
    subjectType: SubjectType,
    subjectId: Long,
    field: MetadataField,
    value: String?,
    granularity: DateGranularity,
    now: Long
  ) {
    val v = value?.trim()?.takeIf { it.isNotBlank() } ?: return
    val hash = sha1(v)
    val dismissed = dismissalDao.isDismissed(subjectType, subjectId, field, MetaSource.FILE_TAG, hash) > 0
    if (dismissed) return

    obsDao.insertObservation(
      MetadataObservationEntity(
        subjectType = subjectType,
        subjectId = subjectId,
        field = field,
        value = v,
        granularity = granularity,
        source = MetaSource.FILE_TAG,
        confidence = Confidence.VERIFIED,
        observedAt = now
      )
    )
  }

  private fun parseDiscTrack(discRaw: String?, trackRaw: String?): Pair<Int?, Int?> {
    fun parseLeadingInt(raw: String?): Int? {
      val t = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
      val first = t.split("/", "-", " ").firstOrNull()?.trim() ?: return null
      return first.toIntOrNull()
    }
    return Pair(parseLeadingInt(discRaw), parseLeadingInt(trackRaw))
  }

  private fun dateGranularity(iso: String): DateGranularity {
    val t = iso.trim()
    return when {
      Regex("""^\d{4}$""").matches(t) -> DateGranularity.YEAR
      Regex("""^\d{4}-\d{2}$""").matches(t) -> DateGranularity.MONTH
      Regex("""^\d{4}-\d{2}-\d{2}$""").matches(t) -> DateGranularity.DAY
      else -> DateGranularity.NONE
    }
  }

  private fun sha1(s: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }
}

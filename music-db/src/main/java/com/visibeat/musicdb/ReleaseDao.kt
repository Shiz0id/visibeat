package com.visibeat.musicdb

import androidx.room.*
import com.visibeat.coredb.DateGranularity

@Dao
interface ReleaseDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(release: ReleaseEntity): Long

  /**
   * The release identity lookup. Both halves of the key, because a title alone
   * is not one — see the unique index on [ReleaseEntity].
   */
  @Query("SELECT * FROM releases WHERE titleNormalized = :titleNorm AND artistNormalized = :artistNorm LIMIT 1")
  suspend fun findByTitleAndArtist(titleNorm: String, artistNorm: String): ReleaseEntity?

  @Query("SELECT * FROM releases WHERE releaseId = :id LIMIT 1")
  suspend fun getById(id: Long): ReleaseEntity?

  @Query("UPDATE releases SET lastSeenAt = :ts WHERE releaseId = :id")
  suspend fun touch(id: Long, ts: Long): Int

  @Query("SELECT releaseId FROM releases")
  suspend fun listAllIds(): List<Long>

  // ----- MusicBrainz Enrichment -----

  /**
   * Get releases that need date enrichment:
   * - dateSource is LOCAL (not yet enriched)
   * - Only have YEAR granularity (likely Jan 1st placeholder)
   */
  @Query("""
    SELECT * FROM releases 
    WHERE dateSource = 'LOCAL' 
    AND primaryDateGranularity = :yearGranularity
    AND primaryDateEpochMs IS NOT NULL
    LIMIT :limit
  """)
  suspend fun getReleasesNeedingEnrichment(
    yearGranularity: DateGranularity = DateGranularity.YEAR,
    limit: Int = 50
  ): List<ReleaseEntity>

  /**
   * Update release with MusicBrainz data.
   */
  @Query("""
    UPDATE releases SET 
      primaryDateEpochMs = :dateEpochMs,
      primaryDateGranularity = :granularity,
      musicBrainzId = :mbid,
      dateSource = :source,
      -- COALESCE so a release MusicBrainz matched but gave no type for keeps
      -- whatever it already had, rather than being blanked back to UNKNOWN.
      releaseType = COALESCE(:releaseType, releaseType)
    WHERE releaseId = :releaseId
  """)
  suspend fun updateWithMusicBrainzData(
    releaseId: Long,
    dateEpochMs: Long,
    granularity: DateGranularity,
    mbid: String,
    releaseType: String?,
    source: String = "MUSICBRAINZ"
  ): Int

  /**
   * Get releases needing enrichment WITH artist name.
   * Joins: releases → track_release → track_artist → artists
   * Returns first PRIMARY artist found for each release.
   * 
   * Criteria: dateSource is LOCAL (not yet enriched by MusicBrainz)
   */
  @Query("""
    SELECT DISTINCT
      r.releaseId,
      r.title AS albumTitle,
      a.displayName AS artistName,
      r.primaryDateEpochMs
    FROM releases r
    INNER JOIN track_release tr ON tr.releaseId = r.releaseId
    INNER JOIN track_artist ta ON ta.trackId = tr.trackId AND ta.role = 'PRIMARY'
    INNER JOIN artists a ON a.artistId = ta.artistId
    WHERE (r.dateSource IS NULL OR r.dateSource = 'LOCAL')
    AND r.musicBrainzId IS NULL
    AND r.enrichAttempts < :maxAttempts
    GROUP BY r.releaseId
    ORDER BY r.enrichAttempts ASC, r.releaseId ASC
    LIMIT :limit
  """)
  suspend fun getReleasesForEnrichment(
    limit: Int = 50,
    maxAttempts: Int = MAX_ENRICH_ATTEMPTS
  ): List<ReleaseForEnrichment>

  /**
   * Records that enrichment tried, whatever the outcome.
   *
   * Called on every attempt rather than only on failure, because a match clears
   * the release from the queue by setting musicBrainzId — so the counter only
   * ever accumulates against releases that genuinely could not be matched.
   */
  @Query("UPDATE releases SET enrichAttempts = enrichAttempts + 1, lastEnrichAt = :at WHERE releaseId = :releaseId")
  suspend fun markEnrichAttempt(releaseId: Long, at: Long)

  /** Lets the user reopen the queue after fixing tags or changing their mind. */
  @Query("UPDATE releases SET enrichAttempts = 0 WHERE musicBrainzId IS NULL")
  suspend fun resetEnrichAttempts(): Int

  /** Releases matched to MusicBrainz whose genres have not been pulled yet. */
  @Query("""
    SELECT releaseId, musicBrainzId AS mbid FROM releases
    WHERE musicBrainzId IS NOT NULL AND musicBrainzId != ''
    AND genresFetchedAt IS NULL
    ORDER BY releaseId ASC
    LIMIT :limit
  """)
  suspend fun getReleasesForGenreFetch(limit: Int = 100): List<ReleaseForGenres>

  /**
   * The album artist behind a release, for the artist-genre fallback.
   *
   * PRIMARY role, and the lowest artist id when a release has several, so the
   * answer is stable rather than whichever row the join happened to reach.
   */
  @Query("""
    SELECT MIN(ai.sourceKey) FROM track_release tr
    INNER JOIN track_artist ta ON ta.trackId = tr.trackId AND ta.role = 'PRIMARY'
    INNER JOIN artist_identities ai ON ai.artistId = ta.artistId AND ai.source = 'MB_ARTIST'
    WHERE tr.releaseId = :releaseId
  """)
  suspend fun artistMbidForRelease(releaseId: Long): String?

  /**
   * Artists whose tracks still have no genre.
   *
   * The pass that actually closes the gap. Measured on a real library, 280 of
   * the 298 un-genred tracks belonged to releases MusicBrainz could not match
   * at all — so a genre lookup keyed on the release could never reach them, no
   * matter how well it worked. Their artists are a different question, and one
   * MusicBrainz answers well: 26 artists covered every one of those tracks.
   *
   * `mbid` is null when the artist has not been resolved yet, which is most of
   * them — resolving artists is gated behind a Wi-Fi-only worker that has
   * rarely run.
   */
  @Query("""
    SELECT a.artistId AS artistId, a.displayName AS name,
           (SELECT ai.sourceKey FROM artist_identities ai
            WHERE ai.artistId = a.artistId AND ai.source = 'MB_ARTIST' LIMIT 1) AS mbid,
           COUNT(*) AS missing
    FROM resolved_tracks rt
    INNER JOIN artists a ON a.artistId = rt.primaryArtistId
    WHERE rt.effectiveGenreDisplay IS NULL OR rt.effectiveGenreDisplay = ''
    GROUP BY a.artistId
    ORDER BY missing DESC
    LIMIT :limit
  """)
  suspend fun getArtistsNeedingGenres(limit: Int = 60): List<ArtistNeedingGenres>

  /** The release a track belongs to, for re-resolving after a genre write. */
  @Query("SELECT releaseId FROM track_release WHERE trackId = :trackId LIMIT 1")
  suspend fun releaseIdForTrack(trackId: Long): Long?

  /** That artist's tracks which still have no genre. */
  @Query("""
    SELECT trackId FROM resolved_tracks
    WHERE primaryArtistId = :artistId
    AND (effectiveGenreDisplay IS NULL OR effectiveGenreDisplay = '')
  """)
  suspend fun unGenredTrackIdsForArtist(artistId: Long): List<Long>

  /** Lets a genre re-fetch reach releases that came back empty. */
  @Query("""
    UPDATE releases SET genresFetchedAt = NULL
    WHERE genresFetchedAt IS NOT NULL AND releaseId NOT IN (
      SELECT DISTINCT tr.releaseId FROM track_release tr
      INNER JOIN metadata_observations o
        ON o.subjectId = tr.trackId AND o.field = 'GENRE' AND o.source = 'MUSICBRAINZ'
    )
  """)
  suspend fun reopenEmptyGenreFetches(): Int

  /** Releases still to match, plus matched ones still needing genres. */
  @Query("""
    SELECT
      (SELECT COUNT(*) FROM releases WHERE musicBrainzId IS NULL AND enrichAttempts < :maxAttempts)
      +
      (SELECT COUNT(*) FROM releases WHERE musicBrainzId IS NOT NULL AND musicBrainzId != '' AND genresFetchedAt IS NULL)
  """)
  suspend fun remainingEnrichmentWork(maxAttempts: Int = MAX_ENRICH_ATTEMPTS): Int

  @Query("UPDATE releases SET genresFetchedAt = :at WHERE releaseId = :releaseId")
  suspend fun markGenresFetched(releaseId: Long, at: Long)

  /** Every track on a release, for writing one genre observation each. */
  @Query("SELECT trackId FROM track_release WHERE releaseId = :releaseId")
  suspend fun trackIdsForRelease(releaseId: Long): List<Long>

  @Query("""
    SELECT
      (SELECT COUNT(*) FROM releases) AS total,
      (SELECT COUNT(*) FROM releases WHERE musicBrainzId IS NOT NULL AND musicBrainzId != '') AS matched,
      (SELECT COUNT(*) FROM releases WHERE musicBrainzId IS NULL AND enrichAttempts >= :maxAttempts) AS givenUp,
      (SELECT COUNT(*) FROM releases WHERE musicBrainzId IS NOT NULL AND musicBrainzId != '' AND genresFetchedAt IS NOT NULL) AS genresDone
  """)
  suspend fun enrichmentProgress(maxAttempts: Int = MAX_ENRICH_ATTEMPTS): EnrichmentProgress

  /**
   * Debug: count all releases
   */
  @Query("SELECT COUNT(*) FROM releases")
  suspend fun countAllReleases(): Int
  
  /**
   * Debug: count releases eligible for enrichment
   */
  @Query("""
    SELECT COUNT(DISTINCT r.releaseId)
    FROM releases r
    INNER JOIN track_release tr ON tr.releaseId = r.releaseId
    INNER JOIN track_artist ta ON ta.trackId = tr.trackId AND ta.role = 'PRIMARY'
    WHERE (r.dateSource IS NULL OR r.dateSource = 'LOCAL')
    AND r.musicBrainzId IS NULL
  """)
  suspend fun countReleasesForEnrichment(): Int
}

/**
 * DTO for enrichment queries - release with artist name.
 */
data class ReleaseForEnrichment(
  val releaseId: Long,
  val albumTitle: String,
  val artistName: String,
  val primaryDateEpochMs: Long?
)

/**
 * Tries before a release is left alone.
 *
 * Three, not one: a match can fail for reasons that pass — the network, a
 * MusicBrainz outage, a rate-limit rejection — and giving up on the first
 * failure would write off releases that were only unlucky.
 */
const val MAX_ENRICH_ATTEMPTS = 3

/** A release ready for a genre lookup. */
data class ReleaseForGenres(val releaseId: Long, val mbid: String)

/** Counts behind the Settings readout. */
data class EnrichmentProgress(
    val total: Int,
    val matched: Int,
    val givenUp: Int,
    val genresDone: Int
)

/** An artist with tracks still lacking a genre. */
data class ArtistNeedingGenres(
    val artistId: Long,
    val name: String,
    /** Null when the artist has never been resolved to MusicBrainz. */
    val mbid: String?,
    val missing: Int
)

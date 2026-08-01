package com.visibeat.musicbrainz

import com.visibeat.coredb.DateGranularity
import java.util.*

/**
 * Interface for release enrichment data access.
 * Implement this in your app module to connect worker to database.
 */
interface EnrichmentDataAccess {
    /**
     * DTO for releases needing enrichment.
     */
    data class ReleaseData(
        val releaseId: Long,
        val albumTitle: String,
        val artistName: String,
        val primaryDateEpochMs: Long?
    )
    
    /**
     * Get releases that need enrichment (year-only dates).
     */
    suspend fun getReleasesForEnrichment(limit: Int = 50): List<ReleaseData>

    /** Records that a release was tried, so failures stop blocking the queue. */
    suspend fun markEnrichAttempt(releaseId: Long)

    /** Matched releases whose genres have not been pulled. */
    suspend fun getReleasesForGenreFetch(limit: Int = 100): List<Pair<Long, String>>

    /**
     * Stores [genres] against every track on the release, as observations.
     *
     * Observations rather than a direct write, so the existing resolver decides
     * precedence — a genre the user typed, or one already in the file's tags,
     * keeps beating anything MusicBrainz suggests.
     */
    suspend fun storeReleaseGenres(releaseId: Long, genres: List<String>)

    /** The album artist's MBID, for the genre fallback. Null if unknown. */
    suspend fun artistMbidForRelease(releaseId: Long): String?

    /** Releases still queued for matching, plus those still needing genres. */
    suspend fun remainingWorkCount(): Int

    /** Artists whose tracks have no genre: id, name, MBID if known. */
    suspend fun getArtistsNeedingGenres(limit: Int = 60): List<Triple<Long, String, String?>>

    /** Remembers an artist's MBID once it has been searched for. */
    suspend fun storeArtistMbid(artistId: Long, mbid: String)

    /**
     * Applies [genres] to that artist's still-untagged tracks.
     *
     * Weak confidence on purpose. An artist genre is one blurred answer for a
     * whole career, so anything from the file's own tags or from the release
     * has to keep beating it.
     */
    suspend fun storeArtistGenres(artistId: Long, genres: List<String>)
    
    suspend fun updateRelease(
        releaseId: Long,
        dateEpochMs: Long,
        granularity: DateGranularity,
        musicBrainzId: String,
        /** Null leaves whatever type is already stored alone. */
        releaseType: String?
    )

    /**
     * Re-resolve all tracks within this release to reflect new date/metadata.
     */
    suspend fun reResolveRelease(releaseId: Long)
}

/**
 * Singleton service for release date enrichment.
 * Initialize from MainActivity with database access.
 */
object EnrichmentService {
    private var dataAccess: EnrichmentDataAccess? = null
    private val client = MusicBrainzClient()
    private val enricher = ReleaseDateEnricher(client)
    
    /**
     * Initialize with database access.
     * Call from MainActivity after database is created.
     */
    fun initialize(dataAccess: EnrichmentDataAccess) {
        this.dataAccess = dataAccess
    }
    
    /**
     * Check if initialized.
     */
    fun isInitialized(): Boolean = dataAccess != null
    
    /**
     * Run enrichment for a batch of releases.
     * Returns number of releases enriched.
     */
    /** How many releases one run works through. */
    var batchSize: Int = 50

    /**
     * Pulls genres for releases already matched to MusicBrainz.
     *
     * Separate from [enrichBatch] because it has a different prerequisite: it
     * needs an MBID, which the date pass is what produces. Running them in one
     * loop would mean a release matched in this run waits a whole cycle for its
     * genres.
     *
     * @return how many releases got at least one genre
     */
    suspend fun enrichGenresBatch(limit: Int = 100): Int {
        val dao = dataAccess ?: throw IllegalStateException("EnrichmentService not initialized")
        var stored = 0
        for ((releaseId, mbid) in dao.getReleasesForGenreFetch(limit)) {
            var genres = client.releaseGenres(mbid)
            if (genres.isEmpty()) {
                // Release and release-group said nothing. The artist almost
                // always has: on a sampled library, release-level genres were
                // present for 1 album in 8 and artist-level for 8 in 8.
                dao.artistMbidForRelease(releaseId)?.let { artistMbid ->
                    genres = client.artistGenres(artistMbid)
                }
            }
            // Marked either way: a release MusicBrainz has no genres for is
            // answered, and asking again tomorrow will get the same nothing.
            dao.storeReleaseGenres(releaseId, genres)
            if (genres.isNotEmpty()) stored++
        }
        return stored
    }

    /** How much is left, so a run can ask to be scheduled again. */
    suspend fun remainingWork(): Int =
        dataAccess?.remainingWorkCount() ?: 0

    /**
     * Genres by artist, for tracks the release path could not reach.
     *
     * Runs last because it is the weakest source and the widest net: it only
     * looks at tracks that still have nothing after tags, release and
     * release-group have all had their turn.
     *
     * @return how many artists contributed at least one genre
     */
    suspend fun enrichArtistGenresBatch(limit: Int = 60): Int {
        val dao = dataAccess ?: throw IllegalStateException("EnrichmentService not initialized")
        var applied = 0
        for ((artistId, name, knownMbid) in dao.getArtistsNeedingGenres(limit)) {
            val mbid = knownMbid ?: client.searchArtist(name).firstOrNull()?.id
                ?.also { dao.storeArtistMbid(artistId, it) }
                ?: continue
            val genres = client.artistGenres(mbid)
            if (genres.isNotEmpty()) {
                dao.storeArtistGenres(artistId, genres)
                applied++
            }
        }
        return applied
    }

    suspend fun enrichBatch(): Int {
        val dao = dataAccess ?: throw IllegalStateException("EnrichmentService not initialized")
        
        val releases = dao.getReleasesForEnrichment(limit = batchSize)
        android.util.Log.d("MusicBrainz", "Found ${releases.size} releases to enrich")
        
        if (releases.isEmpty()) {
            android.util.Log.w("MusicBrainz", "No releases found. Check if track_artist has PRIMARY role entries.")
            return 0
        }
        
        var enrichedCount = 0
        
        for (release in releases) {
            // Recorded before the attempt, not after, so a crash or a killed
            // worker still counts as a try. Otherwise a release that reliably
            // crashes the matcher is retried forever.
            dao.markEnrichAttempt(release.releaseId)

            val existingYear = release.primaryDateEpochMs?.let { epochMs ->
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = epochMs
                }.get(Calendar.YEAR)
            }
            
            val result = enricher.enrichReleaseDate(
                artist = release.artistName,
                album = release.albumTitle,
                existingYear = existingYear
            )
            
            when (result) {
                is EnrichmentResult.Found -> {
                    if (result.confidence != EnrichmentConfidence.LOW) {
                        val dateMs = ReleaseDateEnrichmentWorker.toEpochMs(result.dateComponents)
                        val granularity = ReleaseDateEnrichmentWorker.toGranularity(result.dateComponents)
                        dao.updateRelease(
                            releaseId = release.releaseId,
                            dateEpochMs = dateMs,
                            granularity = granularity,
                            musicBrainzId = result.musicBrainzId,
                            releaseType = result.releaseType
                        )
                        
                        // NEW: Force re-resolution of all tracks in this release so sorting updates
                        dao.reResolveRelease(release.releaseId)
                        
                        enrichedCount++
                    }
                }
                else -> { /* skip */ }
            }
        }
        
        return enrichedCount
    }
}

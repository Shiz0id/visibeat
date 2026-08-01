package com.visibeat.musicbrainz

/**
 * Database access for artist portrait lookup.
 *
 * Mirrors [EnrichmentDataAccess]: this module owns the networking and knows
 * nothing about Room, and the app module supplies the storage.
 */
interface ArtistImageDataAccess {

    data class ArtistNeedingImage(
        val artistId: Long,
        val artistName: String,
        /** Known MBID, if a previous attempt got that far. Skips the search hop. */
        val musicBrainzId: String?,
        val attempts: Int
    )

    /** Artists still without a portrait, most-listened first. */
    suspend fun getArtistsNeedingImages(limit: Int): List<ArtistNeedingImage>

    /**
     * Record the outcome. A null [imageUrl] is a real result — it stops the
     * artist being queried again for a month.
     */
    suspend fun recordResult(
        artistId: Long,
        imageUrl: String?,
        source: String,
        musicBrainzId: String?,
        wikidataId: String?,
        /** Wikidata's one-liner. Null leaves any stored description alone. */
        description: String?,
        /** English Wikipedia article title, captured on the same request. */
        wikipediaTitle: String?,
        attempts: Int
    )

    /** Artists with no cached Wikipedia lead, most-listened first. */
    suspend fun getArtistsNeedingBio(limit: Int): List<ArtistNeedingImage>

    /** Stores the lead section and the article it came from. */
    suspend fun storeBio(artistId: Long, extract: String, articleUrl: String?)
}

/**
 * Singleton for artist portrait lookup, initialised from MainActivity.
 */
object ArtistImageService {
    private var dataAccess: ArtistImageDataAccess? = null
    private val lookup = ArtistImageLookup()

    fun initialize(dataAccess: ArtistImageDataAccess) {
        this.dataAccess = dataAccess
    }

    fun isInitialized(): Boolean = dataAccess != null

    /**
     * Looks up one batch of artists.
     *
     * Deliberately small and slow: MusicBrainz allows one request per second, so
     * a batch of 20 takes around half a minute of mostly waiting. It runs in a
     * worker for exactly that reason.
     *
     * @return how many portraits were found
     */
    suspend fun fetchBatch(limit: Int = DEFAULT_BATCH): Int {
        val dao = dataAccess ?: throw IllegalStateException("ArtistImageService not initialized")

        val artists = dao.getArtistsNeedingImages(limit)
        if (artists.isEmpty()) {
            android.util.Log.d(LOG_TAG, "no artists need portraits")
            return 0
        }

        var found = 0
        for (artist in artists) {
            when (val result = lookup.findPortrait(artist.artistName, artist.musicBrainzId)) {
                is ArtistImageResult.Found -> {
                    dao.recordResult(
                        artistId = artist.artistId,
                        imageUrl = result.imageUrl,
                        source = result.source,
                        musicBrainzId = result.musicBrainzId,
                        wikidataId = result.wikidataId,
                        description = result.description,
                        wikipediaTitle = result.wikipediaTitle,
                        attempts = artist.attempts + 1
                    )
                    found++
                    android.util.Log.d(LOG_TAG, "portrait for ${artist.artistName}")
                }

                is ArtistImageResult.NotFound -> {
                    // Remembered so the next pass skips this artist entirely.
                    // No portrait, but an artist with a Wikidata entry and no
                    // free photo still has a description worth keeping.
                    dao.recordResult(
                        artistId = artist.artistId,
                        imageUrl = null,
                        source = ArtistImageSource.NONE,
                        musicBrainzId = result.musicBrainzId,
                        wikidataId = result.wikidataId,
                        description = result.description,
                        wikipediaTitle = result.wikipediaTitle,
                        attempts = artist.attempts + 1
                    )
                }

                is ArtistImageResult.Failed -> {
                    // A network blip is not evidence the artist has no picture,
                    // so nothing is written and the attempt count stays put.
                    android.util.Log.w(
                        LOG_TAG,
                        "lookup failed for ${artist.artistName}: ${result.reason}"
                    )
                }
            }
        }
        return found
    }

    /**
     * One artist's Wikipedia lead section, fetched on demand.
     *
     * Not part of [fetchBatch] on purpose: the batch runs over the whole library
     * on a timer, and most artists' bios are never opened. This runs when
     * somebody actually asks.
     */
    /**
     * Fills the bio cache ahead of the user rather than in front of them.
     *
     * The artist header shows the lead section now, so an empty cache means
     * every first visit renders a gap and then shoves the controls down a
     * second later when the text lands. Warming it turns that into a one-off
     * background cost.
     *
     * Ordered by listening, so the artists somebody actually opens are done
     * first and the long tail can finish whenever.
     *
     * @return how many bios were stored
     */
    suspend fun warmBiosBatch(limit: Int = 40): Int {
        val dao = dataAccess ?: return 0
        val candidates = dao.getArtistsNeedingBio(limit)
        if (candidates.isEmpty()) {
            android.util.Log.d(LOG_TAG, "no artists need bios")
            return 0
        }

        var stored = 0
        for (artist in candidates) {
            val summary = fetchBio(
                artistName = artist.artistName,
                knownWikipediaTitle = null,
                knownMusicBrainzId = artist.musicBrainzId
            )
            if (summary == null) {
                // Logged at the same level as a success, because "no article"
                // and "never asked" look identical from outside and only one of
                // them is worth investigating.
                android.util.Log.d(LOG_TAG, "no bio for ${artist.artistName}")
                continue
            }
            dao.storeBio(artist.artistId, summary.extract, summary.articleUrl)
            android.util.Log.d(LOG_TAG, "bio for ${artist.artistName}")
            stored++
        }
        return stored
    }

    /** Artists still waiting on a portrait or a bio, for deciding to run again. */
    suspend fun remainingArtists(): Int {
        val dao = dataAccess ?: return 0
        return dao.getArtistsNeedingImages(1).size + dao.getArtistsNeedingBio(1).size
    }

    suspend fun fetchBio(
        artistName: String,
        knownWikipediaTitle: String?,
        knownMusicBrainzId: String?
    ): WikipediaSummary? = lookup.findBio(artistName, knownWikipediaTitle, knownMusicBrainzId)

    private const val LOG_TAG = "VisiBeatArtistImage"
    private const val DEFAULT_BATCH = 20
}

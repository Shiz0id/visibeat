package com.visibeat.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.visibeat.coredb.ArtistRole
import com.visibeat.coredb.IdentitySource
import com.visibeat.musicdb.ArtistIdentityEntity
import com.visibeat.coredb.Confidence
import com.visibeat.coredb.MetaSource
import com.visibeat.coredb.MetadataField
import com.visibeat.coredb.MetadataObservationEntity
import com.visibeat.coredb.SubjectType
import com.visibeat.coredb.DateGranularity
import com.visibeat.ingest.MediaStoreScanner
import com.visibeat.ingest.MusicIngestRepository
import com.visibeat.ingest.SafScanner
import com.visibeat.ingest.TagExtractor
import com.visibeat.musicbrainz.ArtistImageDataAccess
import com.visibeat.musicbrainz.ArtistImageService
import com.visibeat.musicbrainz.ArtistImageWorker
import com.visibeat.radio.AudioEmbeddingEngine
import com.visibeat.radio.AudioWindowReader
import com.visibeat.radio.EmbeddingIndex
import com.visibeat.radio.GenreLookup
import com.visibeat.radio.IndexEntry
import com.visibeat.radio.ModelPresets
import com.visibeat.musicdb.MAX_ENRICH_ATTEMPTS
import com.visibeat.musicui.settings.EnrichmentStatus
import com.visibeat.musicui.settings.RadioStatus
import com.visibeat.radio.RadioIndexing
import com.visibeat.radio.onnx.OnnxEmbeddingModel
import com.visibeat.viewengine.ArtistImageDao
import com.visibeat.musicbrainz.EnrichmentDataAccess
import com.visibeat.musicbrainz.EnrichmentService
import com.visibeat.musicbrainz.ReleaseDateEnrichmentWorker
import com.visibeat.musicui.track.TrackEditRepository
import com.visibeat.viewengine.ArtistMaintenance
import com.visibeat.viewengine.FeedQueryEngine
import com.visibeat.viewengine.LibraryFeeds
import com.visibeat.viewengine.Migrations
import com.visibeat.viewengine.MusicPimDb
import com.visibeat.viewengine.MusicResolver
import com.visibeat.viewengine.ReleaseResolver
import com.visibeat.viewengine.TimelineItemRow
import com.visibeat.viewengine.TimelineFeed
import com.visibeat.viewengine.TrackMaintenance
import com.visibeat.viewengine.TimelineQueryEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything that outlives a screen.
 *
 * All of this used to be built in `MainActivity.onCreate`. The activity is not
 * declared with any `configChanges`, so every rotation constructed a second
 * `MusicPimDb` against the same file — its own connection pool, its own
 * invalidation tracker — and dropped the previous one without closing it. Room
 * is explicit that a database should be a singleton, and this is why.
 *
 * The MusicBrainz services are registered here for a different reason: their
 * WorkManager jobs run in whatever process the system decides to start, which is
 * very often one where no activity has ever been created. Initialised from the
 * activity, those workers found `isInitialized() == false`, returned
 * `Result.retry()`, and backed off exponentially forever — so enrichment and
 * artist portraits only ever progressed while the app happened to be open.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val db: MusicPimDb = Room.databaseBuilder(
        appContext,
        MusicPimDb::class.java, "music-pim-db"
    )
        // Migrations.ALL existed but was never registered, so every schema bump
        // so far had quietly rebuilt the database from scratch. That was
        // harmless while every table could be recreated by rescanning; playlists
        // and play history cannot, so the 3->4 step has to actually run.
        .addMigrations(*Migrations.ALL)
        .fallbackToDestructiveMigration() // still the backstop for gaps in the chain
        .build()

    val resolver = MusicResolver(
        db.observationDao(), db.trackDao(), db.artistDao(), db.releaseDao(),
        db.genreDao(), db.relationshipDao(), db.identityDao()
    )

    val releaseResolver = ReleaseResolver(
        db.observationDao(), db.trackDao(), db.artistDao(),
        db.releaseDao(), db.relationshipDao()
    )

    val timelineEngine = TimelineQueryEngine(db.timelineDao())
    val feedEngine = FeedQueryEngine(db.feedDao())

    val libraryDao = db.libraryDao()
    val playlistDao = db.playlistDao()
    val playHistoryDao = db.playHistoryDao()
    val artistImageDao = db.artistImageDao()
    val timelineDao = db.timelineDao()
    val libraryRootDao = db.libraryRootDao()
    val albumDao = db.albumDao()
    val likesDao = db.likesDao()
    val artistPageDao = db.artistPageDao()

    /**
     * Process-lifetime scope for the shared library flows. Not tied to any
     * screen — that is the whole point of [feeds].
     */
    private val graphScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The library-wide queries, subscribed once instead of once per screen
     * visit. See [LibraryFeeds] for why this is not just a convenience.
     */
    val feeds = LibraryFeeds(libraryDao, playHistoryDao, playlistDao, graphScope)

    /**
     * The timeline's query and buckets. Holds the granularity/sort the user
     * picked, and keeps the bucket query off every other screen.
     */
    val timelineFeed = TimelineFeed(timelineEngine, graphScope)

    val trackMaintenance = TrackMaintenance(db, db.trackMaintenanceDao())

    val artistMaintenance = ArtistMaintenance(
        db, db.artistDao(), db.relationshipDao(), db.artistMaintenanceDao()
    )

    val ingestRepo = MusicIngestRepository(
        appContext, db, db.trackDao(), db.artistDao(), db.releaseDao(), db.genreDao(),
        db.identityDao(), db.relationshipDao(), db.observationDao(),
        db.dismissalDao(), db.resolvedDao(), resolver, releaseResolver
    )

    private val tagExtractor = TagExtractor(appContext)
    val mediaStoreScanner = MediaStoreScanner(appContext, ingestRepo, tagExtractor)
    val safScanner = SafScanner(appContext, ingestRepo, tagExtractor)

    val trackRepo = TrackEditRepository(
        db, db.observationDao(), db.trackDao(), db.relationshipDao(),
        db.resolvedDao(), db.dismissalDao(), resolver, db.releaseDao()
    )

    // ------------------------------------------------------------------
    // Offline radio
    // ------------------------------------------------------------------

    val trackEmbeddingDao = db.trackEmbeddingDao()

    /**
     * The embedding model, built on first use and then kept.
     *
     * Lazy because construction is a 21 MB stage-to-disk on first launch
     * followed by an ONNX graph optimisation pass, and a user who never opens
     * Radio should never pay either. Once built it is held for the process —
     * the indexer runs in bursts and reloading the graph per burst would cost
     * more than the inference.
     *
     * Null if the asset is missing or the graph will not load. Radio is then
     * simply unavailable, which is a better outcome than a crash on launch for
     * a feature that is not the point of the app.
     */
    val embeddingEngine: AudioEmbeddingEngine? by lazy {
        try {
            AudioEmbeddingEngine(
                model = OnnxEmbeddingModel.fromAssetsWithExternalData(
                    context = appContext,
                    // Graph first, then its external weights. The .onnx refers to
                    // the .data by literal filename, so both must land in one
                    // directory under their original names.
                    assetNames = listOf(DCLAP_GRAPH, DCLAP_WEIGHTS),
                    id = ModelPresets.DCLAP_ID,
                    dimension = ModelPresets.DCLAP_DIM,
                    spec = ModelPresets.DCLAP
                ),
                windowReader = AudioWindowReader(appContext)
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The library's vectors, in memory.
     *
     * Rebuilt on demand rather than observed. The indexer writes thousands of
     * rows in bursts, and a Flow would rebuild a 5,000-vector array on every
     * one of them.
     */
    @Volatile
    private var cachedIndex: EmbeddingIndex? = null

    suspend fun embeddingIndex(refresh: Boolean = false): EmbeddingIndex {
        cachedIndex?.takeIf { !refresh }?.let { return it }
        val dim = ModelPresets.DCLAP_DIM
        val rows = trackEmbeddingDao.loadAll(ModelPresets.DCLAP_ID, dim, dim * 4)
            .map { IndexEntry(it.trackId, it.vector, it.artistId, it.albumId) }
        return EmbeddingIndex.build(rows, dim).also { cachedIndex = it }
    }

    /**
     * A plain-language snapshot of the radio's state, for Settings.
     *
     * Touching [embeddingEngine] here *loads the model* — that is the point.
     * Every interesting failure in this feature is silent, so the diagnostic
     * that matters is the one that actually attempts the thing rather than
     * reporting on a flag someone set earlier.
     */
    suspend fun radioStatus(): RadioStatus = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val engine = embeddingEngine
        val dim = ModelPresets.DCLAP_DIM
        val indexed = trackEmbeddingDao.countFor(ModelPresets.DCLAP_ID, dim, dim * 4)
        val unreadable = trackEmbeddingDao.countFailed(ModelPresets.DCLAP_ID, dim)
        val total = trackEmbeddingDao.countTracks()
        val foreign = trackEmbeddingDao.countAll() - indexed - unreadable

        val mel = ModelPresets.DCLAP.mel
        RadioStatus(
            modelLine = if (engine == null) {
                "Failed to load — check assets/$DCLAP_GRAPH"
            } else {
                "${engine.modelId} · ${engine.dimension}d"
            },
            indexLine = when {
                total == 0 -> "No tracks in library"
                indexed == 0 -> "0 of $total — plug in to start"
                indexed + unreadable >= total -> "$indexed of $total · complete"
                else -> "$indexed of $total (${indexed * 100 / total}%)"
            },
            detail = buildList {
                add("Audio: ${mel.sampleRate} Hz · ${ModelPresets.DCLAP.segmentSeconds?.toInt()}s segments " +
                    "at ${(ModelPresets.DCLAP.segmentOverlap * 100).toInt()}% overlap")
                add("Mel: ${mel.nMels} bands · n_fft ${mel.nFft} · hop ${mel.hopLength} · " +
                    "${mel.fMin.toInt()}-${mel.effectiveFMax.toInt()} Hz")
                // Only when there is one. "0 vectors" next to "24 of 1146"
                // reads like a contradiction rather than "not loaded yet".
                cachedIndex?.let { add("Loaded into memory: ${it.size} vectors") }
                if (unreadable > 0) {
                    add("$unreadable tracks could not be decoded — skipped, not retried")
                }

                // The state of the actual background job. Without this, "it is
                // not working" cannot be told apart from "it is waiting for a
                // charger", "it failed instantly" or "it already finished".
                val run = RadioIndexing.readStatus(appContext)
                when {
                    run == null -> add("Analysis job: never queued")
                    run.error != null -> add("Analysis job: ${run.state} — ${run.error}")
                    run.state == "ENQUEUED" -> add(
                        "Analysis job: waiting for a charger. Use Force below to run anyway."
                    )
                    run.state == "RUNNING" -> add("Analysis job: running (${run.progress} this run)")
                    else -> add("Analysis job: ${run.state.lowercase()}")
                }
                if (foreign > 0) {
                    add("$foreign rows from another model — will be cleared on the next run")
                }
                if (indexed in 1 until total) {
                    add("Indexing needs a charger and battery above low.")
                }
            }
        )
    }

    /**
     * Stops any run, then throws away every embedding.
     *
     * The stop has to come first and has to be waited on. Deleting while the
     * indexer is mid-batch empties the table and then watches the worker commit
     * the batch it had already computed — the count drops to zero and climbs
     * straight back, which reads as the clear having silently failed.
     */
    suspend fun clearEmbeddings(): Int {
        RadioIndexing.cancelAndAwait(appContext)
        val removed = trackEmbeddingDao.deleteAll()
        cachedIndex = null
        cachedGenres = null
        return removed
    }

    /**
     * Genre tokens per track, loaded once beside the index.
     *
     * Built even though every RadioConfig currently weights genre at zero, so
     * that turning the weight up is genuinely the one-line change it is
     * advertised as rather than a one-line change plus a plumbing job.
     */
    @Volatile
    private var cachedGenres: GenreLookup? = null

    suspend fun genreLookup(): GenreLookup {
        cachedGenres?.let { return it }
        val map = HashMap<Long, Set<String>>()
        for (row in trackEmbeddingDao.loadGenres()) {
            val tokens = row.genre.split(',', ';', '/', '|')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (tokens.isNotEmpty()) map[row.trackId] = tokens
        }
        return GenreLookup { map[it] ?: emptySet() }.also { cachedGenres = it }
    }

    /**
     * What MusicBrainz enrichment has and has not reached.
     *
     * "Given up" is the number that matters and the one nothing used to show:
     * before attempts were counted, an unmatchable release was indistinguishable
     * from an untried one, so the queue looked full forever while the same few
     * releases were re-attempted every day.
     */
    suspend fun enrichmentStatus(): EnrichmentStatus =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val p = db.releaseDao().enrichmentProgress()
            val pending = p.total - p.matched - p.givenUp
            EnrichmentStatus(
                releasesLine = when {
                    p.total == 0 -> "No releases yet"
                    pending == 0 && p.givenUp == 0 -> "${p.matched} of ${p.total} · complete"
                    else -> "${p.matched} of ${p.total} matched · $pending to go"
                },
                genresLine = if (p.matched == 0) "" else
                    "${p.genresDone} of ${p.matched} matched releases have genres",
                detail = buildList {
                    if (pending > 0) add("Tap Matched releases to run the whole queue now.")
                    if (p.givenUp > 0) {
                        add("${p.givenUp} releases could not be matched after $MAX_ENRICH_ATTEMPTS tries; they are no longer retried.")
                    }
                    add("Portraits wait for Wi-Fi; the buttons here ignore that.")
                }
            )
        }

    /** Forgets undecodable tracks so the next run retries just those. */
    suspend fun retryFailedEmbeddings(): Int {
        RadioIndexing.cancelAndAwait(appContext)
        val cleared = trackEmbeddingDao.deleteFailures()
        cachedIndex = null
        return cleared
    }

    /** Drops the in-memory index so the next station sees new rows. */
    fun invalidateEmbeddingIndex() {
        cachedIndex = null
    }

    /**
     * Hands the index worker its dependencies.
     *
     * WorkManager builds workers itself and cannot be handed a model that takes
     * seconds to load, so the graph registers them the same way it registers the
     * MusicBrainz services above — and for the same reason: the worker very
     * often runs in a process where no activity has ever existed.
     */
    fun registerRadioIndexing() {
        RadioIndexing.dependencies = RadioIndexing.Deps(
            dao = trackEmbeddingDao,
            engineProvider = {
                embeddingEngine ?: throw IllegalStateException("no embedding model installed")
            },
            onBatchCommitted = { invalidateEmbeddingIndex() }
        )
    }


    /**
     * Fetches and caches an artist's Wikipedia lead section, once.
     *
     * Lives here because `music-ui` has no dependency on the `musicbrainz`
     * module and should not gain one — the screen calls this and then simply
     * observes the cache row, the same shape as every other cross-module wire in
     * this graph.
     *
     * A no-op when the extract is already stored, so opening Info repeatedly
     * costs nothing.
     */
    suspend fun ensureArtistBio(artistId: Long, artistName: String) {
        val existing = artistImageDao.get(artistId)
        if (!existing?.wikipediaExtract.isNullOrBlank()) return

        val summary = ArtistImageService.fetchBio(
            artistName = artistName,
            knownWikipediaTitle = existing?.wikipediaTitle,
            knownMusicBrainzId = existing?.musicBrainzId
        ) ?: return

        // The row may not exist yet: the image worker reaches artists slowly and
        // somebody can open Info long before it gets there.
        artistImageDao.ensureRow(artistId, System.currentTimeMillis())
        artistImageDao.updateWikipedia(artistId, summary.extract, summary.articleUrl)
    }

    /**
     * Hands the MusicBrainz modules their storage. They own the networking and
     * know nothing about Room, so the wiring has to come from here.
     */
    fun registerEnrichmentServices() {
        EnrichmentService.initialize(object : EnrichmentDataAccess {
            override suspend fun getReleasesForEnrichment(
                limit: Int
            ): List<EnrichmentDataAccess.ReleaseData> =
                db.releaseDao().getReleasesForEnrichment(limit = limit).map { release ->
                    EnrichmentDataAccess.ReleaseData(
                        releaseId = release.releaseId,
                        albumTitle = release.albumTitle,
                        artistName = release.artistName,
                        primaryDateEpochMs = release.primaryDateEpochMs
                    )
                }

            override suspend fun updateRelease(
                releaseId: Long,
                dateEpochMs: Long,
                granularity: DateGranularity,
                musicBrainzId: String,
                releaseType: String?
            ) {
                db.releaseDao().updateWithMusicBrainzData(
                    releaseId = releaseId,
                    dateEpochMs = dateEpochMs,
                    granularity = granularity,
                    mbid = musicBrainzId,
                    releaseType = releaseType
                )
            }

            override suspend fun markEnrichAttempt(releaseId: Long) {
                db.releaseDao().markEnrichAttempt(releaseId, System.currentTimeMillis())
            }

            override suspend fun getReleasesForGenreFetch(limit: Int): List<Pair<Long, String>> =
                db.releaseDao().getReleasesForGenreFetch(limit).map { it.releaseId to it.mbid }

            /**
             * Writes MusicBrainz genres as observations, one per track.
             *
             * Observations rather than a direct write to `genres`/`track_genre`,
             * because that is how every other source in this app states an
             * opinion — and it means the resolver's existing precedence keeps a
             * genre from the file's own tags, or one the user typed, ahead of a
             * guess from an online database.
             *
             * The release is marked as fetched even when MusicBrainz has no
             * genres for it. A release nobody has tagged is an answer, and
             * asking again tomorrow returns the same nothing.
             */
            override suspend fun storeReleaseGenres(releaseId: Long, genres: List<String>) {
                val now = System.currentTimeMillis()
                if (genres.isNotEmpty()) {
                    val value = genres.joinToString("; ")
                    for (trackId in db.releaseDao().trackIdsForRelease(releaseId)) {
                        db.observationDao().insertObservation(
                            MetadataObservationEntity(
                                subjectType = SubjectType.TRACK,
                                subjectId = trackId,
                                field = MetadataField.GENRE,
                                value = value,
                                source = MetaSource.MUSICBRAINZ,
                                confidence = Confidence.STRONG,
                                observedAt = now
                            )
                        )
                    }
                    reResolveRelease(releaseId)
                }
                db.releaseDao().markGenresFetched(releaseId, now)
            }

            override suspend fun artistMbidForRelease(releaseId: Long): String? =
                db.releaseDao().artistMbidForRelease(releaseId)

            override suspend fun remainingWorkCount(): Int =
                db.releaseDao().remainingEnrichmentWork()

            override suspend fun getArtistsNeedingGenres(
                limit: Int
            ): List<Triple<Long, String, String?>> =
                db.releaseDao().getArtistsNeedingGenres(limit)
                    .map { Triple(it.artistId, it.name, it.mbid) }

            override suspend fun storeArtistMbid(artistId: Long, mbid: String) {
                db.identityDao().insertArtistIdentity(
                    ArtistIdentityEntity(
                        artistId = artistId,
                        source = IdentitySource.MB_ARTIST,
                        sourceKey = mbid,
                        confidence = Confidence.STRONG,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            override suspend fun storeArtistGenres(artistId: Long, genres: List<String>) {
                val now = System.currentTimeMillis()
                val value = genres.joinToString("; ")
                val trackIds = db.releaseDao().unGenredTrackIdsForArtist(artistId)
                for (trackId in trackIds) {
                    db.observationDao().insertObservation(
                        MetadataObservationEntity(
                            subjectType = SubjectType.TRACK,
                            subjectId = trackId,
                            field = MetadataField.GENRE,
                            value = value,
                            source = MetaSource.MUSICBRAINZ,
                            // Weaker than a release genre: one answer for a whole
                            // career, so a file tag or a release must still win.
                            confidence = Confidence.WEAK,
                            observedAt = now
                        )
                    )
                }
                // Re-resolve so the new genre reaches resolved_tracks, which is
                // what every screen and the radio actually read.
                for (trackId in trackIds) {
                    val artists = db.relationshipDao().listTrackArtists(trackId)
                    val primary = artists.firstOrNull {
                        it.role == ArtistRole.PRIMARY || it.role == ArtistRole.ALBUM_ARTIST
                    }?.artistId ?: artists.firstOrNull()?.artistId
                    // The release is already on the resolved row; re-deriving it
                    // would be a second query for something we just read.
                    val releaseId = db.releaseDao().releaseIdForTrack(trackId)
                    db.resolvedDao().upsertResolvedTrack(
                        resolver.resolveTrack(trackId, releaseId, primary)
                    )
                }
            }

            override suspend fun reResolveRelease(releaseId: Long) {
                val tracks = db.relationshipDao().listTracksForRelease(releaseId)
                for (trackRow in tracks) {
                    val trackId = trackRow.trackId
                    val artists = db.relationshipDao().listTrackArtists(trackId)
                    val primaryArtistId = artists.firstOrNull {
                        it.role == ArtistRole.PRIMARY || it.role == ArtistRole.ALBUM_ARTIST
                    }?.artistId ?: artists.firstOrNull()?.artistId

                    val resolved = resolver.resolveTrack(trackId, releaseId, primaryArtistId)
                    db.resolvedDao().upsertResolvedTrack(resolved)
                }
            }
        })

        // Artist portraits: MusicBrainz -> Wikidata -> Wikimedia Commons. Free,
        // keyless and clearly licensed, but only covers artists notable enough to
        // have a Wikidata entry — hence the album-art fallback in the UI.
        ArtistImageService.initialize(object : ArtistImageDataAccess {
            override suspend fun getArtistsNeedingBio(
                limit: Int
            ): List<ArtistImageDataAccess.ArtistNeedingImage> =
                artistImageDao.getArtistsNeedingBio(limit).map {
                    ArtistImageDataAccess.ArtistNeedingImage(
                        artistId = it.artistId,
                        artistName = it.artistName,
                        musicBrainzId = it.musicBrainzId,
                        attempts = it.attempts
                    )
                }

            override suspend fun storeBio(artistId: Long, extract: String, articleUrl: String?) {
                // The row may not exist: it is created by a portrait lookup, and
                // the bio pass reaches artists that have never had one.
                artistImageDao.ensureRow(artistId, System.currentTimeMillis())
                artistImageDao.updateWikipedia(artistId, extract, articleUrl)
            }

            override suspend fun getArtistsNeedingImages(
                limit: Int
            ): List<ArtistImageDataAccess.ArtistNeedingImage> {
                val now = System.currentTimeMillis()
                return artistImageDao.getLookupCandidates(
                    maxAttempts = ARTIST_IMAGE_MAX_ATTEMPTS,
                    retryBefore = now - ARTIST_IMAGE_RETRY_AFTER_MS,
                    limit = limit
                ).map { candidate ->
                    ArtistImageDataAccess.ArtistNeedingImage(
                        artistId = candidate.artistId,
                        artistName = candidate.artistName,
                        musicBrainzId = candidate.musicBrainzId,
                        attempts = candidate.attempts
                    )
                }
            }

            override suspend fun recordResult(
                artistId: Long,
                imageUrl: String?,
                source: String,
                musicBrainzId: String?,
                wikidataId: String?,
                description: String?,
                wikipediaTitle: String?,
                attempts: Int
            ) {
                artistImageDao.upsert(
                    com.visibeat.musicdb.ArtistImageEntity(
                        artistId = artistId,
                        imageUrl = imageUrl,
                        source = source,
                        musicBrainzId = musicBrainzId,
                        wikidataId = wikidataId,
                        description = description,
                        wikipediaTitle = wikipediaTitle,
                        fetchedAt = System.currentTimeMillis(),
                        attempts = attempts
                    )
                )
            }
        })
    }

    private companion object {
        // Mirrors ArtistImageUrls, which lives in a module the app cannot see
        // the internals of. Kept here so the candidate query can apply them.
        const val ARTIST_IMAGE_MAX_ATTEMPTS = 3
        const val ARTIST_IMAGE_RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * The DCLAP graph and its external weights, as named in `assets/`.
         *
         * The names are load-bearing: the `.onnx` refers to the `.data` by
         * literal filename, so renaming either one breaks the model in a way
         * that only shows up at inference.
         */
        const val DCLAP_GRAPH = "model_epoch_36.onnx"
        const val DCLAP_WEIGHTS = "model_epoch_36.onnx.data"
    }
}

class VisiBeatApp : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        TimelineItemRow.initArtDir(filesDir)
        graph.registerEnrichmentServices()
        // Registration only — it hands the worker a *provider*, so the 21 MB
        // model is still not touched until an index run actually starts.
        graph.registerRadioIndexing()
        ReleaseDateEnrichmentWorker.schedule(this)
        ArtistImageWorker.schedule(this)
    }
}

/** The process-wide graph, from anywhere with a Context. */
val Context.appGraph: AppGraph
    get() = (applicationContext as VisiBeatApp).graph

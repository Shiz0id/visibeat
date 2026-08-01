package com.visibeat.radio

import kotlin.math.exp
import kotlin.random.Random

/**
 * What the listener actually tapped.
 *
 * The seed is a track id either way, but *how it was chosen* is a statement of
 * intent that a track id cannot carry. Tapping a song says "something like this
 * sound"; tapping an artist says "the world this artist lives in". Collapsing
 * both to one set of rules is why an artist station used to push away from the
 * artist it was named after.
 */
enum class RadioOrigin { TRACK, ALBUM, ARTIST }

/**
 * The knobs that decide what a station feels like.
 *
 * Every default here is a taste judgement, not a fact, which is why they are
 * all in one place rather than scattered through the selection code. The
 * defaults are the TRACK station; see [RadioConfig.forOrigin] for the others.
 */
data class RadioConfig(
    /** How many tracks a station generates ahead. */
    val queueLength: Int = 20,

    /**
     * How many recent tracks are barred outright.
     *
     * A hard exclusion rather than a penalty. A large penalty still loses to a
     * high enough similarity, which is exactly the case that produces the
     * two-track loop: the nearest neighbour of A is B and the nearest of B is A,
     * so any finite penalty eventually gets outvoted.
     */
    val recencyWindow: Int = 30,

    /**
     * Subtracted when a candidate shares the current track's artist.
     *
     * Negative rewards it instead, which is what an artist station wants: it
     * was asked for that artist, so pushing away from them inverts the request.
     */
    val sameArtistPenalty: Float = 0.15f,

    /** Subtracted when it shares the album. Albums repeat harder than artists. */
    val sameAlbumPenalty: Float = 0.20f,

    /** Subtracted again for matching the track *before* the current one. */
    val previousTrackPenalty: Float = 0.10f,

    /** How many candidates the random pick chooses among. */
    val candidatePool: Int = 3,

    /**
     * Softmax temperature over the candidate pool.
     *
     * This matters more than it looks. Real embedding neighbours sit in a
     * narrow band — the top few might score 0.88, 0.87, 0.86 — and a softmax
     * over raw differences that small is indistinguishable from a uniform
     * draw, which throws away the ranking entirely. Dividing by a small
     * temperature restores the gap: at 0.05, a 0.02 difference becomes a
     * meaningful odds ratio. Lower is greedier; higher wanders more.
     */
    val temperature: Float = 0.05f,

    /**
     * How far the query drifts from the seed toward what is playing now.
     *
     * 0 pins every choice to the seed, and the station never leaves the corner
     * of the space it started in. 1 makes each pick the seed for the next, and
     * the station random-walks away until it is playing something unrecognisable
     * — the classic complaint about algorithmic radio. Blending keeps the seed's
     * gravity while letting the station move.
     */
    val drift: Float = 0.35f,

    /**
     * Below this cosine, a candidate is not related enough to play.
     *
     * Measured, not guessed. On a real 1,066-track library the model puts two
     * *random* tracks at a mean cosine of 0.50, with a quarter of all random
     * pairs above 0.64 — this space is not spread over the sphere, and a floor
     * of 0.2 let 94% of the library through, which is a shuffle wearing a
     * filter. 0.65 keeps roughly the closest quarter, which sits just under the
     * 0.76 that same-album tracks average: related, but not restricted to
     * things that could have come off the same record.
     */
    val minSimilarity: Float = 0.65f,

    /**
     * Above this cosine, a candidate is the same recording.
     *
     * Libraries collect duplicates — a single and its album, two rips of one
     * track — and the metadata merge cannot always see them. The embeddings
     * can: on the same library, 18 pairs sit above 0.9999 while nothing
     * unrelated exceeds 0.90. Without a ceiling a station happily plays a song
     * and then plays it again under a different track id.
     */
    val maxSimilarity: Float = 0.995f,

    /**
     * How much a shared genre is worth, added to the cosine.
     *
     * Zero by default, and deliberately so. Genre is worth having — measured on
     * a real library, genre overlap correlates with acoustic similarity at only
     * +0.28, so it carries information the audio model genuinely does not have,
     * and same-genre pairs sit 0.13 above different-genre ones. But only about
     * half that library has any genre tag at all, and a signal present for half
     * the tracks does not weight a ranking, it biases one. Turn this up once the
     * tags are filled in; the machinery below is complete and inert until then.
     */
    val genreWeight: Float = 0f
) {
    companion object {
        /**
         * The station shape implied by what was tapped.
         *
         * The numbers are a starting point, not a result — unlike the
         * similarity floor, nothing here has been measured against a library
         * yet. They encode intent: a track station is allowed to wander, an
         * artist station is not, and an artist station rewards the artist
         * instead of fining it.
         */
        fun forOrigin(origin: RadioOrigin): RadioConfig = when (origin) {
            // Exploratory. You named a sound, not a boundary.
            RadioOrigin.TRACK -> RadioConfig()

            // You named a record. Stay near its world, and do not simply replay
            // the record — the album penalty is what keeps it from doing that.
            RadioOrigin.ALBUM -> RadioConfig(
                drift = 0.20f,
                sameArtistPenalty = 0.10f,
                sameAlbumPenalty = 0.15f,
                previousTrackPenalty = 0.08f,
                minSimilarity = 0.68f
            )

            // You named an artist. Their tracks are the point, so the penalty
            // becomes a small bonus; the album penalty stays, so it spreads
            // across their catalogue instead of playing one record end to end.
            // The floor drops because an artist's own range is wider than any
            // one of their songs.
            RadioOrigin.ARTIST -> RadioConfig(
                drift = 0.15f,
                sameArtistPenalty = -0.05f,
                sameAlbumPenalty = 0.20f,
                previousTrackPenalty = 0.05f,
                minSimilarity = 0.60f
            )
        }
    }
}

/**
 * Genre tokens per track, for [RadioConfig.genreWeight].
 *
 * A lookup rather than a field on the index: genre is metadata that changes
 * when enrichment runs, and the index is rebuilt only when vectors change.
 */
fun interface GenreLookup {
    fun genresOf(trackId: Long): Set<String>
}

/** One entry of a generated station. */
data class RadioPick(
    val trackId: Long,
    /** Cosine against the query at the moment it was chosen. */
    val similarity: Float,
    /** After penalties — what it actually competed on. */
    val adjustedScore: Float
)

/**
 * A fixed-size window of what has played, for anti-repetition.
 *
 * Backed by an array with a moving head rather than an ArrayDeque, because the
 * only operations that matter are "add" and "is this in here", both of which
 * happen thousands of times per queue build. The set is kept alongside so the
 * membership test does not walk the buffer.
 */
class RecentTracks(val capacity: Int) {
    private val buffer = LongArray(capacity)
    private val present = HashSet<Long>(capacity * 2)
    private var head = 0
    private var filled = 0

    val size: Int get() = filled

    operator fun contains(trackId: Long): Boolean = trackId in present

    fun add(trackId: Long) {
        if (capacity == 0) return
        if (trackId in present) {
            // Already known. Re-adding would evict something else and leave a
            // duplicate in the ring, so refresh nothing and keep the window honest.
            return
        }
        if (filled == capacity) present.remove(buffer[head])
        buffer[head] = trackId
        present.add(trackId)
        head = (head + 1) % capacity
        if (filled < capacity) filled++
    }

    fun addAll(trackIds: Iterable<Long>) = trackIds.forEach(::add)

    /** A copy, so callers can add their own exclusions to it. */
    fun snapshot(): HashSet<Long> = HashSet(present)

    fun clear() {
        present.clear()
        head = 0
        filled = 0
    }
}

/**
 * Builds a radio queue from a seed track.
 *
 * Pure with respect to the model and the database: it takes an already-built
 * [EmbeddingIndex] and a [Random], so its whole behaviour — the penalties, the
 * drift, the sampling — is testable on the JVM without a device, a model file
 * or an audio decoder. That matters because these rules are where a radio
 * feature actually succeeds or fails, and none of it is observable in a
 * screenshot.
 */
class RadioQueueManager(
    private val index: EmbeddingIndex,
    private val config: RadioConfig = RadioConfig(),
    private val random: Random = Random.Default,
    private val genres: GenreLookup = GenreLookup { emptySet() }
) {

    /** What has actually been played. Bounded — this is a "not too soon" rule. */
    private val recent = RecentTracks(config.recencyWindow)

    /**
     * What this manager has already handed out, since the last [reset].
     *
     * Kept apart from [recent] rather than sharing its ring, which is the
     * arrangement that looks tidier and is wrong. Feeding picks into a bounded
     * history means a 20-track station evicts 20 tracks of real history while
     * building itself: with a 30-track window and 25 tracks of genuine history,
     * the oldest five fall out mid-generation and become eligible again. The
     * window quietly shrinks precisely when it is being used.
     *
     * Unbounded, because it is per-station and a station is tens of tracks.
     * [reset] is what ends a station.
     */
    private val queued = LinkedHashSet<Long>()

    /**
     * Tell the manager a track was played, from anywhere — the radio, the
     * library, a playlist. Radio picks are *not* recorded here automatically;
     * being queued is not being heard.
     */
    fun notePlayed(trackId: Long) = recent.add(trackId)

    fun notePlayed(trackIds: Iterable<Long>) = recent.addAll(trackIds)

    /** Ends the current station. Clears history and the queued set. */
    fun reset() {
        recent.clear()
        queued.clear()
    }

    /** Ends the station without forgetting what was played. */
    fun endStation() = queued.clear()

    /**
     * Generates the next stretch of a station.
     *
     * @return up to [RadioConfig.queueLength] picks. Fewer means the library ran
     *   out of tracks similar enough to be worth playing, which is a real answer
     *   and better than padding with noise.
     */
    fun generate(seedTrackId: Long, length: Int = config.queueLength): List<RadioPick> {
        val seedVector = index.vectorFor(seedTrackId) ?: return emptyList()

        val picks = ArrayList<RadioPick>(length)
        // Calling generate again extends the same station, so previously handed
        // out tracks stay excluded rather than being offered a second time.
        queued.add(seedTrackId)

        val seedGenres = if (config.genreWeight > 0f) genres.genresOf(seedTrackId) else emptySet()

        var query = seedVector.copyOf()
        var currentArtist = index.artistOf(seedTrackId)
        var currentAlbum = index.albumOf(seedTrackId)
        var previousArtist: Long? = null
        var previousAlbum: Long? = null

        repeat(length) {
            val excluded = recent.snapshot()
            excluded.addAll(queued)

            // Over-fetch: penalties reorder the shortlist, so taking exactly
            // candidatePool neighbours would mean penalising three tracks and
            // then picking from those same three regardless.
            val neighbours = index.topK(
                query = query,
                k = config.candidatePool * OVERFETCH,
                exclude = excluded,
                minScore = config.minSimilarity
            )
            if (neighbours.isEmpty()) return@repeat

            // The ceiling can empty the shortlist — a library where every close
            // neighbour is a duplicate of the seed. `sample` takes the maximum
            // of the pool, which throws on an empty one.
            val usable = neighbours.filter { it.score <= config.maxSimilarity }
            if (usable.isEmpty()) return@repeat

            val scored = usable.map { n ->
                var score = n.score
                if (n.artistId != null && n.artistId == currentArtist) score -= config.sameArtistPenalty
                if (n.albumId != null && n.albumId == currentAlbum) score -= config.sameAlbumPenalty
                if (n.artistId != null && n.artistId == previousArtist) score -= config.previousTrackPenalty
                if (n.albumId != null && n.albumId == previousAlbum) score -= config.previousTrackPenalty
                score += config.genreWeight * genreAffinity(seedGenres, n.trackId)
                n to score
            }.sortedByDescending { it.second }

            val pool = scored.take(config.candidatePool)
            val picked = sample(pool)

            picks += RadioPick(
                trackId = picked.first.trackId,
                similarity = picked.first.score,
                adjustedScore = picked.second
            )
            queued.add(picked.first.trackId)

            previousArtist = currentArtist
            previousAlbum = currentAlbum
            currentArtist = picked.first.artistId
            currentAlbum = picked.first.albumId

            index.vectorFor(picked.first.trackId)?.let { pickedVector ->
                query = blend(seedVector, pickedVector, config.drift)
            }
        }

        return picks
    }

    /**
     * Weighted draw over the pool, softmax on the adjusted scores.
     *
     * Shifting by the maximum before exponentiating is not cosmetic: scores
     * divided by a temperature of 0.05 reach ~20, and a few of those summed
     * overflow to infinity, at which point every weight becomes NaN and the
     * draw silently degenerates.
     */
    private fun sample(pool: List<Pair<Neighbour, Float>>): Pair<Neighbour, Float> {
        if (pool.size == 1) return pool[0]
        val t = config.temperature.coerceAtLeast(1e-4f)
        val max = pool.maxOf { it.second }
        val weights = DoubleArray(pool.size) { exp(((pool[it].second - max) / t).toDouble()) }
        val total = weights.sum()
        if (total <= 0.0 || !total.isFinite()) return pool[0]

        var r = random.nextDouble() * total
        for (i in pool.indices) {
            r -= weights[i]
            if (r <= 0.0) return pool[i]
        }
        return pool.last()
    }

    /** Moves [from] toward [to] by [amount], renormalised so it stays unit length. */
    private fun blend(from: FloatArray, to: FloatArray, amount: Float): FloatArray {
        val a = amount.coerceIn(0f, 1f)
        val out = FloatArray(from.size) { i -> from[i] * (1f - a) + to[i] * a }
        return AudioEmbeddingEngine.l2Normalize(out)
    }

    /**
     * How much a candidate's genre agrees with the seed's, in 0..1.
     *
     * Returns [UNTAGGED_AFFINITY] when either side has no tags rather than 0.
     * Scoring a missing tag as disagreement would quietly rank every untagged
     * track below every tagged one that happens to match — on a library that is
     * half untagged, that is not a weighting, it is a filter on metadata
     * completeness.
     *
     * Overlap is a fraction of the smaller set. Measured on a real library,
     * strict agreement carried the signal (d = 0.72) and loosening it to
     * any-shared-token weakened it (d = 0.66), so this stays close to strict:
     * "Pop, Rock" against "Pop" scores 1.0, "Pop" against "Country" scores 0.
     */
    private fun genreAffinity(seedGenres: Set<String>, trackId: Long): Float {
        if (config.genreWeight <= 0f) return 0f
        if (seedGenres.isEmpty()) return UNTAGGED_AFFINITY
        val theirs = genres.genresOf(trackId)
        if (theirs.isEmpty()) return UNTAGGED_AFFINITY
        val shared = seedGenres.count { it in theirs }
        return shared.toFloat() / minOf(seedGenres.size, theirs.size)
    }

    private companion object {
        /** Neighbours fetched per pool slot, so penalties have something to reorder. */
        const val OVERFETCH = 6

        /** Neutral, so missing metadata neither helps nor hurts. */
        const val UNTAGGED_AFFINITY = 0.5f
    }
}

package com.visibeat.radio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One neighbour and how close it is. Score is cosine unless a caller adjusts it. */
data class Neighbour(
    val trackId: Long,
    val score: Float,
    val artistId: Long?,
    val albumId: Long?
)

/**
 * Every vector in the library, in memory, in one array.
 *
 * The storage decision is the performance decision. 5,000 separate
 * `FloatArray`s means 5,000 objects scattered across the heap, and a search
 * that chases a pointer for every one of them — the arithmetic is trivial but
 * every vector is a cache miss. One contiguous `FloatArray` of
 * `count * dim` floats is walked front to back, which the prefetcher handles
 * perfectly and which fits comfortably: 5,000 x 512 x 4 bytes is 10 MB, and at
 * 128 dimensions it is 2.5 MB.
 *
 * That makes a full scan fast enough that an approximate index would be
 * premature. 5,000 x 512 is 2.6M multiply-adds, single-digit milliseconds on a
 * phone — for one track selection, off the main thread. HNSW or IVF start
 * paying for themselves somewhere around a hundred thousand vectors; below that
 * they add a build step, a tuning surface, and recall you have to measure, in
 * exchange for saving a few milliseconds nobody can perceive.
 *
 * Vectors are unit length on the way in, so cosine similarity is a dot product.
 *
 * Immutable once built. The indexer writes to the database; the index is
 * rebuilt from it deliberately, so a search never sees a half-written array.
 */
class EmbeddingIndex private constructor(
    val dim: Int,
    private val count: Int,
    private val vectors: FloatArray,
    private val trackIds: LongArray,
    private val artistIds: LongArray,
    private val albumIds: LongArray,
    private val positionByTrack: HashMap<Long, Int>
) {

    val size: Int get() = count

    fun contains(trackId: Long): Boolean = positionByTrack.containsKey(trackId)

    /** The stored vector for [trackId], or null. A copy; the index stays immutable. */
    fun vectorFor(trackId: Long): FloatArray? {
        val pos = positionByTrack[trackId] ?: return null
        return vectors.copyOfRange(pos * dim, (pos + 1) * dim)
    }

    fun artistOf(trackId: Long): Long? =
        positionByTrack[trackId]?.let { artistIds[it].takeIf { v -> v != NO_ID } }

    fun albumOf(trackId: Long): Long? =
        positionByTrack[trackId]?.let { albumIds[it].takeIf { v -> v != NO_ID } }

    /**
     * The [k] nearest vectors to [query], best first.
     *
     * @param exclude track ids to skip — the seed and the recent history
     * @param minScore floor below which a candidate is not worth returning at
     *   all. Under about 0.2 the model is saying "unrelated", and a radio that
     *   plays those is a shuffle with extra steps.
     */
    fun topK(
        query: FloatArray,
        k: Int,
        exclude: Set<Long> = emptySet(),
        minScore: Float = 0f
    ): List<Neighbour> {
        require(query.size == dim) { "query has ${query.size} dims, index has $dim" }
        if (count == 0 || k <= 0) return emptyList()

        // A k-sized insertion buffer, not a heap. k is 20-50 here, and at that
        // size the array's linear scan wins on cache behaviour against a heap's
        // pointer arithmetic — and the common case is an early reject against
        // `worst`, which costs one comparison.
        val bestScores = FloatArray(k) { Float.NEGATIVE_INFINITY }
        val bestIdx = IntArray(k) { -1 }
        var filled = 0
        var worst = Float.NEGATIVE_INFINITY

        for (i in 0 until count) {
            val id = trackIds[i]
            if (id in exclude) continue

            val base = i * dim
            var dot = 0f
            for (d in 0 until dim) dot += vectors[base + d] * query[d]

            if (dot < minScore) continue
            if (filled == k && dot <= worst) continue

            var pos = if (filled < k) filled++ else k - 1
            while (pos > 0 && bestScores[pos - 1] < dot) {
                bestScores[pos] = bestScores[pos - 1]
                bestIdx[pos] = bestIdx[pos - 1]
                pos--
            }
            bestScores[pos] = dot
            bestIdx[pos] = i
            worst = bestScores[filled - 1]
        }

        return (0 until filled).map { rank ->
            val i = bestIdx[rank]
            Neighbour(
                trackId = trackIds[i],
                score = bestScores[rank],
                artistId = artistIds[i].takeIf { it != NO_ID },
                albumId = albumIds[i].takeIf { it != NO_ID }
            )
        }
    }

    /** Cosine between two stored tracks, or null if either is missing. */
    fun similarity(a: Long, b: Long): Float? {
        val pa = positionByTrack[a] ?: return null
        val pb = positionByTrack[b] ?: return null
        var dot = 0f
        val baseA = pa * dim
        val baseB = pb * dim
        for (d in 0 until dim) dot += vectors[baseA + d] * vectors[baseB + d]
        return dot
    }

    companion object {
        private const val NO_ID = Long.MIN_VALUE

        /**
         * Cosine similarity for two arbitrary vectors.
         *
         * The general form, with the division. The index does not use it —
         * everything inside is unit length, so [topK] takes the dot product and
         * skips two square roots per comparison. This exists for vectors from
         * outside that have not been through [AudioEmbeddingEngine.l2Normalize].
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                val x = a[i].toDouble()
                val y = b[i].toDouble()
                dot += x * y
                na += x * x
                nb += y * y
            }
            val denom = Math.sqrt(na) * Math.sqrt(nb)
            return if (denom < 1e-12) 0f else (dot / denom).toFloat()
        }

        /** Dot product, for vectors already known to be unit length. */
        fun dot(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
            var acc = 0f
            for (i in a.indices) acc += a[i] * b[i]
            return acc
        }

        /**
         * Builds the index from database rows.
         *
         * Rows whose blob is not [dim] floats are dropped rather than throwing:
         * a single bad row should not deny the user the entire feature, and the
         * indexer will rewrite it on its next pass.
         */
        fun build(rows: List<IndexEntry>, dim: Int): EmbeddingIndex {
            val usable = rows.filter { it.vector.size == dim * Float.SIZE_BYTES }
            val n = usable.size
            val vectors = FloatArray(n * dim)
            val trackIds = LongArray(n)
            val artistIds = LongArray(n)
            val albumIds = LongArray(n)
            val positions = HashMap<Long, Int>(n * 2)

            usable.forEachIndexed { i, row ->
                ByteBuffer.wrap(row.vector).order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().get(vectors, i * dim, dim)
                trackIds[i] = row.trackId
                artistIds[i] = row.artistId ?: NO_ID
                albumIds[i] = row.albumId ?: NO_ID
                positions[row.trackId] = i
            }
            return EmbeddingIndex(dim, n, vectors, trackIds, artistIds, albumIds, positions)
        }

        /** For tests and callers holding floats rather than blobs. */
        fun of(
            entries: List<Triple<Long, FloatArray, Pair<Long?, Long?>>>,
            dim: Int
        ): EmbeddingIndex = build(
            entries.map { (id, vec, ids) ->
                IndexEntry(id, TrackEmbeddingBlob.pack(vec), ids.first, ids.second)
            },
            dim
        )

        fun empty(dim: Int): EmbeddingIndex = build(emptyList(), dim)
    }
}

/**
 * What the index needs from a stored row.
 *
 * Declared here rather than reusing the Room row type so this module does not
 * depend on the database to be testable.
 */
data class IndexEntry(
    val trackId: Long,
    val vector: ByteArray,
    val artistId: Long?,
    val albumId: Long?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IndexEntry) return false
        return trackId == other.trackId && vector.contentEquals(other.vector) &&
            artistId == other.artistId && albumId == other.albumId
    }

    override fun hashCode(): Int {
        var r = trackId.hashCode()
        r = 31 * r + vector.contentHashCode()
        r = 31 * r + (artistId?.hashCode() ?: 0)
        r = 31 * r + (albumId?.hashCode() ?: 0)
        return r
    }
}

/** Float-array to blob, matching the database's encoding exactly. */
object TrackEmbeddingBlob {
    fun pack(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    fun unpack(bytes: ByteArray, dim: Int): FloatArray {
        val out = FloatArray(dim)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}

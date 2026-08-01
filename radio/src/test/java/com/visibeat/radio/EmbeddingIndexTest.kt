package com.visibeat.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class EmbeddingIndexTest {

    private fun unit(vararg v: Float): FloatArray =
        AudioEmbeddingEngine.l2Normalize(v.copyOf())

    private fun index(vararg entries: Triple<Long, FloatArray, Pair<Long?, Long?>>) =
        EmbeddingIndex.of(entries.toList(), entries.first().second.size)

    // ------------------------------------------------------------ similarity

    @Test
    fun `cosine of identical vectors is one and of opposites is minus one`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, EmbeddingIndex.cosineSimilarity(a, a), 1e-6f)
        assertEquals(-1f, EmbeddingIndex.cosineSimilarity(a, floatArrayOf(-1f, -2f, -3f)), 1e-6f)
    }

    @Test
    fun `cosine ignores magnitude`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1000f, 0f, 0f)
        assertEquals(1f, EmbeddingIndex.cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero`() {
        assertEquals(
            0f,
            EmbeddingIndex.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            1e-6f
        )
    }

    @Test
    fun `a zero vector gives zero rather than NaN`() {
        // Silence, or a model that collapsed. One NaN in the index would make
        // every comparison against it fail a max() the wrong way round.
        val z = floatArrayOf(0f, 0f, 0f)
        val s = EmbeddingIndex.cosineSimilarity(z, floatArrayOf(1f, 2f, 3f))
        assertTrue(s.isFinite())
        assertEquals(0f, s, 0f)
    }

    @Test
    fun `normalising leaves a zero vector alone`() {
        val z = AudioEmbeddingEngine.l2Normalize(floatArrayOf(0f, 0f, 0f))
        assertTrue(z.all { it.isFinite() })
    }

    @Test
    fun `normalise produces unit length`() {
        val v = AudioEmbeddingEngine.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, sqrt(v[0] * v[0] + v[1] * v[1]), 1e-6f)
        assertEquals(0.6f, v[0], 1e-6f)
    }

    @Test
    fun `dot product on unit vectors equals cosine`() {
        // The optimisation the index rests on: because everything stored is unit
        // length, topK can skip the denominator entirely.
        val rnd = Random(11)
        repeat(20) {
            val a = AudioEmbeddingEngine.l2Normalize(FloatArray(64) { rnd.nextFloat() - 0.5f })
            val b = AudioEmbeddingEngine.l2Normalize(FloatArray(64) { rnd.nextFloat() - 0.5f })
            assertEquals(EmbeddingIndex.cosineSimilarity(a, b), EmbeddingIndex.dot(a, b), 1e-5f)
        }
    }

    // ---------------------------------------------------------------- search

    @Test
    fun `topK returns neighbours best first`() {
        val idx = index(
            Triple(1L, unit(1f, 0f, 0f), null to null),
            Triple(2L, unit(0.9f, 0.1f, 0f), null to null),
            Triple(3L, unit(0f, 1f, 0f), null to null),
            Triple(4L, unit(0.5f, 0.5f, 0f), null to null)
        )

        val hits = idx.topK(unit(1f, 0f, 0f), k = 3)
        assertEquals(listOf(1L, 2L, 4L), hits.map { it.trackId })
        assertTrue(hits[0].score >= hits[1].score)
        assertTrue(hits[1].score >= hits[2].score)
    }

    @Test
    fun `topK honours exclusions`() {
        val idx = index(
            Triple(1L, unit(1f, 0f), null to null),
            Triple(2L, unit(0.9f, 0.1f), null to null),
            Triple(3L, unit(0.8f, 0.2f), null to null)
        )
        val hits = idx.topK(unit(1f, 0f), k = 3, exclude = setOf(1L, 2L))
        assertEquals(listOf(3L), hits.map { it.trackId })
    }

    @Test
    fun `topK drops anything below the floor`() {
        val idx = index(
            Triple(1L, unit(1f, 0f), null to null),
            Triple(2L, unit(0f, 1f), null to null)
        )
        val hits = idx.topK(unit(1f, 0f), k = 5, minScore = 0.5f)
        assertEquals(listOf(1L), hits.map { it.trackId })
    }

    @Test
    fun `topK returns everything available when k exceeds the library`() {
        val idx = index(
            Triple(1L, unit(1f, 0f), null to null),
            Triple(2L, unit(0f, 1f), null to null)
        )
        assertEquals(2, idx.topK(unit(1f, 1f), k = 50).size)
    }

    @Test
    fun `topK carries artist and album through`() {
        val idx = index(Triple(1L, unit(1f, 0f), 7L to 9L))
        val hit = idx.topK(unit(1f, 0f), k = 1).single()
        assertEquals(7L, hit.artistId)
        assertEquals(9L, hit.albumId)
    }

    @Test
    fun `an empty index searches without throwing`() {
        assertTrue(EmbeddingIndex.empty(8).topK(FloatArray(8), k = 5).isEmpty())
    }

    @Test
    fun `topK agrees with a brute force scan at library scale`() {
        // The insertion buffer in topK is the one piece of hand-rolled ranking in
        // the feature. This checks it against the obvious implementation at the
        // size it will actually run at.
        val rnd = Random(4)
        val dim = 128
        val n = 5000
        val entries = (1..n).map { id ->
            Triple(
                id.toLong(),
                AudioEmbeddingEngine.l2Normalize(FloatArray(dim) { rnd.nextFloat() - 0.5f }),
                (id % 50).toLong() as Long? to (id % 200).toLong() as Long?
            )
        }
        val idx = EmbeddingIndex.of(entries, dim)
        val query = AudioEmbeddingEngine.l2Normalize(FloatArray(dim) { rnd.nextFloat() - 0.5f })

        val expected = entries
            .map { it.first to EmbeddingIndex.dot(it.second, query) }
            .sortedByDescending { it.second }
            .take(10)
            .map { it.first }

        assertEquals(expected, idx.topK(query, k = 10).map { it.trackId })
    }

    @Test
    fun `rows of the wrong dimension are dropped rather than corrupting the index`() {
        // One bad blob should cost one track, not the whole feature.
        val good = TrackEmbeddingBlob.pack(unit(1f, 0f, 0f))
        val bad = TrackEmbeddingBlob.pack(floatArrayOf(1f, 0f))
        val idx = EmbeddingIndex.build(
            listOf(
                IndexEntry(1L, good, null, null),
                IndexEntry(2L, bad, null, null)
            ),
            dim = 3
        )
        assertEquals(1, idx.size)
        assertTrue(idx.contains(1L))
        assertNull(idx.vectorFor(2L))
    }

    @Test
    fun `blob round trip preserves the vector`() {
        val v = unit(0.1f, -0.2f, 0.3f, 0.4f)
        assertTrue(v.contentEquals(TrackEmbeddingBlob.unpack(TrackEmbeddingBlob.pack(v), 4)))
    }

    @Test
    fun `a tombstone row is not loaded as a vector`() {
        // Unreadable tracks are recorded with an empty blob so the indexer does
        // not retry them on every run. They must never reach the index — a
        // zero-length vector would either crash the loader or, worse, be padded
        // into a valid-looking point that every other failure sits next to.
        val idx = EmbeddingIndex.build(
            listOf(
                IndexEntry(1L, TrackEmbeddingBlob.pack(unit(1f, 0f, 0f)), null, null),
                IndexEntry(2L, ByteArray(0), null, null)
            ),
            dim = 3
        )
        assertEquals(1, idx.size)
        assertTrue(idx.contains(1L))
        assertNull(idx.vectorFor(2L))
        assertTrue(idx.topK(unit(1f, 0f, 0f), k = 5).none { it.trackId == 2L })
    }

    @Test
    fun `a query of the wrong dimension fails loudly`() {
        val idx = index(Triple(1L, unit(1f, 0f, 0f), null to null))
        val e = runCatching { idx.topK(floatArrayOf(1f, 0f), k = 1) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}

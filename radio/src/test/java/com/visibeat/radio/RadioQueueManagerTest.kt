package com.visibeat.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The heuristics are the feature.
 *
 * A perfect model with bad selection rules gives you a two-track loop; a mediocre
 * model with good ones gives you a listenable station. None of this is visible in
 * a screenshot and none of it needs a device, so it is all pinned here.
 *
 * [Random] is injected throughout so the sampling is reproducible.
 */
class RadioQueueManagerTest {

    private fun unit(vararg v: Float) = AudioEmbeddingEngine.l2Normalize(v.copyOf())

    /**
     * A synthetic library laid out on a circle.
     *
     * Neighbouring ids are similar, distant ids are not, so "did the station
     * stay coherent" and "did it drift" are both answerable.
     */
    private fun ringLibrary(
        n: Int,
        artistOf: (Int) -> Long? = { (it / 5).toLong() },
        albumOf: (Int) -> Long? = { (it / 3).toLong() }
    ): EmbeddingIndex {
        val entries = (0 until n).map { i ->
            val theta = 2.0 * Math.PI * i / n
            Triple(
                i.toLong(),
                unit(Math.cos(theta).toFloat(), Math.sin(theta).toFloat()),
                artistOf(i) to albumOf(i)
            )
        }
        return EmbeddingIndex.of(entries, 2)
    }

    // ------------------------------------------------------------- basics

    @Test
    fun `generates the requested number of tracks`() {
        val mgr = RadioQueueManager(ringLibrary(200), RadioConfig(), Random(1))
        assertEquals(20, mgr.generate(seedTrackId = 0L).size)
    }

    @Test
    fun `an unknown seed returns nothing rather than throwing`() {
        val mgr = RadioQueueManager(ringLibrary(50), RadioConfig(), Random(1))
        assertTrue(mgr.generate(seedTrackId = 9999L).isEmpty())
    }

    @Test
    fun `the seed never appears in its own station`() {
        val mgr = RadioQueueManager(ringLibrary(100), RadioConfig(), Random(2))
        assertFalse(mgr.generate(0L).any { it.trackId == 0L })
    }

    @Test
    fun `a station never repeats a track`() {
        val mgr = RadioQueueManager(ringLibrary(300), RadioConfig(), Random(3))
        val ids = mgr.generate(10L, length = 40).map { it.trackId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a library smaller than the queue returns what it has`() {
        val mgr = RadioQueueManager(ringLibrary(6), RadioConfig(minSimilarity = -1f), Random(4))
        assertTrue(mgr.generate(0L, length = 20).size <= 5)
    }

    // -------------------------------------------------------- anti-repetition

    @Test
    fun `the two track loop does not happen`() {
        // The failure this whole section exists to prevent. With only a penalty
        // and no hard exclusion, A's nearest is B and B's nearest is A, and a
        // high enough similarity eventually outvotes any finite penalty.
        val idx = EmbeddingIndex.of(
            listOf(
                Triple(1L, unit(1f, 0f), null as Long? to null as Long?),
                Triple(2L, unit(0.999f, 0.045f), null as Long? to null as Long?),
                Triple(3L, unit(0.99f, 0.14f), null as Long? to null as Long?),
                Triple(4L, unit(0.95f, 0.31f), null as Long? to null as Long?),
                Triple(5L, unit(0.9f, 0.44f), null as Long? to null as Long?),
                Triple(6L, unit(0.8f, 0.6f), null as Long? to null as Long?)
            ),
            2
        )
        val picks = RadioQueueManager(idx, RadioConfig(), Random(5)).generate(1L, length = 5)
        assertEquals(picks.map { it.trackId }.size, picks.map { it.trackId }.toSet().size)
    }

    @Test
    fun `recently played tracks are excluded`() {
        val mgr = RadioQueueManager(ringLibrary(100), RadioConfig(), Random(6))
        // Fewer than recencyWindow, so all of them are still in the ring.
        val banned = (1L..25L).toList()
        mgr.notePlayed(banned)
        val ids = mgr.generate(0L, length = 10).map { it.trackId }.toSet()
        assertTrue("station reused $ids", ids.intersect(banned.toSet()).isEmpty())
    }

    @Test
    fun `history beyond the window falls out and becomes playable again`() {
        // The ring is finite on purpose: it is a "not too soon" rule, not a
        // permanent ban, or a long session would run the library dry. This pins
        // the boundary so the eviction is a decision rather than a surprise.
        val mgr = RadioQueueManager(
            ringLibrary(100), RadioConfig(recencyWindow = 5), Random(18)
        )
        mgr.notePlayed(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L))
        val ids = mgr.generate(0L, length = 20).map { it.trackId }.toSet()
        assertTrue("1 and 2 were evicted and should be eligible", ids.contains(1L) || ids.contains(2L))
        assertTrue("6 and 7 are still in the window", !ids.contains(6L) && !ids.contains(7L))
    }

    @Test
    fun `the recency window only holds its capacity`() {
        val recent = RecentTracks(3)
        recent.addAll(listOf(1L, 2L, 3L, 4L))
        assertEquals(3, recent.size)
        assertFalse(1L in recent)
        assertTrue(4L in recent)
    }

    @Test
    fun `re-adding a track already in the window does not evict anything`() {
        // Otherwise a track played twice silently shrinks the effective window.
        val recent = RecentTracks(3)
        recent.addAll(listOf(1L, 2L, 3L))
        recent.add(2L)
        assertEquals(3, recent.size)
        assertTrue(1L in recent)
        assertTrue(3L in recent)
    }

    @Test
    fun `a zero capacity window accepts writes and holds nothing`() {
        val recent = RecentTracks(0)
        recent.add(1L)
        assertEquals(0, recent.size)
        assertFalse(1L in recent)
    }

    // ---------------------------------------------------------- diversity

    @Test
    fun `same artist is penalised against an equally similar alternative`() {
        // Two candidates at identical cosine; one shares the seed's artist. The
        // penalty has to be what separates them.
        val idx = EmbeddingIndex.of(
            listOf(
                Triple(1L, unit(1f, 0f), 100L as Long? to 500L as Long?),
                Triple(2L, unit(0.99f, 0.14f), 100L as Long? to 501L as Long?),
                Triple(3L, unit(0.99f, -0.14f), 200L as Long? to 600L as Long?)
            ),
            2
        )
        val config = RadioConfig(
            candidatePool = 1, sameArtistPenalty = 0.5f, sameAlbumPenalty = 0f, drift = 0f
        )
        val pick = RadioQueueManager(idx, config, Random(7)).generate(1L, length = 1).single()
        assertEquals("should prefer the different artist", 3L, pick.trackId)
    }

    @Test
    fun `same album is penalised harder than same artist`() {
        val idx = EmbeddingIndex.of(
            listOf(
                Triple(1L, unit(1f, 0f), 100L as Long? to 500L as Long?),
                Triple(2L, unit(0.99f, 0.14f), 100L as Long? to 500L as Long?),
                Triple(3L, unit(0.99f, -0.14f), 100L as Long? to 501L as Long?)
            ),
            2
        )
        val config = RadioConfig(candidatePool = 1, drift = 0f)
        val pick = RadioQueueManager(idx, config, Random(8)).generate(1L, length = 1).single()
        assertEquals("same artist beats same album", 3L, pick.trackId)
    }

    @Test
    fun `a full station spreads across artists`() {
        // 200 tracks, 40 artists. Without penalties the ring puts neighbours in
        // the same artist block and the station would sit in two or three.
        val mgr = RadioQueueManager(ringLibrary(200), RadioConfig(), Random(9))
        val artists = mgr.generate(0L, length = 20)
            .mapNotNull { pick -> ringLibrary(200).artistOf(pick.trackId) }
        assertTrue("only ${artists.toSet().size} distinct artists", artists.toSet().size >= 4)
    }

    @Test
    fun `the adjusted score records the penalty that was applied`() {
        val idx = EmbeddingIndex.of(
            listOf(
                Triple(1L, unit(1f, 0f), 100L as Long? to 500L as Long?),
                Triple(2L, unit(0.99f, 0.14f), 100L as Long? to 500L as Long?)
            ),
            2
        )
        val config = RadioConfig(
            candidatePool = 1, sameArtistPenalty = 0.15f, sameAlbumPenalty = 0.2f, drift = 0f
        )
        val pick = RadioQueueManager(idx, config, Random(10)).generate(1L, length = 1).single()
        assertEquals(pick.similarity - 0.35f, pick.adjustedScore, 1e-5f)
    }

    // ------------------------------------------------------------- sampling

    @Test
    fun `the same seed and seed value give the same station`() {
        val a = RadioQueueManager(ringLibrary(200), RadioConfig(), Random(42)).generate(0L)
        val b = RadioQueueManager(ringLibrary(200), RadioConfig(), Random(42)).generate(0L)
        assertEquals(a.map { it.trackId }, b.map { it.trackId })
    }

    @Test
    fun `different random seeds give different stations`() {
        // If they always matched, the top-K sampling would be doing nothing and
        // every station from a given seed would be identical.
        val a = RadioQueueManager(ringLibrary(200), RadioConfig(), Random(1)).generate(0L)
        val b = RadioQueueManager(ringLibrary(200), RadioConfig(), Random(99)).generate(0L)
        assertTrue(a.map { it.trackId } != b.map { it.trackId })
    }

    @Test
    fun `a low temperature is greedy and a high one wanders`() {
        val greedy = RadioQueueManager(
            ringLibrary(200), RadioConfig(temperature = 0.001f), Random(12)
        ).generate(0L, length = 10)
        val loose = RadioQueueManager(
            ringLibrary(200), RadioConfig(temperature = 5f), Random(12)
        ).generate(0L, length = 10)

        val greedyMean = greedy.map { it.similarity }.average()
        val looseMean = loose.map { it.similarity }.average()
        assertTrue(
            "greedy $greedyMean should not be looser than loose $looseMean",
            greedyMean >= looseMean - 1e-6
        )
    }

    @Test
    fun `softmax does not overflow at the default temperature`() {
        // Scores over a temperature of 0.05 reach ~20 before exponentiating.
        // Without the max-shift these become Infinity, every weight becomes NaN,
        // and the draw silently collapses to the first candidate every time.
        val mgr = RadioQueueManager(ringLibrary(500), RadioConfig(temperature = 0.01f), Random(13))
        val picks = mgr.generate(0L, length = 20)
        assertEquals(20, picks.size)
        assertTrue(picks.all { it.adjustedScore.isFinite() })
    }

    // ---------------------------------------------------------------- drift

    @Test
    fun `zero drift keeps the station near the seed`() {
        val idx = ringLibrary(360)
        val mgr = RadioQueueManager(idx, RadioConfig(drift = 0f, minSimilarity = -1f), Random(14))
        val picks = mgr.generate(0L, length = 20)
        val worst = picks.minOf { idx.similarity(0L, it.trackId) ?: -1f }
        assertTrue("drifted to $worst from the seed", worst > 0.5f)
    }

    @Test
    fun `full drift travels further than none`() {
        val idx = ringLibrary(360)
        fun meanDistance(drift: Float): Double {
            val mgr = RadioQueueManager(
                idx, RadioConfig(drift = drift, minSimilarity = -1f), Random(15)
            )
            return mgr.generate(0L, length = 20)
                .mapNotNull { idx.similarity(0L, it.trackId) }
                .average()
        }
        assertTrue(
            "full drift should end up less similar to the seed",
            meanDistance(1f) < meanDistance(0f)
        )
    }

    @Test
    fun `the similarity floor stops the station playing unrelated music`() {
        // Two clusters at opposite poles. With a floor, a station seeded in one
        // must never reach the other, however long it runs.
        val entries = buildList {
            repeat(20) { i ->
                val t = i * 0.01
                add(Triple(i.toLong(), unit(Math.cos(t).toFloat(), Math.sin(t).toFloat()), i.toLong() as Long? to null as Long?))
            }
            repeat(20) { i ->
                val t = Math.PI + i * 0.01
                add(Triple((100 + i).toLong(), unit(Math.cos(t).toFloat(), Math.sin(t).toFloat()), (100 + i).toLong() as Long? to null as Long?))
            }
        }
        val idx = EmbeddingIndex.of(entries, 2)
        val mgr = RadioQueueManager(idx, RadioConfig(minSimilarity = 0.5f), Random(16))
        assertTrue(mgr.generate(0L, length = 30).none { it.trackId >= 100L })
    }

    @Test
    fun `reset clears the history so a new station can reuse tracks`() {
        val mgr = RadioQueueManager(ringLibrary(60), RadioConfig(), Random(17))
        val first = mgr.generate(0L, length = 10).map { it.trackId }.toSet()
        mgr.reset()
        val second = mgr.generate(0L, length = 10).map { it.trackId }.toSet()
        assertTrue(first.intersect(second).isNotEmpty())
    }
}

package com.visibeat.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Per-origin station shapes.
 *
 * The seed is a track id whatever was tapped, so the only thing distinguishing
 * an artist station from a track station is this configuration. That made it
 * possible for one shared config to have an artist station fining the artist it
 * was named after, which is the case these exist to keep fixed.
 */
class RadioOriginTest {

    private fun unit(vararg v: Float) = AudioEmbeddingEngine.l2Normalize(v.copyOf())

    /** Seed plus three of its artist and three of another, all equally close. */
    private fun twoArtists(): EmbeddingIndex = EmbeddingIndex.of(
        listOf(
            Triple(1L, unit(1f, 0f), 100L as Long? to 500L as Long?),
            Triple(2L, unit(0.99f, 0.10f), 100L as Long? to 501L as Long?),
            Triple(3L, unit(0.99f, 0.12f), 100L as Long? to 502L as Long?),
            Triple(4L, unit(0.99f, 0.14f), 100L as Long? to 503L as Long?),
            Triple(5L, unit(0.99f, -0.10f), 200L as Long? to 600L as Long?),
            Triple(6L, unit(0.99f, -0.12f), 200L as Long? to 601L as Long?),
            Triple(7L, unit(0.99f, -0.14f), 200L as Long? to 602L as Long?)
        ),
        2
    )

    @Test
    fun `an artist station does not push away from its own artist`() {
        // The bug. With one shared config the seed's artist was penalised from
        // the first pick, so asking for an artist made them less likely.
        val idx = twoArtists()
        val picks = RadioQueueManager(
            idx, RadioConfig.forOrigin(RadioOrigin.ARTIST), Random(1)
        ).generate(1L, length = 3)

        val own = picks.count { idx.artistOf(it.trackId) == 100L }
        assertTrue("only $own of ${picks.size} picks were the seeded artist", own >= 2)
    }

    @Test
    fun `a track station still spreads away from the seed artist`() {
        val idx = twoArtists()
        val picks = RadioQueueManager(
            idx, RadioConfig.forOrigin(RadioOrigin.TRACK), Random(1)
        ).generate(1L, length = 3)
        val other = picks.count { idx.artistOf(it.trackId) == 200L }
        assertTrue("track radio should reach the other artist, got $other", other >= 1)
    }

    @Test
    fun `artist radio rewards, track and album radio fine`() {
        assertTrue(RadioConfig.forOrigin(RadioOrigin.ARTIST).sameArtistPenalty < 0f)
        assertTrue(RadioConfig.forOrigin(RadioOrigin.TRACK).sameArtistPenalty > 0f)
        assertTrue(RadioConfig.forOrigin(RadioOrigin.ALBUM).sameArtistPenalty > 0f)
    }

    @Test
    fun `drift tightens as the seed gets more specific`() {
        // A track names a sound and may wander; an artist names a world.
        val t = RadioConfig.forOrigin(RadioOrigin.TRACK).drift
        val a = RadioConfig.forOrigin(RadioOrigin.ALBUM).drift
        val r = RadioConfig.forOrigin(RadioOrigin.ARTIST).drift
        assertTrue("$t > $a > $r", t > a && a > r)
    }

    @Test
    fun `album radio still spreads across a catalogue rather than replaying one record`() {
        assertTrue(RadioConfig.forOrigin(RadioOrigin.ALBUM).sameAlbumPenalty > 0f)
        assertTrue(RadioConfig.forOrigin(RadioOrigin.ARTIST).sameAlbumPenalty > 0f)
    }

    // ------------------------------------------------------------- genre

    @Test
    fun `genre weighting is off everywhere by default`() {
        // Half the library has no genre tag. A signal present for half the
        // tracks does not weight a ranking, it biases one.
        for (o in RadioOrigin.values()) {
            assertEquals(0f, RadioConfig.forOrigin(o).genreWeight, 0f)
        }
    }

    @Test
    fun `a matching genre outranks an equally similar mismatch when weighted`() {
        val idx = EmbeddingIndex.of(
            listOf(
                Triple(1L, unit(1f, 0f), 100L as Long? to 500L as Long?),
                Triple(2L, unit(0.99f, 0.14f), 200L as Long? to 600L as Long?),
                Triple(3L, unit(0.99f, -0.14f), 300L as Long? to 700L as Long?)
            ),
            2
        )
        val genres = GenreLookup {
            when (it) {
                1L, 3L -> setOf("ambient", "classical")
                2L -> setOf("hip hop")
                else -> emptySet()
            }
        }
        val pick = RadioQueueManager(
            idx,
            RadioConfig.forOrigin(RadioOrigin.ARTIST).copy(genreWeight = 0.3f, candidatePool = 1),
            Random(2),
            genres
        ).generate(1L, length = 1).single()
        assertEquals("should prefer the genre match", 3L, pick.trackId)
    }

    @Test
    fun `an untagged track is not punished for missing metadata`() {
        // Scoring "no tag" as disagreement would rank every untagged track below
        // every tagged match — a filter on metadata completeness, not on taste.
        val idx = EmbeddingIndex.of(
            listOf(
                Triple(1L, unit(1f, 0f), 100L as Long? to 500L as Long?),
                // Tagged and matching, but acoustically distant.
                Triple(2L, unit(0.75f, 0.66f), 200L as Long? to 600L as Long?),
                // Untagged, but nearly identical to the seed.
                Triple(3L, unit(0.99f, -0.14f), 300L as Long? to 700L as Long?)
            ),
            2
        )
        val genres = GenreLookup {
            when (it) {
                1L, 2L -> setOf("ambient")
                else -> emptySet()          // track 3 has no tags at all
            }
        }
        val pick = RadioQueueManager(
            idx,
            RadioConfig.forOrigin(RadioOrigin.ARTIST).copy(genreWeight = 0.3f, candidatePool = 1),
            Random(3),
            genres
        ).generate(1L, length = 1).single()
        // The gap is chosen so the answer depends on the neutral score: at 0.5
        // the untagged track keeps its 0.24 acoustic lead and wins, and at 0.0
        // — scoring "no tag" as disagreement — the distant genre match takes it.
        assertEquals(3L, pick.trackId)
    }
}

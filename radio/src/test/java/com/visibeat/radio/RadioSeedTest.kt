package com.visibeat.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RadioSeedTest {

    private val album = (1L..12L).toList()
    private val artistByPopularity = (100L..130L).toList()

    @Test
    fun `an album station does not always start from track one`() {
        // The complaint this exists to answer: a fixed seed is a fixed station.
        val seeds = (1..40).map { RadioSeed.forAlbum(album, Random(it)) }.toSet()
        assertTrue("only ${seeds.size} distinct seeds from 12 tracks", seeds.size >= 5)
    }

    @Test
    fun `an album seed is always from that album`() {
        repeat(40) { assertTrue(RadioSeed.forAlbum(album, Random(it)) in album) }
    }

    @Test
    fun `an artist seed varies but stays among their better known tracks`() {
        val seeds = (1..60).map { RadioSeed.forArtist(artistByPopularity, Random(it)) }.toSet()
        assertTrue("only ${seeds.size} distinct seeds", seeds.size >= 4)
        // Never the deep cut at position 30: variety should not mean seeding a
        // station on something the listener skipped.
        val pool = artistByPopularity.take(RadioSeed.ARTIST_POOL).toSet()
        assertTrue("seeded outside the top ${RadioSeed.ARTIST_POOL}: ${seeds - pool}", seeds.all { it in pool })
    }

    @Test
    fun `a short artist list is handled without dropping anything`() {
        val two = listOf(7L, 8L)
        assertTrue(RadioSeed.forArtist(two, Random(1)) in two)
    }

    @Test
    fun `an empty collection yields no seed rather than throwing`() {
        assertNull(RadioSeed.forAlbum(emptyList()))
        assertNull(RadioSeed.forArtist(emptyList()))
    }

    @Test
    fun `a single track album always seeds itself`() {
        assertEquals(42L, RadioSeed.forAlbum(listOf(42L), Random(3)))
    }
}

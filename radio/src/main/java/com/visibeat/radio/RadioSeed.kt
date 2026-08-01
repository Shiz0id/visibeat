package com.visibeat.radio

import kotlin.random.Random

/**
 * Choosing which track a collection's station starts from.
 *
 * A station is defined by its seed, so a fixed seed is a fixed station: opening
 * artist radio twice used to give the same twenty tracks in a slightly
 * different order, because both runs started from the same song and the only
 * variation left was the softmax draw over near-identical candidates.
 *
 * Not an average of the collection either. For an album a centroid would be
 * defensible — album tracks sit at 0.76 mean cosine, so their middle is a real
 * point in the space — but for an artist with a thirty-year career it is mush,
 * and one rule that behaves well in both places is worth more than two rules.
 * Varying the seed gets the same variety without inventing a vector that no
 * track actually sounds like.
 */
object RadioSeed {

    /**
     * How far down an artist's track list a seed may come from.
     *
     * Bounded rather than open: an artist's most-played tracks are the ones the
     * listener actually reaches for, and seeding a station on the deep cut they
     * skipped twice is technically variety and practically a worse station.
     */
    const val ARTIST_POOL = 8

    /** Any track on the record. An album is coherent enough that all of it qualifies. */
    fun forAlbum(trackIds: List<Long>, random: Random = Random.Default): Long? =
        trackIds.randomOrNull(random)

    /** One of the artist's better-known tracks, but not always the same one. */
    fun forArtist(trackIdsByPopularity: List<Long>, random: Random = Random.Default): Long? =
        trackIdsByPopularity.take(ARTIST_POOL).randomOrNull(random)
}

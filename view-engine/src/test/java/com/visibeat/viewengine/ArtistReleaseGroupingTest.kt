package com.visibeat.viewengine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which shelf a release lands on.
 *
 * The case that motivated the guest flag: Rihanna sings one verse on "LOYALTY.",
 * so `track_artist` credits her on a track of *DAMN.*, and a release list built
 * from "any credit on any track" put Kendrick Lamar's album in her discography.
 */
class ArtistReleaseGroupingTest {

    @Test
    fun `own album goes to Albums`() {
        assertEquals(
            ArtistReleaseGrouping.ALBUMS,
            ArtistReleaseGrouping.sectionFor("ALBUM", isPrimaryArtist = true)
        )
    }

    @Test
    fun `own single and EP go to the short-form shelf`() {
        assertEquals(
            ArtistReleaseGrouping.SINGLES_AND_EPS,
            ArtistReleaseGrouping.sectionFor("SINGLE", isPrimaryArtist = true)
        )
        assertEquals(
            ArtistReleaseGrouping.SINGLES_AND_EPS,
            ArtistReleaseGrouping.sectionFor("EP", isPrimaryArtist = true)
        )
    }

    @Test
    fun `guest credit outranks release type`() {
        // The precedence that fixes the bug. An album you only guest on is not
        // an album of yours, so the type is not consulted at all.
        assertEquals(
            ArtistReleaseGrouping.APPEARS_ON,
            ArtistReleaseGrouping.sectionFor("ALBUM", isPrimaryArtist = false)
        )
        assertEquals(
            ArtistReleaseGrouping.APPEARS_ON,
            ArtistReleaseGrouping.sectionFor("SINGLE", isPrimaryArtist = false)
        )
    }

    @Test
    fun `unenriched releases are still albums when they are the artist's own`() {
        // Everything is "UNKNOWN" until MusicBrainz enrichment reaches it, and on
        // a fresh library that is the entire discography.
        assertEquals(
            ArtistReleaseGrouping.ALBUMS,
            ArtistReleaseGrouping.sectionFor("UNKNOWN", isPrimaryArtist = true)
        )
        assertEquals(
            ArtistReleaseGrouping.ALBUMS,
            ArtistReleaseGrouping.sectionFor(null, isPrimaryArtist = true)
        )
    }

    @Test
    fun `unenriched guest appearance still lands on Appears On`() {
        assertEquals(
            ArtistReleaseGrouping.APPEARS_ON,
            ArtistReleaseGrouping.sectionFor(null, isPrimaryArtist = false)
        )
    }

    @Test
    fun `release type matching is case insensitive`() {
        assertEquals(
            ArtistReleaseGrouping.SINGLES_AND_EPS,
            ArtistReleaseGrouping.sectionFor("single", isPrimaryArtist = true)
        )
    }

    @Test
    fun `defaults to the artist's own work when the flag is not supplied`() {
        // Keeps every existing caller meaning what it used to mean.
        assertEquals(ArtistReleaseGrouping.ALBUMS, ArtistReleaseGrouping.sectionFor("ALBUM"))
    }
}

package com.visibeat.musicbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistImageUrlsTest {

    // ── Commons URL construction ──────────────────────────

    @Test
    fun `builds a Special FilePath url at the requested width`() {
        val url = ArtistImageUrls.commonsImageUrl("Radiohead.jpg", width = 640)
        assertEquals(
            "https://commons.wikimedia.org/wiki/Special:FilePath/Radiohead.jpg?width=640",
            url
        )
    }

    @Test
    fun `spaces are percent-encoded, not turned into plus signs`() {
        // Commons filenames routinely contain spaces. URLEncoder is form-encoding
        // and would emit '+', which Commons reads as a literal plus and 404s.
        val url = ArtistImageUrls.commonsImageUrl("RadioheadO2211125 composite.jpg")
        assertTrue("got $url", url!!.contains("RadioheadO2211125%20composite.jpg"))
        assertFalse("got $url", url.contains("+"))
    }

    @Test
    fun `non-ascii filenames survive encoding`() {
        val url = ArtistImageUrls.commonsImageUrl("Björk 2017.jpg")
        assertTrue("got $url", url!!.startsWith("https://commons.wikimedia.org/"))
        assertFalse("got $url", url.contains(" "))
    }

    @Test
    fun `apostrophes and parentheses are preserved usably`() {
        val url = ArtistImageUrls.commonsImageUrl("Sinéad O'Connor (1988).jpg")
        assertTrue("got $url", url!!.contains("width="))
        assertFalse("got $url", url.contains(" "))
    }

    @Test
    fun `a blank filename yields no url`() {
        assertNull(ArtistImageUrls.commonsImageUrl(""))
        assertNull(ArtistImageUrls.commonsImageUrl("   "))
    }

    // ── Wikidata id extraction ────────────────────────────

    @Test
    fun `extracts a Q id from a wikidata url`() {
        assertEquals(
            "Q44190",
            ArtistImageUrls.wikidataIdFromUrl("https://www.wikidata.org/wiki/Q44190")
        )
    }

    @Test
    fun `tolerates a trailing slash`() {
        assertEquals(
            "Q44190",
            ArtistImageUrls.wikidataIdFromUrl("https://www.wikidata.org/wiki/Q44190/")
        )
    }

    @Test
    fun `rejects anything that is not a Q id`() {
        // A property or lexeme url must not be mistaken for an item.
        assertNull(ArtistImageUrls.wikidataIdFromUrl("https://www.wikidata.org/wiki/P434"))
        assertNull(ArtistImageUrls.wikidataIdFromUrl("https://en.wikipedia.org/wiki/Radiohead"))
        assertNull(ArtistImageUrls.wikidataIdFromUrl(""))
        assertNull(ArtistImageUrls.wikidataIdFromUrl(null))
    }

    // ── retry policy ──────────────────────────────────────

    @Test
    fun `an artist with a portrait is never looked up again`() {
        assertFalse(
            ArtistImageUrls.shouldLookUp(
                hasImage = true, attempts = 1, lastAttemptAt = 0L, now = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `an artist never checked is looked up`() {
        assertTrue(
            ArtistImageUrls.shouldLookUp(
                hasImage = false, attempts = 0, lastAttemptAt = null, now = 1_000L
            )
        )
    }

    @Test
    fun `a recent miss is not retried immediately`() {
        // Without this, every launch would re-query the whole long tail of a
        // library against a one-request-per-second API.
        val now = 10_000_000L
        assertFalse(
            ArtistImageUrls.shouldLookUp(
                hasImage = false,
                attempts = 1,
                lastAttemptAt = now - 1000L,
                now = now
            )
        )
    }

    @Test
    fun `an old miss is retried`() {
        val now = 10_000_000_000L
        assertTrue(
            ArtistImageUrls.shouldLookUp(
                hasImage = false,
                attempts = 1,
                lastAttemptAt = now - ArtistImageUrls.RETRY_AFTER_MS - 1,
                now = now
            )
        )
    }

    @Test
    fun `repeated misses eventually stop`() {
        val now = 10_000_000_000L
        assertFalse(
            ArtistImageUrls.shouldLookUp(
                hasImage = false,
                attempts = ArtistImageUrls.MAX_ATTEMPTS,
                lastAttemptAt = now - ArtistImageUrls.RETRY_AFTER_MS * 100,
                now = now
            )
        )
    }

    @Test
    fun `the retry window is long enough to be polite`() {
        assertTrue(
            "retry window is too short for a rate-limited public API",
            ArtistImageUrls.RETRY_AFTER_MS >= 7L * 24 * 60 * 60 * 1000
        )
    }
}

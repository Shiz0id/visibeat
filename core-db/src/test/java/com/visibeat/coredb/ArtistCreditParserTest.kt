package com.visibeat.coredb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splitting artist credits is a one-way operation on someone's library, and the
 * failure mode is shredding real band names. These tests are weighted towards
 * what must *not* split.
 */
class ArtistCreditParserTest {

    private fun known(vararg names: String): (String) -> Boolean {
        val set = names.map { normalizeArtistName(it) }.toSet()
        return { candidate -> normalizeArtistName(candidate) in set }
    }

    // ── featuring keywords: always safe to split ──────────

    @Test
    fun `splits on feat with a full stop`() {
        val credit = ArtistCreditParser.parse("20syl feat. Oddisee")!!
        assertEquals("20syl", credit.primary)
        assertEquals(listOf("Oddisee"), credit.featured)
    }

    @Test
    fun `splits on the common featuring spellings`() {
        val forms = listOf(
            "A feat. B", "A feat B", "A ft. B", "A ft B",
            "A featuring B", "A f/ B", "A vs. B", "A presents B"
        )
        for (form in forms) {
            val credit = ArtistCreditParser.parse(form)!!
            assertEquals("$form -> primary", "A", credit.primary)
            assertEquals("$form -> featured", listOf("B"), credit.featured)
        }
    }

    @Test
    fun `keeps the original string as the track credit`() {
        // The song is still called "20syl feat. Oddisee" — only the artist graph
        // is split apart.
        val credit = ArtistCreditParser.parse("20syl feat. Oddisee")!!
        assertEquals("20syl feat. Oddisee", credit.displayCredit)
    }

    @Test
    fun `a featuring keyword inside a word does not split`() {
        // "Ftisland" starts with ft, "Presenting" contains presents.
        assertEquals("Ftisland", ArtistCreditParser.parse("Ftisland")!!.primary)
        assertEquals("Featherweight", ArtistCreditParser.parse("Featherweight")!!.primary)
        assertTrue(ArtistCreditParser.parse("Ftisland")!!.featured.isEmpty())
    }

    @Test
    fun `a featured list after feat splits on commas`() {
        val credit = ArtistCreditParser.parse("Drake feat. Future, Young Thug")!!
        assertEquals("Drake", credit.primary)
        assertEquals(listOf("Future", "Young Thug"), credit.featured)
    }

    // ── structural delimiters: always safe ────────────────

    @Test
    fun `splits on the id3 null delimiter`() {
        val credit = ArtistCreditParser.parse("Artist One\u0000Artist Two")!!
        assertEquals("Artist One", credit.primary)
        assertEquals(listOf("Artist Two"), credit.featured)
    }

    @Test
    fun `splits on semicolons`() {
        val credit = ArtistCreditParser.parse("Artist One; Artist Two")!!
        assertEquals("Artist One", credit.primary)
        assertEquals(listOf("Artist Two"), credit.featured)
    }

    @Test
    fun `splits on a spaced slash but not a bare one`() {
        val spaced = ArtistCreditParser.parse("Artist One / Artist Two")!!
        assertEquals(listOf("Artist Two"), spaced.featured)

        // AC/DC must survive intact.
        val acdc = ArtistCreditParser.parse("AC/DC")!!
        assertEquals("AC/DC", acdc.primary)
        assertTrue(acdc.featured.isEmpty())
    }

    // ── the dangerous ones: must NOT split ────────────────

    @Test
    fun `protected band names survive with no corroboration`() {
        val names = listOf(
            "Earth, Wind & Fire",
            "Simon & Garfunkel",
            "Crosby, Stills, Nash & Young",
            "Tyler, The Creator",
            "Florence + the Machine",
            "Blood, Sweat & Tears",
            "Hall & Oates",
            "Peter, Paul and Mary",
            "Now, Now",
            "Of Monsters and Men",
            "Tegan and Sara"
        )
        for (name in names) {
            val credit = ArtistCreditParser.parse(name)!!
            assertEquals("$name must not split", name, credit.primary)
            assertTrue("$name must not split", credit.featured.isEmpty())
        }
    }

    @Test
    fun `an unknown comma name is left alone rather than guessed at`() {
        // Nothing corroborates a split, so the conservative answer wins even
        // though this happens to be a collaboration.
        val credit = ArtistCreditParser.parse("24kGoldn, DaBaby")!!
        assertEquals("24kGoldn, DaBaby", credit.primary)
        assertTrue(credit.featured.isEmpty())
    }

    @Test
    fun `an unknown ampersand name is left alone`() {
        val credit = ArtistCreditParser.parse("Some Band & Another")!!
        assertEquals("Some Band & Another", credit.primary)
    }

    // ── corroborated splitting ────────────────────────────

    @Test
    fun `a comma splits once the lead artist is known`() {
        // This is the screenshot's case: 24kGoldn already has nine tracks of
        // their own, so "24kGoldn, DaBaby" is a collaboration, not a band.
        val credit = ArtistCreditParser.parse("24kGoldn, DaBaby", known("24kGoldn"))!!
        assertEquals("24kGoldn", credit.primary)
        assertEquals(listOf("DaBaby"), credit.featured)
    }

    @Test
    fun `knowing the lead does not split a protected name`() {
        // Even if "Earth" somehow exists as an artist, the whole name is
        // protected and must win.
        val credit = ArtistCreditParser.parse("Earth, Wind & Fire", known("Earth"))!!
        assertEquals("Earth, Wind & Fire", credit.primary)
        assertTrue(credit.featured.isEmpty())
    }

    @Test
    fun `a name that already exists verbatim is never taken apart`() {
        // The strongest guard: once the library has the band, nothing shreds it.
        val credit = ArtistCreditParser.parse(
            "Earth, Wind & Fire",
            known("Earth, Wind & Fire", "Earth")
        )!!
        assertEquals("Earth, Wind & Fire", credit.primary)
        assertTrue(credit.featured.isEmpty())
    }

    @Test
    fun `a split piece that is a protected name blocks the split`() {
        val credit = ArtistCreditParser.parse("Tegan and Sara", known("Tegan"))!!
        assertEquals("Tegan and Sara", credit.primary)
    }

    @Test
    fun `three-way collaborations split when the lead is known`() {
        val credit = ArtistCreditParser.parse("24kGoldn, DaBaby, Future", known("24kGoldn"))!!
        assertEquals("24kGoldn", credit.primary)
        assertEquals(listOf("DaBaby", "Future"), credit.featured)
    }

    // ── general behaviour ─────────────────────────────────

    @Test
    fun `blank input yields nothing`() {
        assertNull(ArtistCreditParser.parse(null))
        assertNull(ArtistCreditParser.parse(""))
        assertNull(ArtistCreditParser.parse("   "))
    }

    @Test
    fun `whitespace is collapsed and trimmed`() {
        val credit = ArtistCreditParser.parse("  Radiohead   ")!!
        assertEquals("Radiohead", credit.primary)
        assertEquals("Radiohead", credit.displayCredit)
    }

    @Test
    fun `curly punctuation is normalised`() {
        val credit = ArtistCreditParser.parse("Guns N’ Roses")!!
        assertEquals("Guns N' Roses", credit.primary)
    }

    @Test
    fun `the primary is never repeated in the featured list`() {
        val credit = ArtistCreditParser.parse("Drake feat. Drake")!!
        assertEquals("Drake", credit.primary)
        assertTrue(credit.featured.isEmpty())
    }

    @Test
    fun `duplicate featured artists are collapsed`() {
        val credit = ArtistCreditParser.parse("A feat. B, B")!!
        assertEquals(listOf("B"), credit.featured)
    }

    @Test
    fun `a simple single artist passes straight through`() {
        val credit = ArtistCreditParser.parse("Radiohead")!!
        assertEquals("Radiohead", credit.primary)
        assertTrue(credit.featured.isEmpty())
        assertTrue(!credit.isComposite)
    }

    @Test
    fun `all lists the primary first`() {
        val credit = ArtistCreditParser.parse("A feat. B, C")!!
        assertEquals(listOf("A", "B", "C"), credit.all)
    }
}

class NormalizeArtistNameTest {

    @Test
    fun `case and whitespace are folded`() {
        assertEquals(normalizeArtistName("Radiohead"), normalizeArtistName("  radiohead "))
        assertEquals(normalizeArtistName("The  Beatles"), normalizeArtistName("The Beatles"))
    }

    @Test
    fun `diacritics fold so spelling variants merge`() {
        // These are the same artist typed two ways, and each variant was
        // producing its own row.
        assertEquals(normalizeArtistName("Beyoncé"), normalizeArtistName("Beyonce"))
        assertEquals(normalizeArtistName("Sinéad O'Connor"), normalizeArtistName("Sinead O'Connor"))
        assertEquals(normalizeArtistName("Björk"), normalizeArtistName("Bjork"))
    }

    @Test
    fun `curly apostrophes fold to straight ones`() {
        assertEquals(normalizeArtistName("Guns N’ Roses"), normalizeArtistName("Guns N' Roses"))
    }

    @Test
    fun `a leading The is deliberately preserved`() {
        // Stripping it would merge "The The" into nothing useful and conflate
        // distinct acts. Documented as a decision, not an oversight.
        assertTrue(normalizeArtistName("The Beatles") != normalizeArtistName("Beatles"))
    }

    @Test
    fun `distinct artists stay distinct`() {
        assertTrue(normalizeArtistName("Drake") != normalizeArtistName("Drake Bell"))
        assertTrue(normalizeArtistName("Earth, Wind & Fire") != normalizeArtistName("Earth"))
    }

    @Test
    fun `blank input normalises to empty`() {
        assertEquals("", normalizeArtistName(""))
        assertEquals("", normalizeArtistName("   "))
    }
}

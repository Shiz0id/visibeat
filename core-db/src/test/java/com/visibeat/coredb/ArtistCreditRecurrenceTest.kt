package com.visibeat.coredb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splitting a credit because one of its components turns up elsewhere.
 *
 * The lead-must-already-be-known rule cannot reach a soundtrack: every performer
 * appears only inside a composite credit, so nobody is ever standalone and no
 * credit ever splits. The library ends up with a row per collaboration instead
 * of a row per artist — "Alice Smith, Miles Caton, Sinners Movie", "Bobby Rush,
 * Miles Caton, Sinners Movie", and so on.
 *
 * These fix the shape of the escape hatch, and — more importantly — the limits
 * on it, because a rule that splits too eagerly shreds band names irreversibly.
 */
class ArtistCreditRecurrenceTest {

    /** The credits from the soundtrack that prompted this. */
    private val soundtrackCredits = listOf(
        "Alice Smith, Miles Caton, Sinners Movie",
        "Bobby Rush, Miles Caton, Sinners Movie",
        "Brittany Howard, Sinners Movie",
        "Buddy Guy, Sinners Movie"
    )

    /** Mirrors ArtistMaintenance.recurringComponents. */
    private fun recurring(credits: List<String>, threshold: Int = 2): Set<String> {
        val counts = mutableMapOf<String, Int>()
        credits.forEach { credit ->
            ArtistCreditParser.candidateComponents(credit)
                .map { normalizeArtistName(it) }
                .filter { it.isNotEmpty() }
                .toSet()
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.filterValues { it >= threshold }.keys
    }

    private fun parseWithRecurrence(credit: String, credits: List<String>): ArtistCredit? {
        val set = recurring(credits)
        return ArtistCreditParser.parse(
            raw = credit,
            isKnownArtist = { false },
            isRecurringComponent = { normalizeArtistName(it) in set }
        )
    }

    // ── the case that failed ──────────────────────────────

    @Test
    fun `a soundtrack credit splits even though no member stands alone`() {
        val credit = parseWithRecurrence(soundtrackCredits[0], soundtrackCredits)!!
        assertEquals("Alice Smith", credit.primary)
        assertEquals(listOf("Miles Caton", "Sinners Movie"), credit.featured)
    }

    @Test
    fun `recurrence corroborates from a trailing position, not only the lead`() {
        // The point of the change: none of the leads recur — only the shared
        // trailing marker does. A lead-only rule would still refuse to split.
        val set = recurring(soundtrackCredits)
        assertTrue("sinners movie" in set)
        assertTrue("miles caton" in set)
        assertTrue("alice smith" !in set)
        assertTrue("buddy guy" !in set)
    }

    @Test
    fun `every performer on the soundtrack becomes reachable`() {
        val everyone = soundtrackCredits
            .mapNotNull { parseWithRecurrence(it, soundtrackCredits) }
            .flatMap { it.all }
            .map { normalizeArtistName(it) }
            .toSet()
        listOf("alice smith", "bobby rush", "brittany howard", "buddy guy", "miles caton")
            .forEach { assertTrue("$it should be its own artist", it in everyone) }
    }

    // ── the limits, which matter more ─────────────────────

    @Test
    fun `a protected band name is never taken apart, however often it recurs`() {
        val credits = List(20) { "Earth, Wind & Fire" }
        assertTrue(
            "a protected name must contribute no components at all",
            ArtistCreditParser.candidateComponents("Earth, Wind & Fire").isEmpty()
        )
        val credit = parseWithRecurrence("Earth, Wind & Fire", credits)!!
        assertEquals("Earth, Wind & Fire", credit.primary)
        assertTrue(credit.featured.isEmpty())
    }

    @Test
    fun `a band name on a hundred tracks is still one credit and does not self-corroborate`() {
        // Counting rows rather than tracks is the whole safety argument: an
        // artist is one row no matter how large its discography, so its pieces
        // can never reach the threshold on their own.
        val set = recurring(listOf("Bob, Alice and Carol Band"))
        assertTrue("a single credit corroborates nothing", set.isEmpty())
    }

    @Test
    fun `a repeated token inside one credit is not two votes`() {
        val set = recurring(listOf("Sinners Movie, Sinners Movie"))
        assertTrue(set.isEmpty())
    }

    @Test
    fun `an unrelated comma name is untouched when nothing it contains recurs`() {
        val credits = soundtrackCredits + "Tyler, The Creator"
        val credit = parseWithRecurrence("Tyler, The Creator", credits)!!
        assertEquals("Tyler, The Creator", credit.primary)
        assertTrue(credit.featured.isEmpty())
    }

    @Test
    fun `structural and featuring delimiters still split without any corroboration`() {
        // Recurrence is an addition, not a replacement — the unambiguous
        // delimiters must keep working on their own.
        val viaFeature = ArtistCreditParser.parse("24kGoldn feat. DaBaby")!!
        assertEquals("24kGoldn", viaFeature.primary)
        assertEquals(listOf("DaBaby"), viaFeature.featured)

        val viaStructural = ArtistCreditParser.parse("A;B")!!
        assertEquals("A", viaStructural.primary)
        assertEquals(listOf("B"), viaStructural.featured)
    }

    @Test
    fun `default parse behaviour is unchanged when no recurrence is supplied`() {
        // Ingest passes no recurrence set, so its results must not move.
        val credit = ArtistCreditParser.parse(soundtrackCredits[0])!!
        assertEquals(soundtrackCredits[0], credit.primary)
        assertTrue("ingest must stay conservative", credit.featured.isEmpty())
    }
}

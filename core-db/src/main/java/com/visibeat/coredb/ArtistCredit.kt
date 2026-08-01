package com.visibeat.coredb

import java.text.Normalizer
import java.util.Locale

/**
 * One track's artist credit, broken into the individual artists behind it.
 *
 * [displayCredit] is what the track should still say — "24kGoldn feat. DaBaby"
 * is the right label for the song. [primary] and [featured] are the artists that
 * credit refers to, which is what the artist list and artist pages are built
 * from. Conflating the two is why the library ended up with rows for
 * "24kGoldn, DaBaby" and "24kGoldn, Future" sitting next to "24kGoldn".
 */
data class ArtistCredit(
    val displayCredit: String,
    val primary: String,
    val featured: List<String> = emptyList()
) {
    /** Every artist in the credit, primary first. */
    val all: List<String> get() = listOf(primary) + featured

    val isComposite: Boolean get() = featured.isNotEmpty()
}

/**
 * Splits artist credit strings into individual artists.
 *
 * ### The hazard
 *
 * Splitting is easy; splitting *safely* is not. Plenty of real acts contain the
 * very characters that usually separate artists — Earth, Wind & Fire; Tyler, The
 * Creator; AC/DC; Simon & Garfunkel; Crosby, Stills, Nash & Young; Florence + the
 * Machine. A naive split shreds a library and there is no undo. So delimiters are
 * graded by how often they appear inside a genuine name:
 *
 *  - **Structural** (NUL, `;`, ` / `): these are multi-value delimiters written by
 *    taggers. Effectively never inside a name. Split always.
 *  - **Featuring keywords** (`feat.`, `ft.`, `presents`, `vs.` …): also
 *    effectively never inside a name. Split always, and everything to the right
 *    is a featured list, where commas *are* safe to split on.
 *  - **Ambiguous** (`,`, `&`, `and`, `+`): split only when something corroborates
 *    it — see [parse]'s `isKnownArtist`.
 *
 * A protected list guards the classic offenders even before there is any
 * corroborating data to consult.
 */
object ArtistCreditParser {

    /**
     * Multi-value delimiters written by tagging software. ID3v2.4 uses NUL,
     * most Windows taggers use `;`, and Picard can use a spaced slash.
     *
     * Note the spaces around the slash: "AC/DC" has none, and must survive.
     */
    private val STRUCTURAL_DELIMITERS = listOf("\u0000", ";", " / ")

    /**
     * Words that introduce a featured artist. Matched case-insensitively and
     * only when surrounded by whitespace, so "Ftisland" or "Weather Report"
     * cannot trigger one.
     */
    private val FEATURE_KEYWORDS = listOf(
        "featuring", "feat.", "feat", "ft.", "ft", "f/",
        "presents", "pres.",
        "versus", "vs.", "vs",
        "w/"
    )

    /**
     * Acts whose names contain an ambiguous delimiter.
     *
     * Not a complete list of such bands — that list does not exist — but a floor
     * under the most common cases so a fresh library with no corroborating data
     * still behaves. The corroboration rule in [parse] is what generalises.
     */
    private val PROTECTED_NAMES: Set<String> = setOf(
        "earth, wind & fire",
        "simon & garfunkel",
        "crosby, stills, nash & young",
        "crosby, stills & nash",
        "blood, sweat & tears",
        "emerson, lake & palmer",
        "tyler, the creator",
        "florence + the machine",
        "hall & oates",
        "daryl hall & john oates",
        "sam & dave",
        "above & beyond",
        "iron & wine",
        "angus & julia stone",
        "peter, paul and mary",
        "kool & the gang",
        "sly & the family stone",
        "bob marley & the wailers",
        "nick cave & the bad seeds",
        "huey lewis and the news",
        "derek & the dominos",
        "booker t. & the m.g.'s",
        "gladys knight & the pips",
        "martha & the vandellas",
        "katrina & the waves",
        "echo & the bunnymen",
        "siouxsie & the banshees",
        "the mamas & the papas",
        "captain & tennille",
        "ashford & simpson",
        "brooks & dunn",
        "big brother & the holding company",
        "mumford & sons",
        "of monsters and men",
        "now, now",
        "me first and the gimme gimmes",
        "the joy formidable",
        "belle & sebastian",
        "ike & tina turner",
        "sonny & cher",
        "tegan and sara",
        "hootie & the blowfish",
        "diana ross & the supremes",
        "prince & the revolution",
        "tom petty & the heartbreakers",
        "elvis costello & the attractions",
        "george thorogood & the destroyers",
        "joan jett & the blackhearts",
        "kc & the sunshine band",
        "smokey robinson & the miracles"
    )

    private val AMBIGUOUS_SPLIT = Regex("""\s*(?:,|&|\+|\band\b)\s*""", RegexOption.IGNORE_CASE)
    private val FEATURED_LIST_SPLIT = Regex("""\s*(?:,|&|\+|\band\b)\s*""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Parses a raw artist tag.
     *
     * @param isKnownArtist asked whether a candidate name already exists in the
     *   library as an artist in its own right. This is what makes splitting on a
     *   comma safe: "24kGoldn, DaBaby" splits because 24kGoldn is already an
     *   artist here, while "Earth, Wind & Fire" does not because "Earth" is not.
     *   Defaults to knowing nothing, which is the conservative answer.
     *
     * @return null when there is no usable name at all
     */
    fun parse(
        raw: String?,
        isKnownArtist: (String) -> Boolean = { false },
        isRecurringComponent: (String) -> Boolean = { false }
    ): ArtistCredit? {
        val cleaned = clean(raw) ?: return null

        // A name that already exists verbatim is never taken apart. This is the
        // single most important guard: once "Earth, Wind & Fire" is in the
        // library, nothing may shred it later.
        if (isProtected(cleaned) || isKnownArtist(cleaned)) {
            return ArtistCredit(displayCredit = cleaned, primary = cleaned)
        }

        // 1. Structural delimiters: unambiguous, split first.
        val chunks = splitStructural(cleaned)
        val head = chunks.first()
        val structuralExtras = chunks.drop(1)

        // 2. Featuring keywords inside the leading chunk.
        val (primaryPart, featuredPart) = splitOnFeatureKeyword(head)

        // 3. The primary side only splits on an ambiguous delimiter with backing.
        val primaryNames = splitAmbiguousIfCorroborated(primaryPart, isKnownArtist, isRecurringComponent)

        // 4. After a "feat.", a comma really is a list separator.
        val featuredNames = buildList {
            featuredPart?.let { addAll(splitFeaturedList(it, isKnownArtist)) }
            structuralExtras.forEach { chunk ->
                val (chunkPrimary, chunkFeatured) = splitOnFeatureKeyword(chunk)
                addAll(splitAmbiguousIfCorroborated(chunkPrimary, isKnownArtist, isRecurringComponent))
                chunkFeatured?.let { addAll(splitFeaturedList(it, isKnownArtist)) }
            }
        }

        val primary = primaryNames.firstOrNull() ?: return null
        val rest = (primaryNames.drop(1) + featuredNames)
            .filter { it.isNotBlank() }
            .distinctBy { normalizeArtistName(it) }
            .filterNot { normalizeArtistName(it) == normalizeArtistName(primary) }

        return ArtistCredit(displayCredit = cleaned, primary = primary, featured = rest)
    }

    private fun isProtected(name: String): Boolean =
        normalizeArtistName(name) in PROTECTED_NAMES

    /** Trims, collapses whitespace and normalises exotic punctuation. */
    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val unified = raw
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('–', '-')
            .replace('—', '-')
        return WHITESPACE.replace(unified, " ").trim().takeIf { it.isNotBlank() }
    }

    private fun splitStructural(value: String): List<String> {
        var parts = listOf(value)
        for (delimiter in STRUCTURAL_DELIMITERS) {
            parts = parts.flatMap { it.split(delimiter) }
        }
        return parts.mapNotNull { clean(it) }.ifEmpty { listOf(value) }
    }

    /**
     * Splits a chunk at the first featuring keyword.
     *
     * @return the part before the keyword, and the part after it or null
     */
    private fun splitOnFeatureKeyword(chunk: String): Pair<String, String?> {
        for (keyword in FEATURE_KEYWORDS) {
            // Keyword must stand alone: leading whitespace, and either trailing
            // whitespace or a character that cannot continue a word. Without
            // this, "Ftisland" and "Presenting" would both trigger.
            val pattern = Regex(
                """\s+${Regex.escape(keyword)}(?=\s)""",
                RegexOption.IGNORE_CASE
            )
            val match = pattern.find(chunk) ?: continue
            val before = clean(chunk.substring(0, match.range.first))
            val after = clean(chunk.substring(match.range.last + 1))
            if (before != null) return before to after
        }
        return chunk to null
    }

    /**
     * Splits on comma/ampersand only when the pieces look like real artists.
     *
     * The test is deliberately asymmetric: the *first* piece must already be a
     * known artist. A collaboration is nearly always led by someone the library
     * already has, whereas the first word of a band name ("Earth", "Crosby",
     * "Tyler") almost never stands alone as an artist.
     */
    private fun splitAmbiguousIfCorroborated(
        value: String,
        isKnownArtist: (String) -> Boolean,
        isRecurringComponent: (String) -> Boolean = { false }
    ): List<String> {
        if (isProtected(value) || isKnownArtist(value)) return listOf(value)

        val parts = AMBIGUOUS_SPLIT.split(value).mapNotNull { clean(it) }
        if (parts.size < 2) return listOf(value)

        // Any piece that is itself a protected name means the split cut through
        // a band name — "Sara" out of "Tegan and Sara", say.
        if (parts.any { isProtected(it) }) return listOf(value)

        val leadIsKnown = isKnownArtist(parts.first())

        // Recurrence corroborates from *any* position, not just the lead.
        //
        // A soundtrack is the case the lead-only rule cannot reach: every
        // performer appears solely inside a composite credit, so nobody is ever
        // standalone and nothing ever splits. But a token shared across several
        // differently-named credits — "Sinners Movie" turning up in eight of
        // them — is strong evidence those strings are lists rather than names,
        // and the shared token is usually the *last* one, not the first.
        val recurs = parts.any { isRecurringComponent(it) }

        return if (leadIsKnown || recurs) parts else listOf(value)
    }

    /**
     * The pieces a credit *would* split into, ignoring corroboration entirely.
     *
     * Only for frequency analysis: counting how often a component recurs across
     * a whole library is what supplies the corroboration in the first place, so
     * it cannot itself require any. Returns nothing for a protected name, which
     * must never be taken apart even hypothetically.
     */
    fun candidateComponents(raw: String?): List<String> {
        val cleaned = clean(raw) ?: return emptyList()
        if (isProtected(cleaned)) return emptyList()
        val parts = splitStructural(cleaned).flatMap { chunk ->
            val (primaryPart, featuredPart) = splitOnFeatureKeyword(chunk)
            AMBIGUOUS_SPLIT.split(primaryPart).toList() +
                (featuredPart?.let { FEATURED_LIST_SPLIT.split(it).toList() } ?: emptyList())
        }
        val cleanedParts = parts.mapNotNull { clean(it) }
        if (cleanedParts.size < 2) return emptyList()
        // A split that cuts through a protected name is not evidence of anything.
        if (cleanedParts.any { isProtected(it) }) return emptyList()
        return cleanedParts
    }

    /**
     * Splits the text after a "feat." into names.
     *
     * Commas are safe here: a featured credit is a list by construction. The
     * protected check still applies for the rare "feat. Earth, Wind & Fire".
     */
    private fun splitFeaturedList(
        value: String,
        isKnownArtist: (String) -> Boolean
    ): List<String> {
        if (isProtected(value) || isKnownArtist(value)) return listOf(value)
        val parts = FEATURED_LIST_SPLIT.split(value).mapNotNull { clean(it) }
        return if (parts.isEmpty()) listOf(value) else parts
    }
}

/**
 * Identity key for an artist name.
 *
 * Two spellings that normalise to the same key are the same artist, so this
 * decides what gets merged. It folds case, strips diacritics and collapses
 * whitespace, which merges "Beyoncé"/"Beyonce" and "Sinéad"/"Sinead" — spelling
 * variants that otherwise produce a duplicate row each.
 *
 * It deliberately does *not* strip a leading "The". "The The", "The Band" and
 * "The Who" all argue against it, and merging "The Beatles" with "Beatles" is
 * not worth the risk of collapsing distinct acts.
 */
fun normalizeArtistName(name: String): String {
    val cleaned = ArtistCreditParser.clean(name) ?: return ""
    val decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFKD)
    val withoutMarks = decomposed.replace(Regex("""\p{Mn}+"""), "")
    return withoutMarks.lowercase(Locale.ROOT).trim()
}

package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A looked-up portrait for an artist, or a record that there isn't one.
 *
 * A row with a null [imageUrl] is a *negative* result and is just as important
 * as a positive one: most of a real library is artists Wikidata has never heard
 * of, and without remembering the misses every launch would re-query all of
 * them against a rate-limited API forever.
 */
@Entity(
    tableName = "artist_images",
    indices = [Index(value = ["fetchedAt"])]
)
data class ArtistImageEntity(
    @PrimaryKey val artistId: Long,

    /** Resolved image URL, or null when the lookup found nothing. */
    val imageUrl: String? = null,

    /** Which pipeline produced this — see `ArtistImageSource`. */
    val source: String,

    /** MusicBrainz artist id, kept so a retry can skip the search step. */
    val musicBrainzId: String? = null,

    /** Wikidata item id, for the same reason. */
    val wikidataId: String? = null,

    /**
     * Wikidata's one-line description of the artist.
     *
     * Stored here rather than in a table of its own because it comes from the
     * same entity, on the same request, with the same coverage and the same
     * retry lifecycle as the portrait. This row is really "what Wikidata knows
     * about this artist", and the name is now a little narrower than the truth.
     */
    val description: String? = null,

    /**
     * The English Wikipedia article title, when the Wikidata item links to one.
     *
     * Captured on the same `wbgetentities` request as the portrait and the
     * description, so knowing an artist has an article costs nothing extra.
     */
    val wikipediaTitle: String? = null,

    /**
     * The article's lead section, fetched on demand the first time someone opens
     * Info for this artist rather than crawled for the whole library. Text is
     * CC BY-SA, so anything showing it has to attribute and link back — see
     * [wikipediaUrl].
     */
    val wikipediaExtract: String? = null,
    val wikipediaUrl: String? = null,

    val fetchedAt: Long,

    /** How many times we have looked. Caps pointless retries on obscure artists. */
    val attempts: Int = 1
)

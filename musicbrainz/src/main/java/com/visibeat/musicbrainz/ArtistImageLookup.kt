package com.visibeat.musicbrainz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Outcome of looking for one artist's portrait. */
sealed interface ArtistImageResult {
    data class Found(
        val imageUrl: String,
        val musicBrainzId: String?,
        val wikidataId: String?,
        val source: String,
        /** Wikidata's one-line description, e.g. "American country music singer". */
        val description: String? = null,
        /** English Wikipedia article title, if the item links to one. */
        val wikipediaTitle: String? = null
    ) : ArtistImageResult

    /** Looked properly and there is genuinely nothing. Worth remembering. */
    data class NotFound(
        val musicBrainzId: String?,
        val wikidataId: String?,
        /**
         * Plenty of artists have a Wikidata entry and no free portrait on it.
         * The description is still worth keeping when that happens.
         */
        val description: String? = null,
        val wikipediaTitle: String? = null
    ) : ArtistImageResult

    /** Network or parse failure. Not the artist's fault — do not count it against them. */
    data class Failed(val reason: String) : ArtistImageResult
}

/**
 * An article's lead section and where it came from.
 *
 * [articleUrl] is required, not optional: Wikipedia text is CC BY-SA, so
 * anything displaying [extract] has to attribute it and link back.
 */
data class WikipediaSummary(
    val extract: String,
    val articleUrl: String
)

/**
 * Finds artist portraits through MusicBrainz and Wikidata.
 *
 * ### Why this route
 *
 * Last.fm used to be the obvious source and no longer is — its API returns a
 * placeholder star for artist images. Of what is left, this is the only path
 * that needs no API key, no account, and no shipped client secret, and whose
 * images come with clear reuse terms: everything on Wikimedia Commons is freely
 * licensed. Deezer and Spotify have far better coverage, but one has terms that
 * tie image use to their own content and the other needs OAuth credentials
 * embedded in the app.
 *
 * ### The chain
 *
 * 1. MusicBrainz artist search resolves a name to an MBID. Search cannot return
 *    relationships, so this step exists purely to get the id.
 * 2. Wikidata is queried by that MBID via `haswbstatement:P434`, which finds the
 *    item in one call — cheaper than a second, rate-limited MusicBrainz lookup
 *    for the artist's Wikidata relation.
 * 3. The item's P18 claim holds a bare Commons filename, which becomes a URL
 *    through `Special:FilePath`.
 *
 * ### Coverage, honestly
 *
 * Wikidata has portraits for well-known artists and very few for everyone else.
 * A typical personal library will come back with faces for a minority of its
 * artists, which is exactly why the album-art fallback matters more than this
 * lookup does.
 */
class ArtistImageLookup(
    private val musicBrainz: MusicBrainzClient = MusicBrainzClient(),
    private val userAgent: String = DEFAULT_USER_AGENT
) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param knownMusicBrainzId skips the search step on a retry
     */
    suspend fun findPortrait(
        artistName: String,
        knownMusicBrainzId: String? = null
    ): ArtistImageResult {
        if (artistName.isBlank()) return ArtistImageResult.Failed("blank artist name")

        return try {
            val mbid = knownMusicBrainzId ?: searchArtistMbid(artistName)
                ?: return ArtistImageResult.NotFound(musicBrainzId = null, wikidataId = null)

            val qid = wikidataIdForMusicBrainzId(mbid)
                ?: return ArtistImageResult.NotFound(musicBrainzId = mbid, wikidataId = null)

            val item = itemDetails(qid)

            val url = item.commonsFileName?.let { ArtistImageUrls.commonsImageUrl(it) }
                ?: return ArtistImageResult.NotFound(
                    musicBrainzId = mbid,
                    wikidataId = qid,
                    description = item.description,
                    wikipediaTitle = item.wikipediaTitle
                )

            ArtistImageResult.Found(
                imageUrl = url,
                musicBrainzId = mbid,
                wikidataId = qid,
                source = ArtistImageSource.WIKIDATA,
                description = item.description,
                wikipediaTitle = item.wikipediaTitle
            )
        } catch (e: Exception) {
            ArtistImageResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * The article extract for an artist, resolving only what is missing.
     *
     * With a known title this is a single request. Without one it walks the same
     * chain the portrait lookup does — name to MBID to Wikidata item to sitelink
     * — which is why the title is captured up front by the image worker.
     */
    suspend fun findBio(
        artistName: String,
        knownWikipediaTitle: String? = null,
        knownMusicBrainzId: String? = null
    ): WikipediaSummary? = try {
        val title = knownWikipediaTitle
            ?: run {
                val mbid = knownMusicBrainzId ?: searchArtistMbid(artistName)
                val qid = mbid?.let { wikidataIdForMusicBrainzId(it) }
                qid?.let { itemDetails(it).wikipediaTitle }
            }
        title?.let { fetchWikipediaSummary(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Best-matching MBID for a name.
     *
     * MusicBrainz scores its own results; anything below [MIN_SEARCH_SCORE] is
     * usually a different act with a similar name, and a wrong face is worse
     * than no face.
     */
    private suspend fun searchArtistMbid(artistName: String): String? {
        val escaped = artistName.replace("\"", "")
        val artists = musicBrainz.searchArtist("artist:\"$escaped\"")
        val best = artists.firstOrNull() ?: return null
        return if (best.score >= MIN_SEARCH_SCORE) best.id else null
    }

    /** Finds the Wikidata item carrying this MusicBrainz artist id (P434). */
    private suspend fun wikidataIdForMusicBrainzId(mbid: String): String? {
        val url = "https://www.wikidata.org/w/api.php" +
            "?action=query&list=search&format=json&srlimit=1" +
            "&srsearch=haswbstatement:P434=$mbid"

        val body = getJson(url) ?: return null
        val results = body.optJSONObject("query")?.optJSONArray("search") ?: return null
        if (results.length() == 0) return null
        val title = results.optJSONObject(0)?.optString("title").orEmpty()
        return ArtistImageUrls.wikidataIdFromUrl("/$title")
    }

    private data class WikidataItem(
        val commonsFileName: String?,
        val description: String?,
        val wikipediaTitle: String?
    )

    /**
     * The portrait filename and the description, in one request.
     *
     * This used to be `wbgetclaims` for P18 alone. `wbgetentities` returns both
     * the claims and the descriptions, so the blurb costs no extra call against
     * an API we are already deliberately gentle with.
     */
    private suspend fun itemDetails(qid: String): WikidataItem {
        val url = "https://www.wikidata.org/w/api.php" +
            "?action=wbgetentities&format=json&ids=$qid" +
            "&props=claims%7Cdescriptions%7Csitelinks&languages=en&sitefilter=enwiki"

        val body = getJson(url) ?: return WikidataItem(null, null, null)
        val entity = body.optJSONObject("entities")?.optJSONObject(qid)
            ?: return WikidataItem(null, null, null)

        // claims -> P18 -> [0] -> mainsnak -> datavalue -> value, a bare filename.
        val fileName = entity.optJSONObject("claims")?.optJSONArray("P18")
            ?.takeIf { it.length() > 0 }
            ?.optJSONObject(0)
            ?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optString("value")
            ?.takeIf { it.isNotBlank() }

        val description = entity.optJSONObject("descriptions")
            ?.optJSONObject("en")
            ?.optString("value")
            ?.takeIf { it.isNotBlank() }

        // The sitelink is the article title. Asking for it costs nothing here
        // and saves a whole resolution chain when someone opens Info later.
        val wikipediaTitle = entity.optJSONObject("sitelinks")
            ?.optJSONObject("enwiki")
            ?.optString("title")
            ?.takeIf { it.isNotBlank() }

        return WikidataItem(fileName, description, wikipediaTitle)
    }

    /**
     * The lead section of an English Wikipedia article.
     *
     * The REST summary endpoint returns plain text and a canonical URL, so there
     * is no HTML to strip. Called on demand for one artist rather than crawled
     * across a library — this is a donated API and most artists' bios are never
     * opened.
     *
     * @return the extract and the article URL, or null if there is no article
     */
    suspend fun fetchWikipediaSummary(title: String): WikipediaSummary? {
        if (title.isBlank()) return null
        val encoded = java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
            .replace("+", "_")
        val body = getJson("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
            ?: return null

        val extract = body.optString("extract").takeIf { it.isNotBlank() } ?: return null
        val pageUrl = body.optJSONObject("content_urls")
            ?.optJSONObject("desktop")
            ?.optString("page")
            ?.takeIf { it.isNotBlank() }
            ?: "https://en.wikipedia.org/wiki/$encoded"

        return WikipediaSummary(extract = extract, articleUrl = pageUrl)
    }

    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // Wikimedia asks for a descriptive User-Agent and throttles requests
            // that arrive without one.
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val text = response.body?.string() ?: return@withContext null
            runCatching { JSONObject(text) }.getOrNull()
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "VisiBeat/1.0 (https://github.com/visibeat)"

        /** MusicBrainz search score below which a name match is not trustworthy. */
        const val MIN_SEARCH_SCORE = 80
    }
}

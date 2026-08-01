package com.visibeat.musicbrainz

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for MusicBrainz API.
 * Rate limit: 1 request/second (enforced by RateLimitedClient)
 */
interface MusicBrainzApi {

    /**
     * Search for releases by artist and album title.
     */
    @Headers("Accept: application/json")
    @GET("release/")
    suspend fun searchRelease(
        @Query("query") query: String,
        @Query("fmt") format: String = "json",
        @Query("limit") limit: Int = 5
    ): ReleaseSearchResponse

    /**
     * Search for an artist by name, to resolve a name into an MBID.
     *
     * Search cannot return relationships, so a portrait lookup needs this first
     * and then goes to Wikidata by MBID.
     */
    @Headers("Accept: application/json")
    @GET("artist/")
    suspend fun searchArtist(
        @Query("query") query: String,
        @Query("fmt") format: String = "json",
        @Query("limit") limit: Int = 5
    ): ArtistSearchResponse

    /**
     * Genres for a release, by MBID.
     *
     * A lookup rather than a search, so it is exact and cheap — one request
     * covers every track on the album. `inc=genres` returns MusicBrainz's
     * curated vocabulary; `tags` would return the raw folksonomy, which is
     * larger and much noisier ("awesome", "seen live", "female vocalist").
     *
     * Release-group genres are requested alongside because they are where
     * MusicBrainz editors usually put them: a specific pressing often carries
     * none while the work it belongs to is fully tagged.
     */
    @Headers("Accept: application/json")
    @GET("release/{mbid}")
    suspend fun lookupReleaseGenres(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "genres+release-groups",
        @Query("fmt") format: String = "json"
    ): ReleaseGenreResponse

    /**
     * Genres for an artist, by MBID.
     *
     * The fallback that matters. Sampling a real library: release-level genres
     * were present for 1 release in 8, release-group for 6 in 8, and artist for
     * 8 in 8. MusicBrainz editors tag artists far more thoroughly than they tag
     * individual pressings, so an artist genre is the difference between an
     * album having something and having nothing.
     *
     * Weaker evidence than a release genre — an artist who changed style across
     * a career gets one blurred answer — which is why it is only consulted when
     * the release and its group have said nothing.
     */
    @Headers("Accept: application/json")
    @GET("artist/{mbid}")
    suspend fun lookupArtistGenres(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "genres",
        @Query("fmt") format: String = "json"
    ): ArtistGenreResponse
}

/** An artist lookup, trimmed to the genre fields. */
data class ArtistGenreResponse(
    val id: String = "",
    val genres: List<MusicBrainzGenre> = emptyList()
) {
    fun bestGenres(): List<String> = genres
        .sortedByDescending { it.count }
        .map { it.name }
        .filter { it.isNotBlank() }
}

/** A release lookup, trimmed to the genre fields. */
data class ReleaseGenreResponse(
    val id: String = "",
    val genres: List<MusicBrainzGenre> = emptyList(),
    @com.google.gson.annotations.SerializedName("release-group")
    val releaseGroup: ReleaseGroupGenres? = null
) {
    /**
     * Release genres, falling back to the release group's.
     *
     * Ordered by editor count so the caller can keep only what people agreed
     * on; a genre with a count of one is somebody's opinion, not a consensus.
     */
    fun bestGenres(minCount: Int = 1): List<String> {
        val chosen = if (genres.isNotEmpty()) genres else releaseGroup?.genres.orEmpty()
        return chosen.filter { it.count >= minCount }
            .sortedByDescending { it.count }
            .map { it.name }
            .filter { it.isNotBlank() }
    }
}

data class ReleaseGroupGenres(val genres: List<MusicBrainzGenre> = emptyList())

data class MusicBrainzGenre(val name: String = "", val count: Int = 0)

data class ArtistSearchResponse(
    val count: Int = 0,
    val artists: List<MusicBrainzArtist> = emptyList()
)

data class MusicBrainzArtist(
    val id: String,
    val name: String,
    val score: Int = 0,
    val disambiguation: String? = null,
    val type: String? = null
)

/**
 * Response from release search endpoint.
 */
data class ReleaseSearchResponse(
    val count: Int,
    val releases: List<MusicBrainzRelease>
)

/**
 * A release from MusicBrainz (simplified).
 */
data class MusicBrainzRelease(
    val id: String,
    val title: String,
    val date: String? = null,
    val country: String? = null,
    val status: String? = null,
    val score: Int = 0,
    /**
     * Album / Single / EP / Broadcast / Other.
     *
     * MusicBrainz has always returned this on the same search we make for
     * release dates; we simply never deserialized it, so `releases.releaseType`
     * sat on the literal "UNKNOWN" both ingest sites write and nothing could
     * tell an album from a single.
     */
    @SerializedName("release-group")
    val releaseGroup: MusicBrainzReleaseGroup? = null
) {
    /**
     * Parse date into components.
     * Formats: "2024-05-15", "2024-05", "2024"
     */
    fun parseDateComponents(): DateComponents? {
        if (date.isNullOrBlank()) return null
        val parts = date.split("-")
        return when (parts.size) {
            3 -> DateComponents(
                year = parts[0].toIntOrNull(),
                month = parts[1].toIntOrNull(),
                day = parts[2].toIntOrNull()
            )
            2 -> DateComponents(
                year = parts[0].toIntOrNull(),
                month = parts[1].toIntOrNull(),
                day = null
            )
            1 -> DateComponents(
                year = parts[0].toIntOrNull(),
                month = null,
                day = null
            )
            else -> null
        }
    }
}

/** The release group a release belongs to. Carries the type we want. */
data class MusicBrainzReleaseGroup(
    val id: String? = null,
    @SerializedName("primary-type")
    val primaryType: String? = null,
    @SerializedName("secondary-types")
    val secondaryTypes: List<String> = emptyList()
) {
    /**
     * The type to store, normalised.
     *
     * Secondary types win where present: MusicBrainz calls a greatest-hits
     * record a primary-type Album with a secondary-type Compilation, and for
     * shelving purposes it is a compilation.
     */
    val effectiveType: String?
        get() = secondaryTypes.firstOrNull()?.uppercase()
            ?: primaryType?.uppercase()
}

data class DateComponents(
    val year: Int?,
    val month: Int?,
    val day: Int?
) {
    val hasFullDate: Boolean get() = year != null && month != null && day != null
    val hasMonthDate: Boolean get() = year != null && month != null
}

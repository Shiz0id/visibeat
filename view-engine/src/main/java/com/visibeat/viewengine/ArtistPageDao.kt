package com.visibeat.viewengine

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The artist header: who they are, and what your library holds of theirs. */
@Immutable
data class ArtistHeaderRow(
    val artistId: Long,
    val artistName: String,
    val imageUrl: String?,
    /**
     * Wikidata's one-liner, e.g. "American country music singer".
     *
     * Where a streaming service shows a fan count. There is no local equivalent
     * of that number and no free source for it, so the header says something
     * true instead — this, plus counts from the library itself.
     */
    val description: String?,
    val trackCount: Int,
    val releaseCount: Int,
    /** Total plays across everything of theirs. Genuinely yours, unlike a fan count. */
    val playCount: Int,
    val fallbackArtPath: String?,
    val fallbackAlbumId: Long?
) {
    /**
     * The artist's album art, kept separate from [imageUrl] rather than merged
     * behind an elvis. The hero falls back to this when a portrait URL fails to
     * load, and it cannot do that if both tiers resolve to the same model.
     */
    val albumArtModel: Any? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resolveArtModel(fallbackArtPath, null, fallbackAlbumId)
    }
}

/** A release on the artist page, with the year and type the carousels group by. */
@Immutable
data class ArtistReleaseRow(
    val releaseId: Long,
    val title: String?,
    val dateEpochMs: Long?,
    /** ALBUM / SINGLE / EP / COMPILATION / UNKNOWN — see MIGRATION notes on releaseType. */
    val releaseType: String,
    val trackCount: Int,
    val artPath: String?,
    val mediaStoreAlbumId: Long?,
    /**
     * True when the artist made this record, false when they are only a guest on
     * it.
     *
     * The distinction the release list could not previously draw. A featured
     * credit is a real credit — `track_artist` is right to hold it — but it is
     * not authorship, and treating the two the same put Kendrick Lamar's *DAMN.*
     * in Rihanna's discography on the strength of one verse.
     */
    val isPrimaryArtist: Boolean = true
) {
    val artModel: Any? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resolveArtModel(artPath, releaseId, mediaStoreAlbumId)
    }
}

@Stable
@Dao
interface ArtistPageDao {

    @Query(
        """
        SELECT
            a.artistId AS artistId,
            a.displayName AS artistName,
            (SELECT ai.imageUrl FROM artist_images ai WHERE ai.artistId = a.artistId) AS imageUrl,
            (SELECT ai.description FROM artist_images ai WHERE ai.artistId = a.artistId) AS description,
            (SELECT COUNT(DISTINCT ta.trackId) FROM track_artist ta WHERE ta.artistId = a.artistId) AS trackCount,
            (SELECT COUNT(DISTINCT rt.releaseId) FROM resolved_tracks rt
                JOIN track_artist ta2 ON ta2.trackId = rt.trackId
                WHERE ta2.artistId = a.artistId AND rt.releaseId IS NOT NULL) AS releaseCount,
            (SELECT COALESCE(SUM(ph.playCount), 0) FROM play_history ph
                JOIN track_artist ta3 ON ta3.trackId = ph.trackId
                WHERE ta3.artistId = a.artistId) AS playCount,
            -- Art from a record they made, falling back to a guest spot only if
            -- they have none of their own. See LibraryArtistRow.fallbackArtPath.
            (SELECT rt.artPath FROM resolved_tracks rt
                JOIN track_artist ta4 ON ta4.trackId = rt.trackId
                WHERE ta4.artistId = a.artistId AND rt.artPath IS NOT NULL
                ORDER BY (ta4.role IN ('PRIMARY', 'ALBUM_ARTIST')) DESC
                LIMIT 1) AS fallbackArtPath,
            (SELECT rt.mediaStoreAlbumId FROM resolved_tracks rt
                JOIN track_artist ta5 ON ta5.trackId = rt.trackId
                WHERE ta5.artistId = a.artistId AND rt.mediaStoreAlbumId IS NOT NULL
                ORDER BY (ta5.role IN ('PRIMARY', 'ALBUM_ARTIST')) DESC
                LIMIT 1) AS fallbackAlbumId
        FROM artists a
        WHERE a.artistId = :artistId
        LIMIT 1
        """
    )
    fun observeArtistHeader(artistId: Long): Flow<ArtistHeaderRow?>

    /**
     * The artist's most-played tracks.
     *
     * Ranked by *your* play count, which is the only version of "top tracks" a
     * local library can answer honestly. A track with no history sorts last
     * rather than being excluded, so the list is still full on a fresh install —
     * the screen relabels itself when nothing has been played at all, because a
     * ranking of zeroes is not a ranking.
     */
    @Query(
        """
        SELECT
            rt.trackId, rt.effectiveReleaseDateEpochMs, rt.effectiveTitle,
            rt.effectiveAlbumTitle, rt.effectiveArtistDisplay, rt.releaseId,
            rt.primaryArtistId, rt.mediaStoreAlbumId, rt.mediaStoreUri, rt.artPath
        FROM resolved_tracks rt
        JOIN track_artist ta ON ta.trackId = rt.trackId
        LEFT JOIN play_history ph ON ph.trackId = rt.trackId
        WHERE ta.artistId = :artistId
        GROUP BY rt.trackId
        ORDER BY COALESCE(MAX(ph.playCount), 0) DESC, rt.effectiveTitle COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun observeTopTracks(artistId: Long, limit: Int = 5): Flow<List<TimelineItemRow>>

    /** Every track by the artist, in release order. What Play and Shuffle use. */
    @Query(
        """
        SELECT DISTINCT
            rt.trackId, rt.effectiveReleaseDateEpochMs, rt.effectiveTitle,
            rt.effectiveAlbumTitle, rt.effectiveArtistDisplay, rt.releaseId,
            rt.primaryArtistId, rt.mediaStoreAlbumId, rt.mediaStoreUri, rt.artPath
        FROM resolved_tracks rt
        JOIN track_artist ta ON ta.trackId = rt.trackId
        WHERE ta.artistId = :artistId
        ORDER BY rt.effectiveReleaseDateEpochMs DESC, rt.discNumber, rt.trackNumber
        """
    )
    fun observeArtistTracks(artistId: Long): Flow<List<TimelineItemRow>>

    /**
     * The artist's releases, newest first, each flagged as theirs or a guest spot.
     *
     * Membership is still "any credit on any track", because dropping guest
     * appearances outright is not an option: measured on a real 1,143-track
     * library, 117 artists appear *only* as features — Drake, Nicki Minaj,
     * Future — and filtering would empty their pages rather than fix them. The
     * flag lets the caller shelve them separately instead.
     *
     * `releaseType` was the literal "UNKNOWN" for every row until MusicBrainz
     * enrichment started storing the real release-group type, so the caller has
     * to cope with releases that have not been enriched yet.
     */
    @Query(
        """
        SELECT
            r.releaseId AS releaseId,
            r.title AS title,
            r.primaryDateEpochMs AS dateEpochMs,
            r.releaseType AS releaseType,
            (SELECT COUNT(*) FROM resolved_tracks t WHERE t.releaseId = r.releaseId) AS trackCount,
            (SELECT t.artPath FROM resolved_tracks t
                WHERE t.releaseId = r.releaseId AND t.artPath IS NOT NULL LIMIT 1) AS artPath,
            (SELECT t.mediaStoreAlbumId FROM resolved_tracks t
                WHERE t.releaseId = r.releaseId AND t.mediaStoreAlbumId IS NOT NULL LIMIT 1) AS mediaStoreAlbumId,
            EXISTS (
                SELECT 1 FROM resolved_tracks t
                JOIN track_artist ta ON ta.trackId = t.trackId
                WHERE t.releaseId = r.releaseId
                  AND ta.artistId = :artistId
                  AND ta.role IN ('PRIMARY', 'ALBUM_ARTIST')
            ) AS isPrimaryArtist
        FROM releases r
        WHERE r.releaseId IN (
            SELECT DISTINCT rt.releaseId FROM resolved_tracks rt
            JOIN track_artist ta ON ta.trackId = rt.trackId
            WHERE ta.artistId = :artistId AND rt.releaseId IS NOT NULL
        )
        ORDER BY r.primaryDateEpochMs IS NULL, r.primaryDateEpochMs DESC, r.title COLLATE NOCASE
        """
    )
    fun observeArtistReleases(artistId: Long): Flow<List<ArtistReleaseRow>>
}

/**
 * Which carousel a release belongs in.
 *
 * MusicBrainz calls it Album / Single / EP / Broadcast / Other, and anything the
 * enrichment worker has not reached yet is still "UNKNOWN". Unknown releases are
 * shown as albums rather than hidden or given a section of their own: on a fresh
 * library that is every release, and an "Unknown" carousel holding the entire
 * discography would be worse than a slightly over-full Albums one.
 */
object ArtistReleaseGrouping {
    const val ALBUMS = "Albums"
    const val SINGLES_AND_EPS = "EP & Singles"

    /**
     * Records the artist is on but did not make.
     *
     * Deliberately one shelf rather than a guest Albums and a guest Singles: it
     * is a footnote to a discography, and splitting a footnote in two gives an
     * artist known for three features two headings with one card under each.
     */
    const val APPEARS_ON = "Appears On"

    /**
     * Which shelf a release belongs on.
     *
     * Guest status is checked before type, because "is this theirs" outranks
     * "what kind of record is it" — a listener scanning a discography wants the
     * artist's own work first and the cameos gathered at the end, not a guest
     * verse filed among the albums by virtue of being on one.
     */
    fun sectionFor(releaseType: String?, isPrimaryArtist: Boolean = true): String =
        when {
            !isPrimaryArtist -> APPEARS_ON
            releaseType?.uppercase() in setOf("SINGLE", "EP") -> SINGLES_AND_EPS
            else -> ALBUMS
        }
}

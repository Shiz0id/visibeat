package com.visibeat.viewengine

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A track as an album page needs it: where it sits on the record, and what it is.
 *
 * Distinct from [TimelineItemRow] because that type is shaped for date views and
 * carries no position. [toItemRow] converts for playback, which speaks in
 * [TimelineItemRow] throughout.
 */
@Immutable
data class AlbumTrackRow(
    val trackId: Long,
    val effectiveReleaseDateEpochMs: Long?,
    val effectiveTitle: String?,
    val effectiveAlbumTitle: String?,
    val effectiveArtistDisplay: String?,
    val releaseId: Long?,
    val primaryArtistId: Long?,
    val mediaStoreAlbumId: Long?,
    val mediaStoreUri: String?,
    val artPath: String?,
    val discNumber: Int?,
    val trackNumber: Int?,
    val mimeType: String?
) {
    val format: AudioFormat? get() = AudioFormat.fromMimeType(mimeType)

    fun toItemRow(): TimelineItemRow = TimelineItemRow(
        trackId = trackId,
        effectiveReleaseDateEpochMs = effectiveReleaseDateEpochMs,
        effectiveTitle = effectiveTitle,
        effectiveAlbumTitle = effectiveAlbumTitle,
        effectiveArtistDisplay = effectiveArtistDisplay,
        releaseId = releaseId,
        primaryArtistId = primaryArtistId,
        mediaStoreAlbumId = mediaStoreAlbumId,
        mediaStoreUri = mediaStoreUri,
        artPath = artPath
    )
}

/** The album header, from the resolved release cache plus what its tracks say. */
@Immutable
data class AlbumHeaderRow(
    val releaseId: Long,
    val title: String?,
    val artistDisplay: String?,
    val dateEpochMs: Long?,
    val releaseDateQuality: String,
    val trackCount: Int,
    val artPath: String?,
    val mediaStoreAlbumId: Long?,
    val primaryArtistId: Long?
) {
    val artModel: Any? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resolveArtModel(artPath, releaseId, mediaStoreAlbumId)
    }
}

@Stable
@Dao
interface AlbumDao {

    /**
     * The album's tracks, in album order.
     *
     * Disc then track number, which is the first time either has ever been read
     * — both have been written to `track_release` since the schema was created
     * and surfaced onto `resolved_tracks` by the 8->9 migration. Before this, an
     * album could only be listed in ingest order, which merely happened to look
     * right when a folder was scanned front to back.
     *
     * Untagged files sort last rather than first: `NULLS LAST` is not available
     * on the SQLite versions this app supports, so the ordering leans on
     * `column IS NULL` sorting 0 before 1. Title is the final tiebreak so the
     * result is stable for a release with no numbering at all.
     */
    @Query(
        """
        SELECT
            trackId, effectiveReleaseDateEpochMs, effectiveTitle, effectiveAlbumTitle,
            effectiveArtistDisplay, releaseId, primaryArtistId, mediaStoreAlbumId,
            mediaStoreUri, artPath, discNumber, trackNumber, mimeType
        FROM resolved_tracks
        WHERE releaseId = :releaseId
        ORDER BY
            discNumber IS NULL, discNumber,
            trackNumber IS NULL, trackNumber,
            effectiveTitle COLLATE NOCASE,
            trackId
        """
    )
    fun observeAlbumTracks(releaseId: Long): Flow<List<AlbumTrackRow>>

    /**
     * The header.
     *
     * `resolved_releases` has been written at ingest since the cache existed and
     * read by nothing at all until now. Artwork is not in it, so that comes from
     * any track on the release, the same way every other album tile resolves art.
     */
    @Query(
        """
        SELECT
            rr.releaseId AS releaseId,
            rr.effectiveTitle AS title,
            rr.effectiveArtistDisplay AS artistDisplay,
            rr.effectiveDateEpochMs AS dateEpochMs,
            rr.releaseDateQuality AS releaseDateQuality,
            (SELECT COUNT(*) FROM resolved_tracks t WHERE t.releaseId = rr.releaseId) AS trackCount,
            (SELECT t.artPath FROM resolved_tracks t
                WHERE t.releaseId = rr.releaseId AND t.artPath IS NOT NULL LIMIT 1) AS artPath,
            (SELECT t.mediaStoreAlbumId FROM resolved_tracks t
                WHERE t.releaseId = rr.releaseId AND t.mediaStoreAlbumId IS NOT NULL LIMIT 1) AS mediaStoreAlbumId,
            (SELECT t.primaryArtistId FROM resolved_tracks t
                WHERE t.releaseId = rr.releaseId AND t.primaryArtistId IS NOT NULL LIMIT 1) AS primaryArtistId
        FROM resolved_releases rr
        WHERE rr.releaseId = :releaseId
        LIMIT 1
        """
    )
    fun observeAlbumHeader(releaseId: Long): Flow<AlbumHeaderRow?>
}

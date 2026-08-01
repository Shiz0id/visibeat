package com.visibeat.viewengine

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.withTransaction

/** What a duplicate-track merge changed. */
data class TrackCleanupReport(
    val tracksBefore: Int,
    val groupsMerged: Int,
    val tracksRemoved: Int,
    val playsPreserved: Int,
    val tracksAfter: Int
) {
    val changed: Boolean get() = tracksRemoved > 0
}

/** One row of a duplicate group. */
data class DuplicateTrackRow(
    val trackId: Long,
    val fileName: String,
    val sizeBytes: Long
)

@Dao
interface TrackMaintenanceDao {

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    /**
     * Every track that shares a filename *and* an exact byte size with another.
     *
     * The same key the ingest fallback uses to recognise a file it has already
     * seen. Two genuinely different recordings agreeing on both is vanishingly
     * unlikely; agreeing on just the name is routine, which is why size is not
     * optional.
     */
    @Query(
        """
        SELECT t.trackId AS trackId, t.fileName AS fileName, t.sizeBytes AS sizeBytes
        FROM tracks t
        JOIN (
            SELECT fileName, sizeBytes
            FROM tracks
            WHERE fileName IS NOT NULL AND sizeBytes IS NOT NULL
            GROUP BY fileName, sizeBytes
            HAVING COUNT(*) > 1
        ) d ON d.fileName = t.fileName AND d.sizeBytes = t.sizeBytes
        ORDER BY t.fileName, t.sizeBytes, t.trackId
        """
    )
    suspend fun duplicateGroups(): List<DuplicateTrackRow>

    // ── Relationships: keyed on trackId, so repointing can collide ─────────

    @Query("UPDATE OR REPLACE track_artist SET trackId = :to WHERE trackId = :from")
    suspend fun repointArtists(from: Long, to: Long)

    @Query("UPDATE OR REPLACE track_release SET trackId = :to WHERE trackId = :from")
    suspend fun repointReleases(from: Long, to: Long)

    @Query("UPDATE OR REPLACE track_genre SET trackId = :to WHERE trackId = :from")
    suspend fun repointGenres(from: Long, to: Long)

    /**
     * Identities are unique on (source, sourceKey), which repointing does not
     * touch — so a merged track keeps *both* its URIs as identities. That is
     * correct: the file really is reachable by both, and it is what lets a later
     * scan from either source find it.
     */
    @Query("UPDATE OR REPLACE track_identities SET trackId = :to WHERE trackId = :from")
    suspend fun repointIdentities(from: Long, to: Long)

    @Query(
        "UPDATE OR REPLACE metadata_observations SET subjectId = :to " +
            "WHERE subjectType = 'TRACK' AND subjectId = :from"
    )
    suspend fun repointObservations(from: Long, to: Long)

    @Query(
        "UPDATE OR REPLACE dismissed_suggestions SET subjectId = :to " +
            "WHERE subjectType = 'TRACK' AND subjectId = :from"
    )
    suspend fun repointDismissals(from: Long, to: Long)

    // ── Playlists: keep the survivor's position ───────────────────────────

    /**
     * Drops the loser's membership only where the survivor is already in that
     * playlist.
     *
     * `UPDATE OR REPLACE` would do the opposite of what is wanted here: SQLite
     * deletes the *conflicting existing* row and applies the update, so the
     * survivor's entry — and its position — would be replaced by the loser's.
     * A playlist would silently reorder itself.
     */
    @Query(
        """
        DELETE FROM playlist_tracks
        WHERE trackId = :from
          AND playlistId IN (SELECT playlistId FROM playlist_tracks WHERE trackId = :to)
        """
    )
    suspend fun dropRedundantPlaylistEntries(from: Long, to: Long)

    @Query("UPDATE playlist_tracks SET trackId = :to WHERE trackId = :from")
    suspend fun repointPlaylistEntries(from: Long, to: Long)

    // ── Play history: summed, not replaced ────────────────────────────────

    @Query(
        """
        UPDATE play_history SET
            playCount = playCount + COALESCE((SELECT playCount FROM play_history WHERE trackId = :from), 0),
            lastPlayedAt = MAX(lastPlayedAt, COALESCE((SELECT lastPlayedAt FROM play_history WHERE trackId = :from), 0))
        WHERE trackId = :to
        """
    )
    suspend fun foldPlayHistoryInto(from: Long, to: Long)

    /** Only fires when the survivor had never been played; otherwise a no-op. */
    @Query(
        """
        UPDATE play_history SET trackId = :to
        WHERE trackId = :from AND NOT EXISTS (SELECT 1 FROM play_history WHERE trackId = :to)
        """
    )
    suspend fun movePlayHistory(from: Long, to: Long)

    @Query("DELETE FROM play_history WHERE trackId = :from")
    suspend fun deletePlayHistory(from: Long)

    // ── Likes: union, keeping the earliest ────────────────────────────────

    @Query(
        """
        UPDATE liked_tracks SET
            likedAt = MIN(likedAt, COALESCE((SELECT likedAt FROM liked_tracks WHERE trackId = :from), likedAt))
        WHERE trackId = :to
        """
    )
    suspend fun foldLikeInto(from: Long, to: Long)

    @Query(
        """
        UPDATE liked_tracks SET trackId = :to
        WHERE trackId = :from AND NOT EXISTS (SELECT 1 FROM liked_tracks WHERE trackId = :to)
        """
    )
    suspend fun moveLike(from: Long, to: Long)

    @Query("DELETE FROM liked_tracks WHERE trackId = :from")
    suspend fun deleteLike(from: Long)

    // ── Caches and the row itself ─────────────────────────────────────────

    @Query("DELETE FROM resolved_tracks WHERE trackId = :trackId")
    suspend fun deleteResolved(trackId: Long)

    @Query("DELETE FROM tracks WHERE trackId = :trackId")
    suspend fun deleteTrack(trackId: Long)

    @Query("SELECT COALESCE(SUM(playCount), 0) FROM play_history")
    suspend fun totalPlays(): Int

    /**
     * Folds [from] into [to] across every table that references a track.
     *
     * Order matters twice over: the play-history fold has to read the loser's
     * row before anything deletes it, and the redundant playlist entries have to
     * go before the rest are repointed.
     */
    @Transaction
    suspend fun mergeInto(from: Long, to: Long) {
        repointArtists(from, to)
        repointReleases(from, to)
        repointGenres(from, to)
        repointIdentities(from, to)
        repointObservations(from, to)
        repointDismissals(from, to)

        dropRedundantPlaylistEntries(from, to)
        repointPlaylistEntries(from, to)

        foldPlayHistoryInto(from, to)
        movePlayHistory(from, to)
        deletePlayHistory(from)

        foldLikeInto(from, to)
        moveLike(from, to)
        deleteLike(from)

        // A cache, rebuilt on the next resolve. The survivor keeps its own row.
        deleteResolved(from)
        deleteTrack(from)
    }
}

/**
 * Merges tracks that are the same file ingested twice.
 *
 * A MediaStore content URI and a SAF document URI for the same file share
 * nothing, so for a long time neither ingest path could see a row the other had
 * created — and only the folder scan had a filename fallback to compensate.
 * Scanning a folder and then running a MediaStore scan therefore inserted a
 * second row for every track in the library. Ingest no longer does that; this
 * repairs the libraries where it already happened, which a rescan cannot,
 * because both rows exist with valid URIs of their own.
 *
 * Nothing the user created is discarded. Play counts are summed rather than
 * picked between, likes are unioned and keep the earlier timestamp, and playlist
 * membership is repointed with the surviving entry's position left alone. That
 * is the whole reason this exists instead of "rebuild the database": the plays,
 * likes and playlists hanging off the discarded copy are not recoverable by
 * rescanning.
 */
class TrackMaintenance(
    private val db: MusicPimDb,
    private val dao: TrackMaintenanceDao
) {

    suspend fun mergeDuplicates(): TrackCleanupReport = db.withTransaction {
        val before = dao.trackCount()
        val playsBefore = dao.totalPlays()

        val groups = dao.duplicateGroups().groupBy { it.fileName to it.sizeBytes }

        var groupsMerged = 0
        var removed = 0
        for ((_, rows) in groups) {
            if (rows.size < 2) continue
            // Lowest id survives — the same choice the artist merge makes, and
            // it keeps the oldest row, which is the one other things point at.
            val survivor = rows.minOf { it.trackId }
            for (row in rows) {
                if (row.trackId == survivor) continue
                dao.mergeInto(from = row.trackId, to = survivor)
                removed++
            }
            groupsMerged++
        }

        // Counted after the fact rather than assumed. Summing play counts across
        // a merge is the easiest thing here to get quietly wrong, so the total
        // is measured and reported: it should equal [playsBefore] exactly.
        val playsAfter = dao.totalPlays()
        check(playsAfter == playsBefore) {
            "merge lost play history: $playsBefore plays before, $playsAfter after"
        }

        TrackCleanupReport(
            tracksBefore = before,
            groupsMerged = groupsMerged,
            tracksRemoved = removed,
            playsPreserved = playsAfter,
            tracksAfter = dao.trackCount()
        )
    }
}

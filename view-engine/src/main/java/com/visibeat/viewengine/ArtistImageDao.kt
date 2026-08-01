package com.visibeat.viewengine

import androidx.compose.runtime.Stable

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.visibeat.musicdb.ArtistImageEntity
import kotlinx.coroutines.flow.Flow

/** An artist still needing a portrait lookup. */
data class ArtistLookupCandidate(
    val artistId: Long,
    val artistName: String,
    val musicBrainzId: String?,
    val attempts: Int
)

/**
 * Stable: a process-lifetime singleton that never changes identity and exposes
 * no mutable state to the composition. Without the annotation the Compose
 * compiler assumes otherwise and every screen taking one is non-skippable.
 */
@Stable
@Dao
interface ArtistImageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(image: ArtistImageEntity)

    @Query("SELECT * FROM artist_images WHERE artistId = :artistId LIMIT 1")
    suspend fun get(artistId: Long): ArtistImageEntity?

    @Query("SELECT * FROM artist_images WHERE artistId = :artistId LIMIT 1")
    fun observe(artistId: Long): Flow<ArtistImageEntity?>

    /**
     * Stores a fetched article extract.
     *
     * A targeted update rather than an upsert of the whole row: the bio arrives
     * long after the portrait, on a different trigger, and must not clobber what
     * the image worker already found.
     */
    @Query(
        """
        UPDATE artist_images
        SET wikipediaExtract = :extract, wikipediaUrl = :url
        WHERE artistId = :artistId
        """
    )
    suspend fun updateWikipedia(artistId: Long, extract: String?, url: String?)

    /** Ensures a row exists so [updateWikipedia] has something to write to. */
    @Query(
        """
        INSERT OR IGNORE INTO artist_images (artistId, source, fetchedAt, attempts)
        VALUES (:artistId, 'NONE', :now, 0)
        """
    )
    suspend fun ensureRow(artistId: Long, now: Long)

    @Query("SELECT COUNT(*) FROM artist_images WHERE imageUrl IS NOT NULL")
    fun observeResolvedCount(): Flow<Int>

    /**
     * Artists with no cached Wikipedia lead, most-listened first.
     *
     * LEFT JOIN so artists with no `artist_images` row at all are included —
     * that is most of them, since the row is only created when a portrait
     * lookup or an Info sheet has already touched the artist.
     */
    @Query("""
        SELECT a.artistId AS artistId, a.displayName AS artistName,
               ai.musicBrainzId AS musicBrainzId,
               COALESCE(ai.attempts, 0) AS attempts
        FROM artists a
        LEFT JOIN artist_images ai ON ai.artistId = a.artistId
        LEFT JOIN resolved_tracks rt ON rt.primaryArtistId = a.artistId
        LEFT JOIN play_history ph ON ph.trackId = rt.trackId
        WHERE ai.wikipediaExtract IS NULL OR ai.wikipediaExtract = ''
        GROUP BY a.artistId
        ORDER BY COALESCE(SUM(ph.playCount), 0) DESC, a.displayName ASC
        LIMIT :limit
    """)
    suspend fun getArtistsNeedingBio(limit: Int): List<ArtistBioCandidate>

    @Query("SELECT COUNT(*) FROM artist_images WHERE wikipediaExtract IS NOT NULL AND wikipediaExtract != ''")
    fun observeBioCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM artist_images")
    fun observeAttemptedCount(): Flow<Int>

    @Query("DELETE FROM artist_images")
    suspend fun clearAll()

    /**
     * Artists worth looking up next.
     *
     * Never-attempted artists come first, then the ones whose previous attempt
     * came up empty and are old enough and cheap enough to be worth another go.
     * Artists with a portrait already are excluded entirely.
     *
     * Ordered by track count so the artists the user actually listens to get
     * their faces first — a library's long tail of one-track features can wait.
     */
    @Query("""
        SELECT
            rt.primaryArtistId AS artistId,
            MAX(rt.effectiveArtistDisplay) AS artistName,
            MAX(ai.musicBrainzId) AS musicBrainzId,
            COALESCE(MAX(ai.attempts), 0) AS attempts
        FROM resolved_tracks rt
        LEFT JOIN artist_images ai ON ai.artistId = rt.primaryArtistId
        WHERE rt.primaryArtistId IS NOT NULL
          AND rt.effectiveArtistDisplay IS NOT NULL
          AND (
                ai.artistId IS NULL
                OR (ai.imageUrl IS NULL AND ai.attempts < :maxAttempts AND ai.fetchedAt < :retryBefore)
              )
        GROUP BY rt.primaryArtistId
        ORDER BY COUNT(*) DESC
        LIMIT :limit
    """)
    suspend fun getLookupCandidates(
        maxAttempts: Int,
        retryBefore: Long,
        limit: Int
    ): List<ArtistLookupCandidate>
}

/** An artist awaiting a Wikipedia lead. */
data class ArtistBioCandidate(
    val artistId: Long,
    val artistName: String,
    val musicBrainzId: String?,
    val attempts: Int
)

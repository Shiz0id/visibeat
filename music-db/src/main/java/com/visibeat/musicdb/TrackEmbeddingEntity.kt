package com.visibeat.musicdb

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One track's audio embedding, as produced by whichever model is installed.
 *
 * The vector is a BLOB rather than a Room `FloatArray` with a TypeConverter.
 * A converter would apply to every `FloatArray` crossing the database, and the
 * conversion is the hot path of the whole feature — 5,000 rows deserialised on
 * every index load. [floats] does it in one bulk `asFloatBuffer().get()` instead
 * of a boxed element-by-element walk.
 *
 * [modelId] and [dim] are stored per row, not globally, because they are the
 * invalidation key. Vectors from two different models are not comparable at all
 * — cosine between them is noise that looks like a number — so swapping the
 * model has to re-index the library rather than silently produce a radio that
 * shuffles. The indexer treats "row's modelId != installed modelId" exactly like
 * "no row at all".
 *
 * [artistId] and [albumId] are denormalised copies. Diversity penalties compare
 * them for every candidate on every step of the queue, and joining out to
 * `resolved_tracks` 5,000 times per skip is the difference between a scan that
 * is free and one that is not.
 */
@Entity(
    tableName = "track_embeddings",
    indices = [
        // The indexer's "what is missing" query filters on this.
        Index(value = ["modelId"]),
        Index(value = ["artistId"]),
        Index(value = ["albumId"])
    ]
)
data class TrackEmbeddingEntity(
    @PrimaryKey val trackId: Long,

    /** Which model produced this. Changing models invalidates every row. */
    val modelId: String,

    /** Vector length. Carried so a dimension change is caught, not misread. */
    val dim: Int,

    /**
     * The vector, little-endian float32, already L2-normalised.
     *
     * Normalising at write time turns cosine similarity into a plain dot
     * product at query time — the magnitudes are all 1, so the denominator is
     * gone. That removes two square roots per comparison from a loop that runs
     * 5,000 times per track selection.
     */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val vector: ByteArray,

    val artistId: Long?,
    val albumId: Long?,

    /** When this was computed, so a re-index can be ordered oldest-first. */
    val computedAt: Long
) {
    /** Decodes [vector] into floats. Bulk copy, not a per-element loop. */
    fun floats(): FloatArray {
        val out = FloatArray(dim)
        ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    // A ByteArray field means the generated equals/hashCode compare references.
    // Spelled out so two rows holding the same vector are actually equal, which
    // the tests rely on.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackEmbeddingEntity) return false
        return trackId == other.trackId &&
            modelId == other.modelId &&
            dim == other.dim &&
            vector.contentEquals(other.vector) &&
            artistId == other.artistId &&
            albumId == other.albumId &&
            computedAt == other.computedAt
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + dim
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + (artistId?.hashCode() ?: 0)
        result = 31 * result + (albumId?.hashCode() ?: 0)
        result = 31 * result + computedAt.hashCode()
        return result
    }

    companion object {
        /** Packs [floats] little-endian. Mirror of [floats]. */
        fun pack(floats: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(floats)
            return buf.array()
        }
    }
}

/** Just the columns the in-memory index needs, so the row is never over-read. */
data class EmbeddingRow(
    val trackId: Long,
    val vector: ByteArray,
    val artistId: Long?,
    val albumId: Long?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingRow) return false
        return trackId == other.trackId &&
            vector.contentEquals(other.vector) &&
            artistId == other.artistId &&
            albumId == other.albumId
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + (artistId?.hashCode() ?: 0)
        result = 31 * result + (albumId?.hashCode() ?: 0)
        return result
    }
}

/** One track's genre text, as resolved for display. */
data class TrackGenreRow(val trackId: Long, val genre: String)

/** A track that still needs embedding, with everything needed to decode it. */
data class UnindexedTrack(
    val trackId: Long,
    val uriString: String,
    val durationMs: Long?,
    val artistId: Long?,
    val albumId: Long?
)

@Dao
interface TrackEmbeddingDao {

    /**
     * Everything for the installed model, for loading the in-memory index.
     *
     * Not a Flow: the index is rebuilt deliberately, not on every write, or the
     * indexer's 5,000 inserts would each rebuild a 5,000-vector array.
     */
    @Query(
        "SELECT trackId, vector, artistId, albumId FROM track_embeddings " +
            "WHERE modelId = :modelId AND dim = :dim AND length(vector) = :bytes"
    )
    suspend fun loadAll(modelId: String, dim: Int, bytes: Int): List<EmbeddingRow>

    @Query("SELECT COUNT(*) FROM track_embeddings WHERE modelId = :modelId AND dim = :dim")
    fun observeIndexedCount(modelId: String, dim: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeTrackCount(): Flow<Int>

    /**
     * Tracks with a real vector.
     *
     * The length test is what separates them from tombstones. A track the
     * decoder could not read gets a row with an empty blob so it is not tried
     * again on every future run — see the worker — and those rows are attempts,
     * not results. Counting them would report a library as analysed when part
     * of it is unreadable.
     */
    @Query(
        "SELECT COUNT(*) FROM track_embeddings " +
            "WHERE modelId = :modelId AND dim = :dim AND length(vector) = :bytes"
    )
    suspend fun countFor(modelId: String, dim: Int, bytes: Int): Int

    /** Tracks that were tried and could not be decoded. */
    @Query(
        "SELECT COUNT(*) FROM track_embeddings " +
            "WHERE modelId = :modelId AND dim = :dim AND length(vector) = 0"
    )
    suspend fun countFailed(modelId: String, dim: Int): Int

    /** Every row, including any left by a previous model. */
    @Query("SELECT COUNT(*) FROM track_embeddings")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun countTracks(): Int

    /**
     * Tracks with no usable embedding, oldest additions first.
     *
     * A row belonging to a different model does not count as indexed — the
     * LEFT JOIN carries [modelId] into the ON clause rather than the WHERE, so
     * a track embedded by the previous model comes back as unindexed instead of
     * being filtered out of existence.
     */
    @Query(
        """
        SELECT t.trackId AS trackId,
               t.uriString AS uriString,
               t.durationMs AS durationMs,
               r.primaryArtistId AS artistId,
               r.releaseId AS albumId
        FROM tracks t
        LEFT JOIN track_embeddings e
               ON e.trackId = t.trackId AND e.modelId = :modelId AND e.dim = :dim
        LEFT JOIN resolved_tracks r ON r.trackId = t.trackId
        WHERE e.trackId IS NULL
        ORDER BY t.addedToLibraryAt ASC
        LIMIT :limit
        """
    )
    suspend fun findUnindexed(modelId: String, dim: Int, limit: Int): List<UnindexedTrack>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<TrackEmbeddingEntity>)

    @Query("DELETE FROM track_embeddings WHERE modelId != :modelId OR dim != :dim")
    suspend fun deleteStale(modelId: String, dim: Int): Int

    /**
     * Forgets the tracks that could not be decoded, so they are tried again.
     *
     * Tombstones say "do not retry", which is right for a file that is gone and
     * wrong the moment the decoder improves. Targeted rather than a full clear:
     * a library is minutes of CPU to rebuild, and the good vectors have done
     * nothing to deserve it.
     */
    @Query("DELETE FROM track_embeddings WHERE length(vector) = 0")
    suspend fun deleteFailures(): Int

    /**
     * Discards every vector, so the next run recomputes from scratch.
     *
     * Needed because "which rows are wrong" is not a question the schema can
     * answer. A row records that a track was embedded, not whether the audio
     * behind it was any good — so when the definition of a valid embedding
     * changes, the only honest move is to drop the lot.
     */
    @Query("DELETE FROM track_embeddings")
    suspend fun deleteAll(): Int

    /**
     * Genre text per track, for the radio's optional genre weighting.
     *
     * Read alongside the index rather than joined per candidate: the weighting
     * is consulted for every candidate on every step of a queue, and a join
     * there would be thousands of lookups per station.
     */
    @Query(
        "SELECT trackId, effectiveGenreDisplay AS genre FROM resolved_tracks " +
            "WHERE effectiveGenreDisplay IS NOT NULL AND effectiveGenreDisplay != ''"
    )
    suspend fun loadGenres(): List<TrackGenreRow>

    /** Tracks removed from the library leave their vectors behind otherwise. */
    @Query("DELETE FROM track_embeddings WHERE trackId NOT IN (SELECT trackId FROM tracks)")
    suspend fun deleteOrphans(): Int
}

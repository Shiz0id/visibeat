package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// =====================================================
// User-curated playlists
//
// The only tables in the database that hold the user's own decisions rather than
// facts observed from files. Nothing here is derivable from a rescan, so these
// are the tables a migration must never drop.
// =====================================================

@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["name"]),
        Index(value = ["pinnedAt"]),
        Index(value = ["lastOpenedAt"])
    ]
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String,
    val createdAt: Long,
    /** Bumped whenever tracks are added or removed. */
    val updatedAt: Long,
    /**
     * When the playlist was last opened. Together with [updatedAt] this is what
     * "Recents" sorts on — a playlist you listened to and one you edited are
     * both recent activity.
     */
    val lastOpenedAt: Long? = null,
    /**
     * When the user pinned this playlist, or null if it is not pinned.
     *
     * A nullable timestamp rather than a boolean so pinned playlists have a
     * stable order of their own and do not reshuffle when the sort mode changes.
     */
    val pinnedAt: Long? = null
)

/**
 * Membership of a track in a playlist.
 *
 * The composite primary key means a track cannot appear in the same playlist
 * twice. That is a deliberate simplification — [position] gives explicit
 * ordering, and duplicate entries would need a surrogate key to be addressable.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["trackId"]),
        Index(value = ["playlistId", "position"])
    ]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: Long,
    /** Position within the playlist, ascending. Gaps are harmless. */
    val position: Int,
    val addedAt: Long
)

/**
 * One row per track that has ever been played.
 *
 * "Recently added" was standing in for "recently played" everywhere, because
 * nothing recorded playback at all — the library's recent shelf was really just
 * ingest order.
 */
@Entity(
    tableName = "play_history",
    indices = [Index(value = ["lastPlayedAt"])]
)
data class PlayHistoryEntity(
    @PrimaryKey val trackId: Long,
    val lastPlayedAt: Long,
    val playCount: Int
)

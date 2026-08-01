package com.visibeat.musicdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A track the user liked.
 *
 * Deliberately not a playlist. Liking is a per-track boolean that every row in
 * every list has to be able to answer cheaply, which is a primary-key lookup
 * here and a membership query through `playlist_tracks` otherwise. It also
 * cannot be renamed or deleted the way a real playlist can, which matters for
 * something the app treats as always present.
 *
 * Independent of [LikedReleaseEntity]: liking an album does not like its tracks.
 */
@Entity(
    tableName = "liked_tracks",
    indices = [Index(value = ["likedAt"])]
)
data class LikedTrackEntity(
    @PrimaryKey val trackId: Long,
    /** Newest-first is the only order a liked list ever wants. */
    val likedAt: Long
)

/**
 * An artist the user followed.
 *
 * Third of the three like tables and identical in shape. "Follow" on the artist
 * page and "Liked Artists" in the library are the same thing.
 */
@Entity(
    tableName = "liked_artists",
    indices = [Index(value = ["likedAt"])]
)
data class LikedArtistEntity(
    @PrimaryKey val artistId: Long,
    val likedAt: Long
)

/**
 * A release the user liked.
 *
 * Its own concept rather than "every track on it is liked", so that liking an
 * album says something about the album and does not flood the songs list.
 */
@Entity(
    tableName = "liked_releases",
    indices = [Index(value = ["likedAt"])]
)
data class LikedReleaseEntity(
    @PrimaryKey val releaseId: Long,
    val likedAt: Long
)

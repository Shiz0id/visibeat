package com.visibeat.viewengine

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.visibeat.coredb.*
import com.visibeat.musicdb.*

/**
 * The database schema version, in one place.
 *
 * Settings displays this. It used to be a second constant in MainActivity with a
 * comment promising to keep it in step, which it did not — the app reported v5
 * against a v6 database. An annotation argument has to be a compile-time
 * constant, so this is the only shape that cannot drift.
 */
const val MUSIC_DB_VERSION = 14

@Database(
  entities = [
    // Core
    MetadataObservationEntity::class,
    DismissedSuggestionEntity::class,
    // Music
    LibraryRootEntity::class,
    GenreEntity::class,
    ArtistEntity::class,
    ReleaseEntity::class,
    TrackEntity::class,
    TrackArtistCrossRef::class,
    TrackReleaseCrossRef::class,
    TrackGenreCrossRef::class,
    TrackIdentityEntity::class,
    ArtistIdentityEntity::class,
    ReleaseIdentityEntity::class,
    // User data
    PlaylistEntity::class,
    PlaylistTrackCrossRef::class,
    PlayHistoryEntity::class,
    LikedTrackEntity::class,
    LikedReleaseEntity::class,
    LikedArtistEntity::class,
    // Enrichment caches
    ArtistImageEntity::class,
    TrackEmbeddingEntity::class,
    // View
    ResolvedTrackEntity::class,
    ResolvedReleaseEntity::class
  ],
  version = MUSIC_DB_VERSION,  // 14: enrichment attempt tracking + genre fetch state
  exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MusicPimDb : RoomDatabase() {
  abstract fun libraryRootDao(): LibraryRootDao
  abstract fun trackDao(): TrackDao
  abstract fun artistDao(): ArtistDao
  abstract fun releaseDao(): ReleaseDao
  abstract fun genreDao(): GenreDao
  abstract fun identityDao(): IdentityDao
  abstract fun relationshipDao(): RelationshipDao
  abstract fun observationDao(): ObservationDao
  abstract fun dismissalDao(): DismissalDao
  abstract fun resolvedDao(): ResolvedCacheDao
  abstract fun timelineDao(): TimelineDao
  abstract fun feedDao(): FeedDao
  abstract fun libraryDao(): LibraryDao
  abstract fun playlistDao(): PlaylistDao
  abstract fun playHistoryDao(): PlayHistoryDao
  abstract fun artistImageDao(): ArtistImageDao
  abstract fun artistMaintenanceDao(): ArtistMaintenanceDao
  abstract fun trackMaintenanceDao(): TrackMaintenanceDao
  abstract fun likesDao(): LikesDao
  abstract fun albumDao(): AlbumDao
  abstract fun artistPageDao(): ArtistPageDao
  abstract fun trackEmbeddingDao(): TrackEmbeddingDao
}

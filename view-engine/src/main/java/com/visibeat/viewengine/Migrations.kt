package com.visibeat.viewengine

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations for MusicPimDb.
 */
object Migrations {
    /**
     * Migration 1->2: Add MusicBrainz enrichment fields.
     * - musicBrainzId: MBID for lookups
     * - dateSource: LOCAL, MUSICBRAINZ, or USER
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE releases ADD COLUMN musicBrainzId TEXT")
            db.execSQL("ALTER TABLE releases ADD COLUMN dateSource TEXT NOT NULL DEFAULT 'LOCAL'")
        }
    }
    
    /**
     * Migration 3->4: playlists, playlist membership, and play history.
     *
     * These three tables hold the only data in the database that a rescan cannot
     * rebuild, which is why this migration exists at all. The database is opened
     * with `fallbackToDestructiveMigration()`, so without it every upgrade would
     * silently delete the user's playlists along with their library.
     *
     * The statements are copied verbatim from Room's exported schema
     * (`view-engine/schemas/…/4.json`) rather than written by hand. Room
     * validates the resulting schema when the database opens and throws on any
     * difference — down to index names — so guessed DDL is a crash on launch,
     * not a warning.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlists` (" +
                    "`playlistId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`lastOpenedAt` INTEGER, " +
                    "`pinnedAt` INTEGER)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_name` ON `playlists` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_pinnedAt` ON `playlists` (`pinnedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_lastOpenedAt` ON `playlists` (`lastOpenedAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_tracks` (" +
                    "`playlistId` INTEGER NOT NULL, " +
                    "`trackId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`playlistId`, `trackId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_trackId` ON `playlist_tracks` (`trackId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_position` ON `playlist_tracks` (`playlistId`, `position`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `play_history` (" +
                    "`trackId` INTEGER NOT NULL, " +
                    "`lastPlayedAt` INTEGER NOT NULL, " +
                    "`playCount` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`trackId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_lastPlayedAt` ON `play_history` (`lastPlayedAt`)")
        }
    }

    /**
     * Migration 4->5: cached artist portraits.
     *
     * A pure cache — losing it would only mean looking the images up again — but
     * it shares a database with playlists, so it still needs a real migration
     * rather than a destructive rebuild.
     *
     * DDL copied verbatim from Room's exported schema (`schemas/…/5.json`).
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `artist_images` (" +
                    "`artistId` INTEGER NOT NULL, " +
                    "`imageUrl` TEXT, " +
                    "`source` TEXT NOT NULL, " +
                    "`musicBrainzId` TEXT, " +
                    "`wikidataId` TEXT, " +
                    "`fetchedAt` INTEGER NOT NULL, " +
                    "`attempts` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`artistId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_images_fetchedAt` ON `artist_images` (`fetchedAt`)")
        }
    }

    /**
     * Migration 5->6: one row per artist, enforced.
     *
     * `index_artists_displayNameNormalized` was not unique, so ingest's
     * check-then-insert had nothing stopping it creating a second row for an
     * artist it had just failed to find — which is how a library ends up with
     * two "24kGoldn" entries holding nine and two tracks.
     *
     * Adding the constraint means merging what is already there first: pick the
     * lowest artistId per normalised name as the survivor, repoint everything
     * that references the losers, then delete them.
     *
     * Merging is done on the *stored* normalised value rather than the app's
     * newer, stricter normalisation, because SQLite cannot fold diacritics.
     * Rows that only differ by accent (Beyoncé/Beyonce) therefore survive this
     * step and are merged later by the cleanup pass, which runs in Kotlin.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Surviving id for any artist id, by normalised name.
            val survivorFor =
                """(SELECT MIN(a2.artistId) FROM artists a2
                    WHERE a2.displayNameNormalized =
                        (SELECT a1.displayNameNormalized FROM artists a1
                         WHERE a1.artistId = %s))"""

            // track_artist is keyed on (trackId, artistId, role): repointing can
            // collide with a row that already exists, so OR REPLACE clears the
            // loser instead of aborting the migration.
            db.execSQL(
                "UPDATE OR REPLACE track_artist SET artistId = " +
                    survivorFor.format("track_artist.artistId") +
                    " WHERE artistId IS NOT NULL"
            )

            // No uniqueness here, so a plain update is safe.
            db.execSQL(
                "UPDATE resolved_tracks SET primaryArtistId = " +
                    survivorFor.format("resolved_tracks.primaryArtistId") +
                    " WHERE primaryArtistId IS NOT NULL"
            )

            // Unique on (source, sourceKey), which repointing does not touch.
            db.execSQL(
                "UPDATE OR REPLACE artist_identities SET artistId = " +
                    survivorFor.format("artist_identities.artistId") +
                    " WHERE artistId IS NOT NULL"
            )

            // artist_images is keyed by artistId and is a pure cache. Rather
            // than merge portraits between rows that are about to become one
            // artist, drop it and let the worker refill.
            db.execSQL("DELETE FROM artist_images")

            db.execSQL(
                """DELETE FROM artists WHERE artistId NOT IN
                   (SELECT MIN(artistId) FROM artists GROUP BY displayNameNormalized)"""
            )

            db.execSQL("DROP INDEX IF EXISTS `index_artists_displayNameNormalized`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_artists_displayNameNormalized` " +
                    "ON `artists` (`displayNameNormalized`)"
            )
        }
    }

    /**
     * Migration 6->7: releases and observations get real identities.
     *
     * Two constraints that should always have existed, and the data repair each
     * one needs before it can be added.
     *
     * **Releases** were keyed on the normalised title alone, and not even
     * uniquely. Every "Greatest Hits", "Live" and "Demos" in a library therefore
     * became one release — the path taken by every file without MusicBrainz
     * tags, which is most of them. The artist half of the key is backfilled from
     * `artists.displayNameNormalized` via the track graph, preferring the album
     * artist, because SQLite cannot run the app's own normaliser. Releases that
     * were wrongly merged *before* this migration cannot be separated here — the
     * rows that would tell them apart are already gone — so that needs a rescan.
     *
     * **Observations** had no unique index at all, so `OnConflictStrategy.IGNORE`
     * had nothing to ignore and every rescan appended another identical copy of
     * every fact. Duplicates are collapsed to the earliest observation of each,
     * which is the one whose `observedAt` is true.
     *
     * Both indices are copied verbatim from Room's exported `7.json`, as the
     * 3->4 step explains at length.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ── Releases ──────────────────────────────────────────────
            db.execSQL("ALTER TABLE releases ADD COLUMN artistNormalized TEXT NOT NULL DEFAULT ''")

            // Backfill from the track graph. Album artist beats primary artist,
            // matching releaseArtistKey() in the ingest repository; a release
            // with no artist at all keeps the empty string, which is a real key
            // here rather than a NULL that would defeat the unique index.
            db.execSQL(
                """UPDATE releases SET artistNormalized = COALESCE((
                       SELECT a.displayNameNormalized
                       FROM track_release tr
                       JOIN track_artist ta ON ta.trackId = tr.trackId
                       JOIN artists a ON a.artistId = ta.artistId
                       WHERE tr.releaseId = releases.releaseId
                       ORDER BY CASE ta.role
                           WHEN 'ALBUM_ARTIST' THEN 0
                           WHEN 'PRIMARY' THEN 1
                           ELSE 2
                       END
                       LIMIT 1
                   ), '')"""
            )

            // Surviving id for any release id, by the new two-part key.
            val survivorFor = { column: String ->
                """(SELECT MIN(r2.releaseId) FROM releases r2
                    WHERE r2.titleNormalized =
                            (SELECT r1.titleNormalized FROM releases r1 WHERE r1.releaseId = $column)
                      AND r2.artistNormalized =
                            (SELECT r1.artistNormalized FROM releases r1 WHERE r1.releaseId = $column))"""
            }

            // track_release is keyed on (trackId, releaseId), so repointing can
            // land on a row that already exists; OR REPLACE absorbs it instead
            // of aborting the migration.
            db.execSQL(
                "UPDATE OR REPLACE track_release SET releaseId = " +
                    survivorFor("track_release.releaseId")
            )
            // Unique on (source, sourceKey), which repointing does not touch.
            db.execSQL(
                "UPDATE OR REPLACE release_identities SET releaseId = " +
                    survivorFor("release_identities.releaseId")
            )
            // No uniqueness here, so a plain update is safe.
            db.execSQL(
                "UPDATE resolved_tracks SET releaseId = " +
                    survivorFor("resolved_tracks.releaseId") +
                    " WHERE releaseId IS NOT NULL"
            )

            db.execSQL(
                """DELETE FROM releases WHERE releaseId NOT IN
                   (SELECT MIN(releaseId) FROM releases GROUP BY titleNormalized, artistNormalized)"""
            )
            // resolved_releases is a cache keyed by releaseId. Drop the rows
            // whose release just went away, and correct the track counts of the
            // survivors that absorbed them — otherwise every merged album shows
            // a stale count until the next scan rewrites it.
            db.execSQL("DELETE FROM resolved_releases WHERE releaseId NOT IN (SELECT releaseId FROM releases)")
            db.execSQL(
                """UPDATE resolved_releases SET trackCount =
                   (SELECT COUNT(*) FROM track_release tr WHERE tr.releaseId = resolved_releases.releaseId)"""
            )

            db.execSQL("DROP INDEX IF EXISTS `index_releases_titleNormalized`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_releases_titleNormalized_artistNormalized` " +
                    "ON `releases` (`titleNormalized`, `artistNormalized`)"
            )

            // ── Observations ──────────────────────────────────────────
            db.execSQL(
                """DELETE FROM metadata_observations WHERE observationId NOT IN
                   (SELECT MIN(observationId) FROM metadata_observations
                    GROUP BY subjectType, subjectId, field, source, value)"""
            )
            // Superseded: the new index has the same leading columns, so it
            // serves listBestFirst's filter on its own.
            db.execSQL("DROP INDEX IF EXISTS `index_metadata_observations_subjectType_subjectId_field`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_metadata_observations_subjectType_subjectId_field_source_value` " +
                    "ON `metadata_observations` (`subjectType`, `subjectId`, `field`, `source`, `value`)"
            )
        }
    }

    /**
     * Migration 7->8: indices for the orders the library screens actually use.
     *
     * Pure performance, no data change. `Library > Tracks` orders every row by
     * `effectiveTitle`, `Library > Albums` groups and orders by
     * `effectiveAlbumTitle`, and `Library > Artists` orders by `displayName` —
     * none of which were indexed, so each of those screens paid a full scan plus
     * an external sort every time it opened. That is invisible on a few hundred
     * tracks and very much not on a few tens of thousands.
     *
     * DDL copied verbatim from Room's exported `8.json`.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_resolved_tracks_effectiveTitle` " +
                    "ON `resolved_tracks` (`effectiveTitle`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_artists_displayName` " +
                    "ON `artists` (`displayName`)"
            )
        }
    }

    /**
     * Migration 8->9: album ordering, audio format, and likes.
     *
     * `resolved_tracks` gains the three facts an album view needs and never had:
     * disc number, track number and MIME type. All three already exist elsewhere
     * in the database — the first two have been written to `track_release` since
     * the schema was created and read by nothing at all, and the third sits on
     * `tracks` — so this backfills from those rather than demanding a rescan.
     * Until it ran, an album could only be listed in ingest order.
     *
     * The two `liked_*` tables are new and start empty. They are deliberately
     * not playlists: a like is a primary-key lookup that every row in every list
     * has to answer, and neither table can be renamed or deleted the way a real
     * playlist can.
     *
     * DDL copied verbatim from Room's exported `9.json`.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE resolved_tracks ADD COLUMN discNumber INTEGER")
            db.execSQL("ALTER TABLE resolved_tracks ADD COLUMN trackNumber INTEGER")
            db.execSQL("ALTER TABLE resolved_tracks ADD COLUMN mimeType TEXT")

            // A track belongs to at most one release, so these are simple lookups.
            db.execSQL(
                """UPDATE resolved_tracks SET
                       discNumber = (SELECT tr.discNumber FROM track_release tr
                                     WHERE tr.trackId = resolved_tracks.trackId),
                       trackNumber = (SELECT tr.trackNumber FROM track_release tr
                                      WHERE tr.trackId = resolved_tracks.trackId)"""
            )
            db.execSQL(
                """UPDATE resolved_tracks SET
                       mimeType = (SELECT t.mimeType FROM tracks t
                                   WHERE t.trackId = resolved_tracks.trackId)"""
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `liked_tracks` (" +
                    "`trackId` INTEGER NOT NULL, " +
                    "`likedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`trackId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_liked_tracks_likedAt` ON `liked_tracks` (`likedAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `liked_releases` (" +
                    "`releaseId` INTEGER NOT NULL, " +
                    "`likedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`releaseId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_liked_releases_likedAt` ON `liked_releases` (`likedAt`)")
        }
    }

    /**
     * Migration 9->10: artist descriptions, and followed artists.
     *
     * `artist_images` gains a description. It lives on that row rather than in a
     * table of its own because it comes from the same Wikidata entity, on the
     * same request, with the same coverage and the same retry lifecycle as the
     * portrait — the row is really "what Wikidata knows about this artist".
     * Existing rows keep their portraits and fill the blurb in on their next
     * lookup rather than being invalidated.
     *
     * `liked_artists` is the third and last of the like tables, and is what the
     * artist page's Follow control writes to.
     *
     * DDL copied verbatim from Room's exported `10.json`.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE artist_images ADD COLUMN description TEXT")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `liked_artists` (" +
                    "`artistId` INTEGER NOT NULL, " +
                    "`likedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`artistId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_liked_artists_likedAt` ON `liked_artists` (`likedAt`)")
        }
    }

    /**
     * Migration 10->11: the Wikipedia article cache.
     *
     * `wikipediaTitle` is captured for free — the same `wbgetentities` request
     * that already fetches the portrait and the description can ask for the
     * item's sitelinks. The extract and its URL are filled in on demand, the
     * first time someone opens Info for that artist, rather than by crawling the
     * whole library for text nobody may read.
     *
     * The URL is not decoration: article text is CC BY-SA, so anything
     * displaying an extract has to attribute it and link back.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE artist_images ADD COLUMN wikipediaTitle TEXT")
            db.execSQL("ALTER TABLE artist_images ADD COLUMN wikipediaExtract TEXT")
            db.execSQL("ALTER TABLE artist_images ADD COLUMN wikipediaUrl TEXT")
        }
    }

    /**
     * Migration 11->12: precomputed timeline bucket anchors.
     *
     * The timeline grouped by `strftime('%Y-%m-01', …)` of the release epoch,
     * which no index can serve — so every bucket query was a full scan of
     * `resolved_tracks` plus a sort, re-run on every emission of a Flow that
     * fires on any database change. Fine at a few hundred tracks; the reason the
     * screen could not carry a large library.
     *
     * The three anchors are now columns, written once per resolve and indexed.
     * Backfilled here with the very expressions the queries used to run, so the
     * scan happens once instead of on every read.
     *
     * DDL copied verbatim from Room's exported `12.json`.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE resolved_tracks ADD COLUMN bucketDayEpochMs INTEGER")
            db.execSQL("ALTER TABLE resolved_tracks ADD COLUMN bucketMonthEpochMs INTEGER")
            db.execSQL("ALTER TABLE resolved_tracks ADD COLUMN bucketYearEpochMs INTEGER")

            db.execSQL(
                """UPDATE resolved_tracks SET
                       bucketDayEpochMs = CASE WHEN effectiveReleaseDateEpochMs IS NULL THEN NULL ELSE
                           (strftime('%s', strftime('%Y-%m-%d 00:00:00',
                               effectiveReleaseDateEpochMs/1000, 'unixepoch')) * 1000) END,
                       bucketMonthEpochMs = CASE WHEN effectiveReleaseDateEpochMs IS NULL THEN NULL ELSE
                           (strftime('%s', strftime('%Y-%m-01 00:00:00',
                               effectiveReleaseDateEpochMs/1000, 'unixepoch')) * 1000) END,
                       bucketYearEpochMs = CASE WHEN effectiveReleaseDateEpochMs IS NULL THEN NULL ELSE
                           (strftime('%s', strftime('%Y-01-01 00:00:00',
                               effectiveReleaseDateEpochMs/1000, 'unixepoch')) * 1000) END"""
            )

            db.execSQL("CREATE INDEX IF NOT EXISTS `index_resolved_tracks_bucketDayEpochMs` ON `resolved_tracks` (`bucketDayEpochMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_resolved_tracks_bucketMonthEpochMs` ON `resolved_tracks` (`bucketMonthEpochMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_resolved_tracks_bucketYearEpochMs` ON `resolved_tracks` (`bucketYearEpochMs`)")
        }
    }

    /**
     * Audio embeddings for the offline radio.
     *
     * Purely additive: a new table and its indices. Nothing backfills, because
     * an embedding cannot be derived from anything already in the database —
     * every row has to be earned by decoding the audio, which is the indexer's
     * job. An upgraded install simply starts with an empty index and fills it.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `track_embeddings` (
                       `trackId` INTEGER NOT NULL,
                       `modelId` TEXT NOT NULL,
                       `dim` INTEGER NOT NULL,
                       `vector` BLOB NOT NULL,
                       `artistId` INTEGER,
                       `albumId` INTEGER,
                       `computedAt` INTEGER NOT NULL,
                       PRIMARY KEY(`trackId`))"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_embeddings_modelId` ON `track_embeddings` (`modelId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_embeddings_artistId` ON `track_embeddings` (`artistId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_embeddings_albumId` ON `track_embeddings` (`albumId`)")
        }
    }

    /**
     * Enrichment bookkeeping.
     *
     * Purely additive. Existing releases start at zero attempts, which is the
     * honest default: nothing was recorded about them either way, so an
     * already-failing release simply gets its three tries from here.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE releases ADD COLUMN enrichAttempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE releases ADD COLUMN lastEnrichAt INTEGER")
            db.execSQL("ALTER TABLE releases ADD COLUMN genresFetchedAt INTEGER")
        }
    }

    /**
     * All migrations in order.
     *
     * Note there is no 2->3 step; a database at version 2 still falls back to a
     * destructive rebuild, which is acceptable because nothing user-authored
     * existed before version 4.
     */
    val ALL = arrayOf(
        MIGRATION_1_2, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14
    )
}

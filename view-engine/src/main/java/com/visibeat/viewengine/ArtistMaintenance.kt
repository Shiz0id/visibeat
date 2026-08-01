package com.visibeat.viewengine

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.withTransaction
import com.visibeat.coredb.ArtistCredit
import com.visibeat.coredb.ArtistCreditParser
import com.visibeat.coredb.ArtistRole
import com.visibeat.coredb.Confidence
import com.visibeat.coredb.MetaSource
import com.visibeat.coredb.normalizeArtistName
import com.visibeat.musicdb.ArtistDao
import com.visibeat.musicdb.ArtistEntity
import com.visibeat.musicdb.RelationshipDao
import com.visibeat.musicdb.TrackArtistCrossRef

/** What a cleanup run changed. */
data class ArtistCleanupReport(
    val artistsBefore: Int,
    val duplicatesMerged: Int,
    val creditsSplit: Int,
    val featuredLinksAdded: Int,
    val artistsAfter: Int
) {
    val changed: Boolean get() = duplicatesMerged > 0 || creditsSplit > 0 || featuredLinksAdded > 0
}

@Dao
interface ArtistMaintenanceDao {

    @Query("SELECT trackId FROM track_artist WHERE artistId = :artistId")
    suspend fun trackIdsFor(artistId: Long): List<Long>

    /**
     * `OR REPLACE` because track_artist is keyed on (trackId, artistId, role) —
     * repointing can land on a row that already exists, and the merge should
     * absorb it rather than abort.
     */
    @Query("UPDATE OR REPLACE track_artist SET artistId = :to WHERE artistId = :from")
    suspend fun repointTrackArtist(from: Long, to: Long)

    @Query("UPDATE resolved_tracks SET primaryArtistId = :to WHERE primaryArtistId = :from")
    suspend fun repointResolvedTracks(from: Long, to: Long)

    @Query("UPDATE OR REPLACE artist_identities SET artistId = :to WHERE artistId = :from")
    suspend fun repointIdentities(from: Long, to: Long)

    @Query("DELETE FROM artist_images WHERE artistId = :artistId")
    suspend fun dropImage(artistId: Long)

    @Query("SELECT COUNT(*) FROM artists")
    suspend fun artistCount(): Int

    /** Absorbs [from] into [to] completely. */
    @Transaction
    suspend fun mergeInto(from: Long, to: Long) {
        repointTrackArtist(from, to)
        repointResolvedTracks(from, to)
        repointIdentities(from, to)
        // Portraits are keyed by artist and cheap to refetch; merging two
        // caches is not worth the ambiguity about which face wins.
        dropImage(from)
    }
}

/**
 * Repairs the artist table on a library that has already been scanned.
 *
 * The ingest fixes only apply to tracks scanned after them, which is no help at
 * all to a library that is already full of "24kGoldn, DaBaby" rows and two
 * separate 24kGoldns. This does the same work retroactively, reading only what
 * is already in the database — no network, no file access, no rescan.
 *
 * Two passes, in this order:
 *
 *  1. **Merge duplicates.** The 5→6 migration deduplicated on the *stored*
 *     normalised name, which SQL could compare. This pass re-normalises in
 *     Kotlin, which also folds diacritics, catching the Beyoncé/Beyonce pairs
 *     that SQL could not see.
 *  2. **Split composites.** Re-parse each artist's name as a credit and, where
 *     it comes apart, move its tracks to the real lead and add featured links.
 *
 * Splitting uses the same corroboration rule as ingest, with one addition: an
 * artist never corroborates itself, or "24kGoldn, DaBaby" would prove its own
 * existence and never split.
 */
class ArtistMaintenance(
    private val db: MusicPimDb,
    private val artistDao: ArtistDao,
    private val relDao: RelationshipDao,
    private val maintenanceDao: ArtistMaintenanceDao
) {

    suspend fun cleanUp(): ArtistCleanupReport = db.withTransaction {
        val before = maintenanceDao.artistCount()
        val merged = mergeDuplicates()
        val (split, featuredLinks) = splitComposites()
        ArtistCleanupReport(
            artistsBefore = before,
            duplicatesMerged = merged,
            creditsSplit = split,
            featuredLinksAdded = featuredLinks,
            artistsAfter = maintenanceDao.artistCount()
        )
    }

    /**
     * Folds artists whose names normalise identically under the current rules.
     *
     * Grouped first, acted on second, and the order matters. Renaming a survivor
     * into its re-normalised key the moment it is seen — which is what this did —
     * writes a value that a not-yet-visited duplicate is still holding, and
     * `displayNameNormalized` is uniquely indexed. The insert throws
     * `SQLiteConstraintException`, the enclosing transaction aborts, and the
     * whole cleanup fails: on the Beyoncé/Beyonce pair this pass exists to merge,
     * and on nothing else. Deleting a group's losers before renaming its survivor
     * means the key is always free by the time it is claimed.
     */
    private suspend fun mergeDuplicates(): Int {
        val groups = linkedMapOf<String, MutableList<ArtistEntity>>()
        for (artist in artistDao.listAll()) {
            val key = normalizeArtistName(artist.displayName)
            if (key.isEmpty()) continue
            groups.getOrPut(key) { mutableListOf() } += artist
        }

        var merged = 0
        for ((key, members) in groups) {
            // listAll orders by artistId, so the lowest id survives — the same
            // choice the 5->6 migration made.
            val survivor = members.first()
            for (loser in members.drop(1)) {
                maintenanceDao.mergeInto(from = loser.artistId, to = survivor.artistId)
                artistDao.delete(loser.artistId)
                merged++
            }
            // Bring the stored key in line with current normalisation so the
            // unique index reflects the same rules the app uses.
            if (survivor.displayNameNormalized != key) {
                artistDao.rename(survivor.artistId, survivor.displayName, key)
            }
        }
        return merged
    }

    /**
     * Breaks composite credits into real artists.
     *
     * @return how many composites were split, and how many featured links added
     */
    private suspend fun splitComposites(): Pair<Int, Int> {
        val artists = artistDao.listAll()
        val knownKeys = artists.map { normalizeArtistName(it.displayName) }.toMutableSet()
        val idByKey = artists.associateBy({ normalizeArtistName(it.displayName) }, { it.artistId })
            .toMutableMap()
        val recurring = recurringComponents(artists)

        var split = 0
        var featuredLinks = 0

        for (artist in artists) {
            val ownKey = normalizeArtistName(artist.displayName)
            // An artist may not vouch for itself.
            val credit: ArtistCredit = ArtistCreditParser.parse(
                raw = artist.displayName,
                isKnownArtist = { candidate ->
                    val key = normalizeArtistName(candidate)
                    key != ownKey && key in knownKeys
                },
                isRecurringComponent = { candidate ->
                    normalizeArtistName(candidate) in recurring
                }
            ) ?: continue

            if (!credit.isComposite) continue

            val primaryKey = normalizeArtistName(credit.primary)
            if (primaryKey.isEmpty() || primaryKey == ownKey) continue

            val tracks = maintenanceDao.trackIdsFor(artist.artistId)
            val now = System.currentTimeMillis()

            val primaryId = idByKey[primaryKey] ?: createArtist(credit.primary, now).also {
                idByKey[primaryKey] = it
                knownKeys += primaryKey
            }

            // Hand every track over to the real lead, then retire the composite.
            maintenanceDao.mergeInto(from = artist.artistId, to = primaryId)
            artistDao.delete(artist.artistId)
            knownKeys -= ownKey
            idByKey.remove(ownKey)
            split++

            for (featuredName in credit.featured) {
                val key = normalizeArtistName(featuredName)
                if (key.isEmpty() || key == primaryKey) continue
                val featuredId = idByKey[key] ?: createArtist(featuredName, now).also {
                    idByKey[key] = it
                    knownKeys += key
                }
                for (trackId in tracks) {
                    relDao.upsertTrackArtist(
                        TrackArtistCrossRef(
                            trackId = trackId,
                            artistId = featuredId,
                            role = ArtistRole.FEATURED,
                            source = MetaSource.FILE_TAG,
                            // Derived by splitting a string after the fact.
                            confidence = Confidence.STRONG,
                            createdAt = now
                        )
                    )
                    featuredLinks++
                }
            }
        }
        return split to featuredLinks
    }

    /**
     * Components that turn up in more than one differently-named credit.
     *
     * This is the evidence the lead-must-be-known rule cannot produce. On a
     * soundtrack every performer appears only inside a composite credit, so
     * nobody is ever standalone and no credit ever splits — the library ends up
     * with a row per collaboration instead of a row per artist.
     *
     * Counting *artist rows* rather than tracks is what makes it safe. A band
     * whose name contains a comma is one row however many tracks it has, so its
     * pieces score 1 and stay put; a token only reaches [MIN_RECURRENCE] by
     * appearing in two credits that are otherwise different, which a real band
     * name essentially never does. Protected names are excluded from the count
     * entirely, so "Earth, Wind & Fire" cannot lend weight to splitting anything.
     */
    private fun recurringComponents(artists: List<ArtistEntity>): Set<String> {
        val counts = mutableMapOf<String, Int>()
        for (artist in artists) {
            ArtistCreditParser.candidateComponents(artist.displayName)
                .map { normalizeArtistName(it) }
                .filter { it.isNotEmpty() }
                .toSet() // once per row, so a repeated token in one credit is not two votes
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.filterValues { it >= MIN_RECURRENCE }.keys
    }

    private suspend fun createArtist(name: String, now: Long): Long {
        val key = normalizeArtistName(name)
        artistDao.findByNorm(key)?.let { return it.artistId }
        val inserted = artistDao.insert(
            ArtistEntity(displayName = name, createdAt = now, lastSeenAt = now)
        )
        return if (inserted == -1L) artistDao.findByNorm(key)!!.artistId else inserted
    }

    private companion object {
        /**
         * How many distinct credits a component must appear in to count.
         *
         * Two is deliberately low. The signal is strong because it counts rows,
         * not tracks: a legitimate comma-containing band name is a single row no
         * matter how large its discography, so it cannot reach two on its own.
         */
        const val MIN_RECURRENCE = 2
    }
}

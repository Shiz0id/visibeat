package com.visibeat.musicbrainz

import android.content.Context
import androidx.work.*
import com.visibeat.coredb.DateGranularity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for background release date enrichment.
 * Uses EnrichmentService singleton for database access.
 */
class ReleaseDateEnrichmentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!EnrichmentService.isInitialized()) {
                // Service not initialized, retry later
                return@withContext Result.retry()
            }
            
            // Sized to the real cost, not an imagined one. MusicBrainz allows a
            // request a second and the client honours that, so a 300-release
            // library is about five minutes of network — not the week that
            // fifty-per-day implies. The old budget was not caution, it was an
            // estimate nobody had checked.
            // Budgeted by time, not by count. Every request is a second on a
            // rate-limited API, so "400 releases" is really "400 seconds" — and
            // a worker that overruns its execution window is killed with its
            // remaining work unreported. Doing less and asking to be run again
            // finishes sooner than being killed and retried from a backoff.
            EnrichmentService.batchSize = BATCH_SIZE
            val enrichedCount = EnrichmentService.enrichBatch()

            // Genres need an MBID, which the pass above is what produces, so
            // this runs second and picks up everything matched so far.
            val genreCount = EnrichmentService.enrichGenresBatch(GENRE_BATCH_SIZE)
            val artistGenreCount = EnrichmentService.enrichArtistGenresBatch(ARTIST_GENRE_BATCH)
            val remaining = EnrichmentService.remainingWork()

            android.util.Log.i(
                "MusicBrainz",
                "Enriched $enrichedCount releases, genres for $genreCount"
            )

            val data = workDataOf(
                "enriched" to enrichedCount,
                "genres" to genreCount,
                "artistGenres" to artistGenreCount,
                "remaining" to remaining
            )
            // More to do: come back rather than leaving a half-done library
            // looking finished.
            if (remaining > 0) Result.retry() else Result.success(data)
        } catch (e: Exception) {
            android.util.Log.e("MusicBrainz", "Enrichment failed", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "release_date_enrichment"

        /**
         * Releases matched per run.
         *
         * About two minutes of requests, leaving room inside WorkManager's
         * ten-minute window for the genre pass and for the API being slow.
         */
        const val BATCH_SIZE = 120

        /** Genre lookups per run. One request each, one per second. */
        const val GENRE_BATCH_SIZE = 120

        /** Artists per run. Up to two requests each: a search then a lookup. */
        const val ARTIST_GENRE_BATCH = 40

        /**
         * Schedule periodic enrichment (once per day).
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<ReleaseDateEnrichmentWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        /**
         * Run enrichment immediately (one-time).
         *
         * Uniquely named and KEEP, because a batch is minutes of waiting on a
         * rate-limited donated API and a plain `enqueue` let every tap of the
         * Settings button start another concurrent run against it.
         */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ReleaseDateEnrichmentWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("${WORK_NAME}_now", ExistingWorkPolicy.KEEP, request)
        }
        
        // Helper to convert DateComponents to epoch milliseconds
        fun toEpochMs(dc: DateComponents): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(Calendar.YEAR, dc.year ?: 1970)
            cal.set(Calendar.MONTH, (dc.month ?: 1) - 1)  // Calendar months are 0-indexed
            cal.set(Calendar.DAY_OF_MONTH, dc.day ?: 1)
            return cal.timeInMillis
        }
        
        fun toGranularity(dc: DateComponents): DateGranularity {
            return when {
                dc.hasFullDate -> DateGranularity.DAY
                dc.hasMonthDate -> DateGranularity.MONTH
                dc.year != null -> DateGranularity.YEAR
                else -> DateGranularity.NONE
            }
        }
    }
}

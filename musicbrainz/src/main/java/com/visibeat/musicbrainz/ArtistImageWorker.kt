package com.visibeat.musicbrainz

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background artist portrait lookup.
 *
 * A batch is mostly spent waiting on MusicBrainz's one-request-per-second limit,
 * so this belongs in a worker and never on a screen's coroutine. It is also why
 * a large library fills in over several days of periodic runs rather than all at
 * once — deliberately, since hammering a donated API to populate avatars would
 * be rude.
 */
class ArtistImageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!ArtistImageService.isInitialized()) {
                return@withContext Result.retry()
            }

            // A manual run gets a bigger budget than the background one. Both
            // are bounded: an artist costs a rate-limited MusicBrainz search
            // plus a Wikidata hop, so about a second each, and a run that
            // overruns its execution window is killed with nothing reported.
            val budget = inputData.getInt(KEY_BATCH, DEFAULT_BATCH)
            val found = ArtistImageService.fetchBatch(budget)
            // Bios ride along with portraits: both key off the same artist, both
            // are one-off per artist, and the header needs the text whether or
            // not a photo was found.
            val bios = ArtistImageService.warmBiosBatch(budget)
            val remaining = ArtistImageService.remainingArtists()
            android.util.Log.i(
                LOG_TAG, "found $found portraits, $bios bios; $remaining artists left"
            )

            // Ask to come back rather than reporting done on a batch.
            //
            // A budget per run is right — these are donated APIs and the work is
            // not urgent — but a run that stops at forty and then waits six hours
            // turns a two-minute job into a two-day one. The enrichment worker
            // learned this already; this is the same three lines.
            if (remaining > 0) Result.retry() else Result.success()
        } catch (e: Exception) {
            android.util.Log.e(LOG_TAG, "artist portrait lookup failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val LOG_TAG = "VisiBeatArtistImage"
        const val WORK_NAME = "artist_image_lookup"

        /**
         * Runs every six hours on an unmetered connection.
         *
         * Unmetered because portraits are a cosmetic nicety and not worth
         * anyone's mobile data.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<ArtistImageWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        const val KEY_BATCH = "batch"

        /** Background pass: modest, because nobody is waiting for it. */
        const val DEFAULT_BATCH = 40

        /** Manual pass: somebody is watching, so cover more per round. */
        const val MANUAL_BATCH = 80

        /** Immediate one-off pass, for the button in Settings. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ArtistImageWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_BATCH to MANUAL_BATCH))
                // Linear, not the default exponential. The worker now returns
                // retry to continue rather than to recover, so a doubling delay
                // would punish progress: seven rounds at 30s doubling is half an
                // hour of mostly waiting for work that takes two minutes.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_now",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

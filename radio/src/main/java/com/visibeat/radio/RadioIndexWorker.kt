package com.visibeat.radio

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.visibeat.musicdb.TrackEmbeddingDao
import com.visibeat.musicdb.TrackEmbeddingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/**
 * Everything the worker needs, supplied by the application graph.
 *
 * WorkManager constructs workers itself, so it cannot be given a model that
 * takes tens of megabytes to load. This is set once at startup — the same shape
 * as a WorkerFactory, without dragging in the configuration-provider plumbing
 * for one worker.
 */
object RadioIndexing {
    @Volatile
    var dependencies: Deps? = null

    class Deps(
        val dao: TrackEmbeddingDao,
        val engineProvider: () -> AudioEmbeddingEngine,
        /**
         * Called after each committed batch, so anything holding a loaded copy
         * of the index knows it is behind. Without it, a station started while
         * indexing is running sees only the tracks that existed when the index
         * was last loaded.
         */
        val onBatchCommitted: () -> Unit = {}
    )

    const val WORK_NAME = "visibeat-radio-index"
    const val PROGRESS_DONE = "done"
    const val PROGRESS_TOTAL = "total"

    /**
     * Queues an index run, replacing any in flight.
     *
     * REPLACE rather than KEEP because the trigger is "the library changed" —
     * a run started before an import knows nothing about what the import added,
     * and letting it finish first only delays the run that matters.
     *
     * @param force drop the charging and battery constraints and run now.
     *   The constraints are a sensible default, not a law: they are the app
     *   guessing on the user's behalf, and the guess is wrong often enough —
     *   a slow charger the platform does not report as charging, a desk setup
     *   that is always plugged in, or simply someone who wants it done now.
     *   Deciding to spend your own battery is not a decision the app should be
     *   making for you.
     */
    fun enqueue(context: Context, force: Boolean = false) {
        val constraints = if (force) {
            Constraints.NONE
        } else {
            Constraints.Builder()
                // Decoding thousands of files is a battery cost the user did
                // not ask for, so by default it waits for a charger.
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
        }

        val request = OneTimeWorkRequestBuilder<RadioIndexWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** What the last or current index run is doing, in one line. */
    data class RunStatus(
        val state: String,
        val progress: Int,
        val error: String?,
        /** Terminal — succeeded, failed or cancelled. Nothing more will be written. */
        val isFinished: Boolean
    )

    /**
     * Stops the current run and waits for it to actually stop.
     *
     * The waiting is the point. `cancelUniqueWork` only *requests* cancellation:
     * it returns immediately while the worker is still mid-batch, and a worker
     * mid-batch is still going to commit the rows it has already computed.
     * Deleting the table in that window empties it and then watches the run
     * refill it, which looks exactly like the clear having done nothing.
     *
     * Times out rather than hanging: a worker wedged in a hardware decoder is
     * not worth blocking the UI for, and the delete is still the right thing to
     * do afterwards.
     */
    suspend fun cancelAndAwait(context: Context, timeoutMs: Long = 8_000) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_NAME)
        withTimeoutOrNull(timeoutMs) {
            observeStatus(context).first { it == null || it.isFinished }
        }
    }

    /**
     * The unique work's state, live.
     *
     * A Flow rather than a one-shot read, and that is a correctness fix rather
     * than a nicety. `enqueueUniqueWork` returns before the scheduler has
     * committed anything, so reading the state immediately afterwards — which
     * is exactly what a screen does when it refreshes after a button press —
     * returns the state of the *previous* request. Force-starting a run and
     * then being told "waiting for a charger" is that race, not a failure to
     * force anything.
     *
     * Worth surfacing at all because "nothing is happening" has four causes
     * that look identical from outside: blocked on a constraint, failed on the
     * first tick, never scheduled, or finished with nothing to do.
     */
    fun observeStatus(context: Context): Flow<RunStatus?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                RunStatus(
                    state = info.state.name,
                    progress = info.progress.getInt(PROGRESS_DONE, 0),
                    error = info.outputData.getString("error"),
                    isFinished = info.state.isFinished
                )
            }

    /**
     * One-shot read, for a panel opening cold.
     *
     * Blocking on a future; call from a background dispatcher. Prefer
     * [observeStatus] anywhere the value is shown after an action.
     */
    fun readStatus(context: Context): RunStatus? {
        val infos = try {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()
        } catch (t: Throwable) {
            return null
        }
        val info = infos?.lastOrNull() ?: return null
        return RunStatus(
            state = info.state.name,
            progress = info.progress.getInt(PROGRESS_DONE, 0),
            error = info.outputData.getString("error"),
            isFinished = info.state.isFinished
        )
    }
}

/**
 * Embeds the library in the background, a batch at a time.
 *
 * Runs to a budget rather than to completion. A 5,000-track library is 5,000
 * audio decodes, and decode — not inference — is what makes this slow: a
 * 30-second window is roughly 50-150 ms of hardware codec time, against maybe
 * 10-30 ms for a distilled encoder on the same window. Call it fifteen minutes
 * of continuous work for a first pass. Attempting that in one worker invites
 * the system to kill it and lose everything, so each run does a bounded chunk,
 * commits it, and asks to be run again.
 *
 * Playback is the priority the whole time. The constraints keep it off battery,
 * the batching keeps memory flat, and the codec used here is a separate
 * instance from the player's — but they do contend for the same hardware
 * decoders on some devices, which is why [BATCH_SIZE] is small enough to leave
 * gaps rather than saturating.
 */
class RadioIndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = RadioIndexing.dependencies ?: return Result.failure(
            workDataOf("error" to "RadioIndexing.dependencies not set")
        )

        val engine = try {
            deps.engineProvider()
        } catch (t: Throwable) {
            // No model installed, or it failed to load. Not retryable by
            // waiting — retrying would spin against a missing asset forever.
            return Result.failure(workDataOf("error" to "model unavailable: ${t.message}"))
        }

        val dao = deps.dao
        val modelId = engine.modelId
        val dim = engine.dimension

        return try {
            // Vectors from a previous model are not comparable with new ones and
            // would quietly corrupt every search. Cleared before anything is
            // written, so the index is never a mixture.
            dao.deleteStale(modelId, dim)
            dao.deleteOrphans()

            var processed = 0
            var failures = 0
            var busy = 0

            while (processed < MAX_PER_RUN) {
                currentCoroutineContext().ensureActive()
                if (isStopped) return Result.retry()

                val batch = dao.findUnindexed(modelId, dim, BATCH_SIZE)
                if (batch.isEmpty()) break

                val rows = ArrayList<TrackEmbeddingEntity>(batch.size)
                for (track in batch) {
                    if (isStopped) break
                    val outcome = try {
                        engine.embedTrack(track.uriString, track.durationMs)
                    } catch (e: EmbeddingException) {
                        // The model itself is broken. Every remaining track will
                        // fail the same way, so stop rather than burn the
                        // battery proving it 5,000 more times.
                        return Result.failure(workDataOf("error" to "inference: ${e.message}"))
                    }

                    if (outcome is EmbedOutcome.Busy) {
                        // A hardware decoder in use elsewhere — almost always
                        // the user playing music while this runs. Nothing is
                        // written: the track is fine and deserves another go.
                        busy++
                        if (busy >= MAX_BUSY_BEFORE_YIELD) {
                            // The player has the codec and is going to keep it.
                            // Committing what is done and standing down beats
                            // grinding through the rest of the library failing.
                            if (rows.isNotEmpty()) {
                                dao.upsert(rows)
                                deps.onBatchCommitted()
                            }
                            return Result.retry()
                        }
                        continue
                    }

                    val vector = (outcome as? EmbedOutcome.Success)?.vector
                    if (vector == null) {
                        // Unreadable: a deleted file behind a stale MediaStore
                        // row, a format with no decoder, a truncated download.
                        //
                        // Recorded as a tombstone — same identity, empty blob —
                        // rather than skipped silently. Skipping leaves no row,
                        // and no row means `findUnindexed` hands the same file
                        // back at the top of the very next run, forever. A
                        // library with fifty broken files would spend the first
                        // minute of every run failing to read the same fifty.
                        //
                        // The empty blob is what marks it: the index drops rows
                        // that are not exactly `dim` floats, and the counts test
                        // the length, so a tombstone is never mistaken for a
                        // vector.
                        failures++
                        rows += TrackEmbeddingEntity(
                            trackId = track.trackId,
                            modelId = modelId,
                            dim = dim,
                            vector = ByteArray(0),
                            artistId = track.artistId,
                            albumId = track.albumId,
                            computedAt = System.currentTimeMillis()
                        )
                        continue
                    }

                    rows += TrackEmbeddingEntity(
                        trackId = track.trackId,
                        modelId = modelId,
                        dim = dim,
                        vector = TrackEmbeddingEntity.pack(vector),
                        artistId = track.artistId,
                        albumId = track.albumId,
                        computedAt = System.currentTimeMillis()
                    )
                }

                // Committed per batch, not per run. A worker killed mid-pass
                // keeps everything it finished.
                if (rows.isNotEmpty()) {
                    dao.upsert(rows)
                    deps.onBatchCommitted()
                }

                processed += batch.size
                setProgress(
                    Data.Builder()
                        .putInt(RadioIndexing.PROGRESS_DONE, processed)
                        .putInt(RadioIndexing.PROGRESS_TOTAL, batch.size)
                        .build()
                )

                // Tombstones mean a batch always writes something, so the
                // next query cannot return the same batch. No loop to break.
            }

            val remaining = dao.findUnindexed(modelId, dim, 1).size
            if (remaining > 0) Result.retry() else Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    private companion object {
        /** Tracks per database commit. Small enough that memory stays flat. */
        const val BATCH_SIZE = 25

        /**
         * Ceiling per run, so a worker finishes well inside its execution window
         * and hands back to the scheduler rather than being killed.
         */
        const val MAX_PER_RUN = 400

        /**
         * Consecutive "codec busy" results before the run gives up for now.
         *
         * Contention is not random: if the player holds the one hardware decoder
         * for a format, it holds it for the length of a song. A handful of
         * failures is a coincidence; ten in a row is the device telling you it
         * is doing something else.
         */
        const val MAX_BUSY_BEFORE_YIELD = 10


    }
}

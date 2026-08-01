package com.visibeat.ingest

import com.visibeat.musicdb.ReleaseDao
import com.visibeat.viewengine.ReleaseResolver
import com.visibeat.viewengine.ResolvedCacheDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility to backfill the resolved release cache.
 * In a real app, this might be a WorkManager worker.
 * For this toy app, we'll provide a simple coroutine-based utility.
 */
class ResolvedReleaseBackfillWorker(
    private val releaseDao: ReleaseDao,
    private val resolvedDao: ResolvedCacheDao,
    private val releaseResolver: ReleaseResolver
) {
    suspend fun runBackfill() = withContext(Dispatchers.IO) {
        val allIds = releaseDao.listAllIds()
        allIds.forEach { id ->
            val resolved = releaseResolver.resolveRelease(id)
            resolvedDao.upsertResolvedRelease(resolved)
        }
    }
}

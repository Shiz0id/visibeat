package com.visibeat.musicui.timeline.shared

import com.visibeat.viewengine.TimelineBucket
import java.util.*

/**
 * Utility for timeline zoom operations.
 * Handles bucket transitions and focus epoch calculations.
 */
object TimelineZoom {

    /**
     * Zoom in (finer granularity): YEAR → MONTH → DAY
     * Returns same bucket if already at finest level.
     */
    fun zoomIn(from: TimelineBucket): TimelineBucket = when (from) {
        TimelineBucket.YEAR -> TimelineBucket.MONTH
        TimelineBucket.MONTH -> TimelineBucket.DAY
        TimelineBucket.DAY -> TimelineBucket.DAY // Already finest
    }

    /**
     * Zoom out (coarser granularity): DAY → MONTH → YEAR
     * Returns same bucket if already at coarsest level.
     */
    fun zoomOut(from: TimelineBucket): TimelineBucket = when (from) {
        TimelineBucket.DAY -> TimelineBucket.MONTH
        TimelineBucket.MONTH -> TimelineBucket.YEAR
        TimelineBucket.YEAR -> TimelineBucket.YEAR // Already coarsest
    }

    /**
     * Check if we can zoom in further.
     */
    fun canZoomIn(bucket: TimelineBucket): Boolean = bucket != TimelineBucket.DAY

    /**
     * Check if we can zoom out further.
     */
    fun canZoomOut(bucket: TimelineBucket): Boolean = bucket != TimelineBucket.YEAR

    /**
     * Index of the bucket sitting closest to [focusEpochMs].
     *
     * Changing granularity used to dump the user back at the top of the
     * timeline, which made pinch-to-zoom useless for anything but the most
     * recent year. Feeding the last visible anchor through here lets the new
     * granularity land on the same point in time.
     *
     * [bucketStarts] is the list in display order — the caller does not have to
     * sort it, and both ascending and descending timelines work.
     *
     * Returns -1 for an empty list.
     */
    fun nearestBucketIndex(bucketStarts: List<Long>, focusEpochMs: Long): Int {
        if (bucketStarts.isEmpty()) return -1
        var bestIndex = 0
        var bestDistance = Long.MAX_VALUE
        bucketStarts.forEachIndexed { index, start ->
            val distance = if (start > focusEpochMs) start - focusEpochMs else focusEpochMs - start
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    /**
     * Where to scroll after a granularity change, or null to keep waiting.
     *
     * The subtle part is [dataBucket] vs [wantBucket]. Changing granularity flips
     * the query one recomposition before the database emits the matching buckets,
     * so for a moment the screen is asking for days while still holding years. An
     * anchor spent in that moment scrolls to the right year in the *old* list and
     * then has nothing left for the real one — which is precisely how zooming in
     * on 1985 used to dump you back at the top.
     *
     * Returning null means "not yet": the caller keeps the pending focus and tries
     * again on the next emission.
     */
    fun anchorIndex(
        dataBucket: TimelineBucket,
        wantBucket: TimelineBucket,
        bucketStarts: List<Long>,
        focusEpochMs: Long?
    ): Int? {
        if (focusEpochMs == null) return null
        if (dataBucket != wantBucket) return null
        if (bucketStarts.isEmpty()) return null
        return nearestBucketIndex(bucketStarts, focusEpochMs)
    }

    /**
     * Compute the month bucket start for a given epoch.
     * Used to find the correct scroll position after zoom.
     */
    fun computeMonthStart(epochMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Compute the year bucket start for a given epoch.
     */
    fun computeYearStart(epochMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Compute the day bucket start for a given epoch.
     */
    fun computeDayStart(epochMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

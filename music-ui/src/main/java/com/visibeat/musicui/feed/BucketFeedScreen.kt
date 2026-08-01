package com.visibeat.musicui.feed

import androidx.compose.runtime.Composable
import com.visibeat.viewengine.*
import com.visibeat.musicui.feed.day.DayFeedView
import com.visibeat.musicui.feed.month.MonthFeedView
import com.visibeat.musicui.feed.year.YearFeedView

/**
 * Feed screen router - Delegates to the appropriate feed view based on bucket type.
 * This is a thin router with NO bucket-specific logic.
 *
 * Playback reaches the views through LocalPlayback rather than callbacks, so the
 * router only carries navigation.
 */
@Composable
fun BucketFeedScreen(
    queryEngine: FeedQueryEngine,
    bucketStartEpochMs: Long,
    bucketEndEpochMs: Long,
    baseQuery: ViewQuery,
    onBack: () -> Unit,
    onOpenArtist: (artistId: Long) -> Unit
) {
    when (baseQuery.bucket) {
        TimelineBucket.DAY -> DayFeedView(
            queryEngine = queryEngine,
            bucketStartEpochMs = bucketStartEpochMs,
            bucketEndEpochMs = bucketEndEpochMs,
            baseQuery = baseQuery,
            onBack = onBack,
            onOpenArtist = onOpenArtist
        )
        TimelineBucket.YEAR -> YearFeedView(
            queryEngine = queryEngine,
            bucketStartEpochMs = bucketStartEpochMs,
            bucketEndEpochMs = bucketEndEpochMs,
            baseQuery = baseQuery,
            onBack = onBack,
            onOpenArtist = onOpenArtist
        )
        TimelineBucket.MONTH -> MonthFeedView(
            queryEngine = queryEngine,
            bucketStartEpochMs = bucketStartEpochMs,
            bucketEndEpochMs = bucketEndEpochMs,
            baseQuery = baseQuery,
            onBack = onBack,
            onOpenArtist = onOpenArtist
        )
    }
}

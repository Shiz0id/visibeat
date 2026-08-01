package com.visibeat.viewengine

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.Immutable

/**
 * Buckets, and the granularity they were computed for.
 *
 * Kept together because a caller acting on the rows almost always needs to know
 * whether they answer the question it is currently asking. Changing granularity
 * flips the query immediately but the rows arrive a beat later, so for one frame
 * a bare `List<TimelineBucketRow>` is years while the screen believes it is
 * looking at days — and nothing in the type says so.
 */
@Immutable
data class TimelineBuckets(
    val bucket: TimelineBucket,
    val rows: List<TimelineBucketRow>
) {
    companion object {
        val EMPTY = TimelineBuckets(TimelineBucket.MONTH, emptyList())
    }
}

/**
 * The timeline's query and its results, held for the app rather than for a screen.
 *
 * Two problems, one shape. The activity used to collect
 * `observeBuckets(timelineQuery)` at the root of `setContent`, which meant the
 * bucket `GROUP BY` ran — and re-ran on every database change — the whole time
 * the user was on Home, Search or Library, and its results were read in the root
 * composition scope so every new emission invalidated the entire tree. Simply
 * moving the collector inside the Timeline branch fixes that but reintroduces
 * the other problem: the query would then restart from scratch on every visit.
 *
 * So the query lives here, `flatMapLatest` re-runs it when the user changes
 * granularity, sort or filters, and [SharingStarted.WhileSubscribed] keeps it
 * warm briefly after the screen goes away. Leaving the timeline and coming
 * straight back replays the last value; wandering off for a minute lets it stop.
 *
 * Holding the query here also means granularity and sort survive navigation
 * without the activity having to remember them.
 */
@Stable
class TimelineFeed(
    private val engine: TimelineQueryEngine,
    scope: CoroutineScope
) {
    private val _query = MutableStateFlow(
        ViewQuery(bucket = TimelineBucket.MONTH, sort = SortDirection.DESC)
    )
    val query: StateFlow<ViewQuery> = _query.asStateFlow()

    fun setQuery(next: ViewQuery) {
        _query.value = next
    }

    /** How many tracks have no release date, and so cannot appear above. */
    val undatedCount: StateFlow<Int> =
        engine.observeUndatedTrackCount()
            .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_ALIVE_MS), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val buckets: StateFlow<TimelineBuckets> =
        _query
            .flatMapLatest { q -> engine.observeBuckets(q).map { TimelineBuckets(q.bucket, it) } }
            .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_ALIVE_MS), TimelineBuckets.EMPTY)

    private companion object {
        /** Matches LibraryFeeds: long enough to cover a there-and-back. */
        const val KEEP_ALIVE_MS = 5_000L
    }
}

@file:OptIn(ExperimentalFoundationApi::class)
package com.visibeat.musicui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.visibeat.viewengine.*
import com.visibeat.musicui.design.*
import com.visibeat.musicui.feed.shared.DateQuality
import com.visibeat.musicui.timeline.shared.EmptyTimelineState
import com.visibeat.musicui.timeline.shared.TimelineZoom
import com.visibeat.musicui.timeline.day.DayTimelineView
import com.visibeat.musicui.timeline.month.MonthTimelineView
import com.visibeat.musicui.timeline.year.YearTimelineView
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import com.visibeat.musicui.timeline.shared.YearMonthLabel
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Main timeline screen - Routes to the appropriate view based on bucket type.
 * This is a thin router with NO bucket-specific logic.
 *
 * It owns three things beyond routing: the filter bar, the pinch-to-zoom
 * gesture, and the scroll anchor that survives a granularity change.
 */
@Composable
fun TimelineBucketsScreen(
    queryEngine: TimelineQueryEngine,
    query: ViewQuery,
    /** Rows plus the granularity they answer for — see [TimelineBuckets]. */
    bucketData: TimelineBuckets,
    onQueryChange: (ViewQuery) -> Unit,
    onOpenFeedForBucket: (bucketStartEpochMs: Long, bucketEndEpochMs: Long, query: ViewQuery) -> Unit,
    onOpenArtist: (artistId: Long) -> Unit,
    /**
     * Opens the album page proper. Branch rows used to route this through the
     * feed screen with a releaseId filter, which is a list of one album's tracks
     * pretending to be an album.
     */
    onOpenAlbum: (releaseId: Long) -> Unit,
    onIngest: () -> Unit = {},
    /**
     * Tracks with no release date. They cannot appear on a date axis, so the
     * only honest thing to do is say how many are missing from it.
     */
    undatedCount: Int = 0,
    onVisibleBucketChange: (bucketStartEpochMs: Long) -> Unit = {},
    /**
     * Bumped by the host whenever the library is rewritten under us — a scan, a
     * folder import, a database rebuild.
     *
     * The bucket list itself is a Room Flow and refreshes on its own, but the
     * per-bucket preview rows are fetched once and cached by bucket start, so
     * without this a bucket that already existed kept showing the tracks it held
     * before the scan.
     */
    refreshKey: Int = 0,
    listState: LazyListState = rememberLazyListState()
) {
    AntigravityTheme {
        val scope = rememberCoroutineScope()
        val buckets = bucketData.rows

        // Track visible bucket from child views
        var visibleBucketEpoch by remember { mutableStateOf(0L) }

        /**
         * Where the timeline should re-centre once the next granularity's
         * buckets arrive. Set on any granularity change, cleared once honoured.
         */
        var pendingFocusEpoch by remember { mutableStateOf<Long?>(null) }

        var filtersExpanded by remember { mutableStateOf(false) }

        // Debounce zoom gestures to prevent rapid triggering
        var lastZoomTime by remember { mutableStateOf(0L) }
        val zoomDebounceMs = 400L

        // Visual feedback during pinch gesture
        var liveZoomRatio by remember { mutableFloatStateOf(1f) }
        var thresholdFlash by remember { mutableStateOf(false) }

        // Animated scale that tracks the pinch ratio with spring physics
        val animatedScale by animateFloatAsState(
            targetValue = liveZoomRatio.coerceIn(0.8f, 1.2f),
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
            label = "pinchScale"
        )

        // Flash opacity that fades out quickly
        val flashAlpha by animateFloatAsState(
            targetValue = if (thresholdFlash) 0.05f else 0f,
            animationSpec = tween(durationMillis = 150),
            finishedListener = { thresholdFlash = false },
            label = "flashAlpha"
        )

        /** Change granularity while remembering where the user was looking. */
        fun changeBucket(next: TimelineBucket) {
            if (next == query.bucket) return
            pendingFocusEpoch = visibleBucketEpoch.takeIf { it > 0L }
                ?: buckets.firstOrNull()?.bucketStartEpochMs
            onQueryChange(query.copy(bucket = next))
        }

        // Re-anchor once — and only once — the new granularity's buckets land.
        LaunchedEffect(bucketData, query.bucket) {
            val index = TimelineZoom.anchorIndex(
                dataBucket = bucketData.bucket,
                wantBucket = query.bucket,
                bucketStarts = buckets.map { it.bucketStartEpochMs },
                focusEpochMs = pendingFocusEpoch
            ) ?: return@LaunchedEffect
            listState.scrollToItem(index)
            pendingFocusEpoch = null
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                    }
                    .pointerInput(query.bucket) {
                        awaitEachGesture {
                            // Wait for first pointer down
                            awaitFirstDown(pass = PointerEventPass.Initial)

                            var initialDistance = 0f
                            var pinchActive = false

                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointers = event.changes.filter { it.pressed }

                                if (pointers.size >= 2) {
                                    // Two fingers - calculate distance
                                    val p1 = pointers[0].position
                                    val p2 = pointers[1].position
                                    val dx = p2.x - p1.x
                                    val dy = p2.y - p1.y
                                    val currentDistance = sqrt(dx * dx + dy * dy)

                                    if (!pinchActive) {
                                        // Start of pinch
                                        initialDistance = currentDistance
                                        pinchActive = true
                                    } else {
                                        // During pinch - calculate zoom ratio and update visual
                                        val zoomRatio = currentDistance / initialDistance
                                        liveZoomRatio = zoomRatio
                                        val now = System.currentTimeMillis()

                                        if (now - lastZoomTime > zoomDebounceMs) {
                                            when {
                                                // Pinch in (fingers together) - zoom out
                                                zoomRatio < 0.6f && TimelineZoom.canZoomOut(query.bucket) -> {
                                                    lastZoomTime = now
                                                    thresholdFlash = true
                                                    liveZoomRatio = 1f
                                                    changeBucket(TimelineZoom.zoomOut(query.bucket))
                                                    pointers.forEach { it.consume() }
                                                }
                                                // Pinch out (fingers apart) - zoom in
                                                zoomRatio > 1.5f && TimelineZoom.canZoomIn(query.bucket) -> {
                                                    lastZoomTime = now
                                                    thresholdFlash = true
                                                    liveZoomRatio = 1f
                                                    changeBucket(TimelineZoom.zoomIn(query.bucket))
                                                    pointers.forEach { it.consume() }
                                                }
                                            }
                                        }
                                    }
                                    // Consume two-finger events to prevent scroll
                                    pointers.forEach { it.consume() }
                                } else {
                                    if (pinchActive) {
                                        // Spring back to 1.0 when fingers lift
                                        liveZoomRatio = 1f
                                    }
                                    pinchActive = false
                                }
                            } while (event.changes.any { it.pressed })
                            // Ensure reset when gesture ends
                            liveZoomRatio = 1f
                        }
                    }
            ) {
                TimelineControlBar(
                    query = query,
                    bucketCount = buckets.size,
                    trackCount = buckets.sumOf { it.itemCount },
                    undatedCount = undatedCount,
                    filtersExpanded = filtersExpanded,
                    onToggleFilters = { filtersExpanded = !filtersExpanded },
                    onBucketChange = ::changeBucket,
                    onQueryChange = onQueryChange,
                    onScrollToTop = { scope.launch { listState.animateScrollToItem(0) } }
                )

                HorizontalDivider(color = AgPalette.GlassWhite)

                if (buckets.isEmpty()) {
                    EmptyTimelineState(
                        hasFilters = query.releaseDateQuality.isNotEmpty(),
                        onClearFilters = { onQueryChange(query.copy(releaseDateQuality = emptySet())) },
                        onIngest = onIngest,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    val reportVisible: (Long) -> Unit = { epoch ->
                        visibleBucketEpoch = epoch
                        onVisibleBucketChange(epoch)
                    }

                    // Route to the appropriate view based on bucket type
                    when (query.bucket) {
                        TimelineBucket.DAY -> DayTimelineView(
                            listState = listState,
                            buckets = buckets,
                            query = query,
                            queryEngine = queryEngine,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                            onVisibleBucketChange = reportVisible,
                            refreshKey = refreshKey
                        )
                        TimelineBucket.YEAR -> YearTimelineView(
                            listState = listState,
                            buckets = buckets,
                            query = query,
                            queryEngine = queryEngine,
                            onOpenAlbum = onOpenAlbum,
                            onVisibleBucketChange = reportVisible,
                            refreshKey = refreshKey
                        )
                        TimelineBucket.MONTH -> MonthTimelineView(
                            listState = listState,
                            buckets = buckets,
                            query = query,
                            queryEngine = queryEngine,
                            onOpenFeedForBucket = onOpenFeedForBucket,
                            onOpenAlbum = onOpenAlbum,
                            onVisibleBucketChange = reportVisible,
                            refreshKey = refreshKey
                        )
                    }
                }
            }

            // Flash overlay when zoom threshold is crossed
            if (flashAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = flashAlpha))
                )
            }
        }
    }
}

// -----------------------------------------------------
// Control bar
// -----------------------------------------------------
/**
 * Granularity, sort, and date-confidence filters.
 *
 * The alpha shipped three chips: one that cycled MONTH → DAY → YEAR (hiding two
 * of the three choices at any moment and skipping the natural ordering), one
 * sort toggle, and a "Quality: Any" chip whose only action was to clear a filter
 * you had no way to set. All three are real controls now.
 */
@Composable
private fun TimelineControlBar(
    query: ViewQuery,
    bucketCount: Int,
    trackCount: Int,
    undatedCount: Int,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
    onBucketChange: (TimelineBucket) -> Unit,
    onQueryChange: (ViewQuery) -> Unit,
    onScrollToTop: () -> Unit
) {
    val accent = LocalWallpaperAccent.current
    val hasFilters = query.releaseDateQuality.isNotEmpty()

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .agPressable(onClick = onScrollToTop, pressScale = 0.98f)
            ) {
                Text(
                    text = "Timeline",
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    color = AgPalette.TextPrimary
                )
                Text(
                    text = "$bucketCount ${query.bucket.plural()} · $trackCount tracks",
                    fontFamily = NunitoFamily,
                    fontSize = 12.sp,
                    color = AgPalette.TextMetadata
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AgSegmentedControl(
                options = BUCKET_OPTIONS,
                selected = query.bucket,
                onSelect = onBucketChange,
                label = { it.label() }
            )

            Spacer(Modifier.weight(1f))

            AgIconButton(
                icon = if (query.sort == SortDirection.DESC) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = if (query.sort == SortDirection.DESC) "Newest first" else "Oldest first",
                onClick = {
                    onQueryChange(
                        query.copy(
                            sort = if (query.sort == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC
                        )
                    )
                },
                size = 34.dp,
                iconSize = 18.dp
            )

            AgIconButton(
                icon = Icons.Default.FilterList,
                contentDescription = "Filter by date confidence",
                onClick = onToggleFilters,
                size = 34.dp,
                iconSize = 18.dp,
                tint = if (hasFilters) accent else AgPalette.TextPrimary,
                opacity = if (hasFilters || filtersExpanded) 0.20f else 0.12f
            )
        }

        AnimatedVisibility(
            visible = filtersExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    text = "How sure are we about these release dates?",
                    fontFamily = NunitoFamily,
                    fontSize = 11.sp,
                    color = AgPalette.TextMetadata,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AgChip(
                            label = "All",
                            selected = !hasFilters,
                            onClick = { onQueryChange(query.copy(releaseDateQuality = emptySet())) }
                        )
                    }
                    items(DateQuality.entries) { quality ->
                        AgChip(
                            label = quality.label,
                            selected = query.releaseDateQuality == quality.filters,
                            onClick = {
                                val next = if (query.releaseDateQuality == quality.filters) {
                                    emptySet()
                                } else {
                                    quality.filters
                                }
                                onQueryChange(query.copy(releaseDateQuality = next))
                            }
                        )
                    }
                }
                if (undatedCount > 0) {
                    Text(
                        text = "$undatedCount ${if (undatedCount == 1) "track has" else "tracks have"} " +
                            "no release date, so ${if (undatedCount == 1) "it is" else "they are"} " +
                            "not on the timeline. Edit a track to give it one.",
                        fontFamily = NunitoFamily,
                        fontSize = 11.sp,
                        color = AgPalette.TextMetadata,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private val BUCKET_OPTIONS = listOf(
    TimelineBucket.YEAR,
    TimelineBucket.MONTH,
    TimelineBucket.DAY
)

private fun TimelineBucket.label(): String = when (this) {
    TimelineBucket.YEAR -> "Years"
    TimelineBucket.MONTH -> "Months"
    TimelineBucket.DAY -> "Days"
}

private fun TimelineBucket.plural(): String = when (this) {
    TimelineBucket.YEAR -> "years"
    TimelineBucket.MONTH -> "months"
    TimelineBucket.DAY -> "days"
}


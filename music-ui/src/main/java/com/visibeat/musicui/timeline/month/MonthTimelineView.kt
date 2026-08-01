@file:OptIn(ExperimentalFoundationApi::class)
package com.visibeat.musicui.timeline.month

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.visibeat.viewengine.*
import com.visibeat.musicui.design.*
import com.visibeat.musicui.timeline.shared.*
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.timeline.day.MAX_CACHED_BUCKETS

/**
 * MONTH view - Self-contained timeline view for monthly buckets.
 * Features expandable album grids with long-press interaction.
 */

@Composable
fun MonthTimelineView(
    listState: LazyListState,
    buckets: List<TimelineBucketRow>,
    query: ViewQuery,
    queryEngine: TimelineQueryEngine,
    onOpenFeedForBucket: (bucketStartEpochMs: Long, bucketEndEpochMs: Long, query: ViewQuery) -> Unit,
    onOpenAlbum: (releaseId: Long) -> Unit,
    onVisibleBucketChange: (bucketStartEpochMs: Long) -> Unit = {},
    /** Bumped by the host when the library is rewritten; invalidates cached previews. */
    refreshKey: Int = 0
) {
    // Cache previews by bucketStart — clear when query filters change
    val previews = remember { mutableStateMapOf<Long, List<TimelineItemRow>>() }
    LaunchedEffect(query.artistId, query.releaseId, query.genreContains, query.releaseDateQuality, refreshKey) {
        previews.clear()
    }

    // What is opened, and what has been loaded for it.
    //
    // Long-press used to grow a bucket's album grid inside its own card and stop
    // there — the expansion was only ever half built. It now opens the bucket as
    // a branch of the tree: its releases hang below it on the spine, and opening
    // one of those hangs its tracks below that.
    val playback = LocalPlayback.current
    var tree by remember { mutableStateOf(TimelineTreeState()) }
    LaunchedEffect(query.artistId, query.releaseId, query.genreContains, query.releaseDateQuality, refreshKey) {
        tree = TimelineTreeState()
    }

    val rows = remember(buckets, tree) { buildTimelineTree(buckets, tree) }

    // Report visible bucket when scroll position changes. Counted over bucket
    // rows only, so opening one does not make the timeline think it scrolled.
    LaunchedEffect(listState.firstVisibleItemIndex, rows) {
        (rows.getOrNull(listState.firstVisibleItemIndex) as? TimelineTreeRow.Bucket)
            ?.let { onVisibleBucketChange(it.bucket.bucketStartEpochMs) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = 14.dp, bottom = 160.dp)
        ) {
            items(items = rows, key = { it.key }) { row ->
                when (row) {
                    is TimelineTreeRow.Bucket -> {
                        val bucket = row.bucket
                        val index = row.bucketIndex
                        val yearMonthLabel = remember(bucket.bucketStartEpochMs) {
                            YearMonthLabel.fromBucketStart(bucket.bucketStartEpochMs)
                        }

                        // Prefetch the collage. Partial by design — the honest
                        // full list is what expanding the bucket loads.
                        LaunchedEffect(bucket.bucketStartEpochMs, query.artistId, query.releaseId, query.genreContains, query.releaseDateQuality, refreshKey) {
                            if (!previews.containsKey(bucket.bucketStartEpochMs)) {
                                val items = queryEngine.getBucketPreviewItems(
                                    query.copy(itemsPerBucket = ITEMS_PER_MONTH),
                                    bucket.bucketStartEpochMs
                                )
                                if (previews.size >= MAX_CACHED_BUCKETS) previews.clear()
                                previews[bucket.bucketStartEpochMs] = items
                            }
                        }

                        // Load the real contents once opened, once.
                        LaunchedEffect(row.expanded, bucket.bucketStartEpochMs) {
                            if (row.expanded && tree.albumsByBucket[bucket.bucketStartEpochMs] == null) {
                                val albums = queryEngine.getAlbumsInBucket(query, bucket.bucketStartEpochMs)
                                tree = tree.withAlbums(bucket.bucketStartEpochMs, albums)
                            }
                        }

                        val bucketEnd = remember(bucket.bucketStartEpochMs) {
                            computeBucketEndUtc(bucket.bucketStartEpochMs, TimelineBucket.MONTH)
                        }

                        MonthYearHeaderIfNeeded(
                            year = yearMonthLabel.year,
                            previousYear = remember(buckets, index) {
                                buckets.getOrNull(index - 1)
                                    ?.let { YearMonthLabel.fromBucketStart(it.bucketStartEpochMs).year }
                            },
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )

                MonthBucketRowUI(
                            bucket = bucket,
                            bucketEnd = bucketEnd,
                            label = yearMonthLabel.monthShort,
                            previewItems = previews[bucket.bucketStartEpochMs] ?: emptyList(),
                            side = sideForIndex(index),
                            isLoaded = previews.containsKey(bucket.bucketStartEpochMs),
                            isExpanded = row.expanded,
                            onLongPress = { tree = tree.toggleBucket(bucket.bucketStartEpochMs) },
                            onOpenBucketFeed = { start, end ->
                                onOpenFeedForBucket(start, end, query)
                            },
                            onAlbumClick = onOpenAlbum
                        )
                    }

                    is TimelineTreeRow.Album -> {
                        LaunchedEffect(row.expanded, row.album.releaseId) {
                            if (row.expanded && tree.tracksByAlbum[row.album.releaseId] == null) {
                                val tracks = queryEngine.getAlbumTracks(row.album.releaseId)
                                tree = tree.withTracks(row.album.releaseId, tracks)
                            }
                        }
                        AlbumBranch(
                            album = row.album,
                            expanded = row.expanded,
                            isLast = row.isLastInBucket,
                            onToggle = { tree = tree.toggleAlbum(row.album.releaseId) },
                            onOpenAlbum = { onOpenAlbum(row.album.releaseId) }
                        )
                    }

                    is TimelineTreeRow.Track -> {
                        val siblings = tree.tracksByAlbum[row.releaseId].orEmpty()
                        TrackBranch(
                            track = row.track,
                            isLast = row.isLastInAlbum,
                            onPlay = {
                                val queue = siblings.map { it.toItemRow() }
                                val at = siblings.indexOfFirst { it.trackId == row.track.trackId }
                                playback.playTracks(queue, at.coerceAtLeast(0))
                            }
                        )
                    }

                    is TimelineTreeRow.Loading -> BranchLoading(depth = row.depth)
                }
            }
        }
    }
}

/** Enough rows to fill the collage on a busy month. */
private const val ITEMS_PER_MONTH = 150

// -----------------------------------------------------
// Month bucket row - with expandable album art grid
// -----------------------------------------------------
@Composable
private fun MonthBucketRowUI(
    bucket: TimelineBucketRow,
    bucketEnd: Long,
    label: String,
    previewItems: List<TimelineItemRow>,
    side: Side,
    isLoaded: Boolean,
    isExpanded: Boolean,
    onLongPress: () -> Unit,
    onOpenBucketFeed: (startEpochMs: Long, endEpochMs: Long) -> Unit,
    onAlbumClick: (releaseId: Long) -> Unit
) {
    // Unique releases for this bucket (up to 30 for expansion)
    val uniqueReleases = remember(previewItems) {
        previewItems
            .filter { it.releaseId != null && it.artModel != null }
            .distinctBy { it.releaseId }
            .take(30)
    }

    val grid: @Composable () -> Unit = {
        ExpandableAlbumGrid(
            releases = uniqueReleases,
            trackCount = bucket.itemCount,
            isLoaded = isLoaded,
            isExpanded = isExpanded,
            onLongPress = onLongPress,
            onTap = { onOpenBucketFeed(bucket.bucketStartEpochMs, bucketEnd) },
            onAlbumClick = onAlbumClick
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side
        if (side == Side.LEFT) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { grid() }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.width(20.dp))

        CenterSpine(
            label = label,
            modifier = Modifier.width(64.dp),
            isExpanded = isExpanded
        )

        Spacer(Modifier.width(20.dp))

        // Right side
        if (side == Side.RIGHT) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { grid() }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

// -----------------------------------------------------
// Expandable Album Grid - 2x2 collapsed, 5x3 expanded
// -----------------------------------------------------
@Composable
private fun ExpandableAlbumGrid(
    releases: List<TimelineItemRow>,
    trackCount: Int,
    isLoaded: Boolean,
    isExpanded: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    onAlbumClick: (releaseId: Long) -> Unit
) {
    // Animation
    val animatedSize by animateDpAsState(
        targetValue = if (isExpanded) 200.dp else 80.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "gridSize"
    )

    val animatedCorner by animateDpAsState(
        targetValue = if (isExpanded) 20.dp else 16.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "cornerRadius"
    )

    // Calculate grid dimensions
    val cols = if (isExpanded) 5 else 2
    val rows = if (isExpanded) 3 else 2
    val maxItems = cols * rows
    val totalPages = (releases.size + maxItems - 1) / maxItems
    val pagerState = rememberPagerState(pageCount = { totalPages })

    AgSurface(
        modifier = Modifier
            .size(animatedSize)
            .agPressable(
                onClick = { if (!isExpanded) onTap() },
                // Not gated on isExpanded: that made expanding one-way, because
                // the gesture that opened a month was disabled the moment it
                // opened and nothing else could close it again.
                onLongClick = onLongPress,
                pressScale = 0.96f
            ),
        shape = RoundedCornerShape(animatedCorner)
        
    ) {
        Box(Modifier.fillMaxSize().padding(6.dp)) {
            if (releases.isEmpty()) {
                // A bucket with no art: say what is in it rather than showing a
                // bare "?" that reads like an error.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!isLoaded) {
                        Text(
                            "…",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AgPalette.TextMetadata
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "$trackCount",
                                fontFamily = NunitoFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 22.sp,
                                color = AgPalette.TextSecondary
                            )
                            Text(
                                if (trackCount == 1) "track" else "tracks",
                                fontFamily = NunitoFamily,
                                fontSize = 10.sp,
                                color = AgPalette.TextMetadata
                            )
                        }
                    }
                }
            } else if (totalPages <= 1) {
                // Single page grid
                AlbumGridContent(
                    releases = releases.take(maxItems),
                    cols = cols,
                    rows = rows,
                    isExpanded = isExpanded,
                    onAlbumClick = onAlbumClick
                )
            } else {
                // Multi-page vertical pager
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val startIdx = page * maxItems
                    val pageReleases = releases.drop(startIdx).take(maxItems)
                    AlbumGridContent(
                        releases = pageReleases,
                        cols = cols,
                        rows = rows,
                        isExpanded = isExpanded,
                        onAlbumClick = onAlbumClick
                    )
                }

                // +N badge (collapsed, more albums than fit)
                if (!isExpanded && releases.size > maxItems) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .agGlass(RoundedCornerShape(999.dp), opacity = 0.15f)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "+${releases.size - maxItems}",
                            fontFamily = NunitoFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Page indicator (only when expanded and multiple pages)
                if (isExpanded && totalPages > 1) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        repeat(totalPages) { i ->
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        if (i == pagerState.currentPage) Color.White 
                                        else Color.White.copy(alpha = 0.3f),
                                        CircleShape
                                    )
                            )
                        }
                    }
                }
            }

        }
    }
}

// -----------------------------------------------------
// Album Grid Content (rows x cols) - SQUARE tiles
// -----------------------------------------------------
@Composable
private fun AlbumGridContent(
    releases: List<TimelineItemRow>,
    cols: Int,
    rows: Int,
    isExpanded: Boolean,
    onAlbumClick: (releaseId: Long) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (col in 0 until cols) {
                    val idx = row * cols + col
                    val release = releases.getOrNull(idx)
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)  // Force square
                            .padding(2.dp)
                    ) {
                        if (release != null) {
                            // A cover takes its own tap whether the grid is open
                            // or not. Gating this on `isExpanded` meant that on a
                            // collapsed card — which is most of them — tapping an
                            // album fell through to the card underneath and
                            // opened the month's feed instead of the album.
                            val releaseId = release.releaseId
                            AsyncImage(
                                model = release.artModel ?: "",
                                contentDescription = release.effectiveAlbumTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .agAlbumTile(RoundedCornerShape(if (isExpanded) 6.dp else 4.dp))
                                    .then(
                                        if (releaseId != null) {
                                            Modifier.agPressable(
                                                onClick = { onAlbumClick(releaseId) },
                                                pressScale = 0.92f
                                            )
                                        } else Modifier
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------
// Year header for MONTH view
// -----------------------------------------------------
@Composable
private fun MonthYearHeaderIfNeeded(
    year: Int,
    previousYear: Int?,
    modifier: Modifier = Modifier
) {
    if (previousYear == null || previousYear != year) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AgTimelineSpine(Modifier.height(30.dp))
                VerticalStackText(
                    text = year.toString(),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                AgTimelineSpine(Modifier.height(30.dp))
            }
        }
    }
}

// -----------------------------------------------------
// Helper to find previous year
// -----------------------------------------------------

@file:OptIn(ExperimentalFoundationApi::class)
package com.visibeat.musicui.timeline.year

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.visibeat.viewengine.*
import com.visibeat.musicui.design.*
import com.visibeat.musicui.timeline.shared.*
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.LocalDragToQueue
import com.visibeat.musicui.playback.QueueDragPayload
import java.util.*

/**
 * YEAR view - Self-contained timeline view for yearly buckets.
 * Shows quarterly layout with Q1-Q4 pagers.
 */

@Composable
fun YearTimelineView(
    listState: LazyListState,
    buckets: List<TimelineBucketRow>,
    query: ViewQuery,
    queryEngine: TimelineQueryEngine,
    onOpenAlbum: (releaseId: Long) -> Unit,
    onVisibleBucketChange: (bucketStartEpochMs: Long) -> Unit = {},
    /** Bumped by the host when the library is rewritten; invalidates cached previews. */
    refreshKey: Int = 0
) {
    // Cached per year: the year's items already split into months 1..12.
    //
    // The alpha fired twelve separate month queries for every year row it
    // composed. One query per year returns the same data — the month split is
    // client-side arithmetic on a field every row already carries.
    val previews = remember { mutableStateMapOf<Int, Map<Int, List<TimelineItemRow>>>() }
    LaunchedEffect(query.artistId, query.releaseId, query.genreContains, query.releaseDateQuality, refreshKey) {
        previews.clear()
    }

    // What is opened, and what has been loaded for it.
    val playback = LocalPlayback.current
    var tree by remember { mutableStateOf(TimelineTreeState()) }
    LaunchedEffect(query.artistId, query.releaseId, query.genreContains, query.releaseDateQuality, refreshKey) {
        tree = TimelineTreeState()
    }

    // Which month of a year the open branch is narrowed to. Absent = whole year.
    var scopes by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    LaunchedEffect(query.artistId, query.releaseId, query.genreContains, query.releaseDateQuality, refreshKey) {
        scopes = emptyMap()
    }

    // The DAO already returns one row per year at this granularity, so the
    // grouping this used to do was regrouping data that was never grouped.
    val rows = remember(buckets, tree) { buildTimelineTree(buckets, tree) }

    LaunchedEffect(listState.firstVisibleItemIndex, rows) {
        (rows.getOrNull(listState.firstVisibleItemIndex) as? TimelineTreeRow.Bucket)
            ?.let { onVisibleBucketChange(it.bucket.bucketStartEpochMs) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 14.dp, bottom = 160.dp)
    ) {
        items(items = rows, key = { it.key }) { row ->
            when (row) {
                is TimelineTreeRow.Bucket -> {
                    val yearStart = row.bucket.bucketStartEpochMs
                    val year = remember(yearStart) { YearMonthLabel.fromBucketStart(yearStart).year }

                    LaunchedEffect(year, query.artistId, query.releaseId, query.genreContains, query.releaseDateQuality, refreshKey) {
                        if (!previews.containsKey(year)) {
                            val items = queryEngine.getBucketPreviewItems(
                                query.copy(bucket = TimelineBucket.YEAR, itemsPerBucket = ITEMS_PER_YEAR),
                                yearStart
                            )
                            previews[year] = items.groupBy { r ->
                                r.effectiveReleaseDateEpochMs?.let { monthOf(it) } ?: 0
                            }
                        }
                    }

                    // What the open branch is showing: the whole year, or one
                    // month of it. Loaded in full either way — the collage above
                    // is a capped preview, this is not, which is what makes a
                    // busy year tell the truth.
                    val scope = scopes[yearStart]
                    LaunchedEffect(row.expanded, scope, yearStart) {
                        if (!row.expanded) return@LaunchedEffect
                        val albums = if (scope == null) {
                            queryEngine.getAlbumsInBucket(query, yearStart)
                        } else {
                            queryEngine.getAlbumsInBucket(
                                query.copy(bucket = TimelineBucket.MONTH),
                                monthStartUtc(year, scope)
                            )
                        }
                        tree = tree.withAlbums(yearStart, albums)
                    }

                    QuarterlyYearBucket(
                        year = year,
                        monthlyData = previews[year] ?: emptyMap(),
                        verticalPadding = 16.dp,
                        isExpanded = row.expanded,
                        scopedMonth = scope,
                        onScopeYear = {
                            scopes = scopes - yearStart
                            // Reopening on the whole year has to reload: the map
                            // still holds whichever month was scoped last.
                            tree = tree.withoutAlbums(yearStart).toggleBucket(yearStart)
                        },
                        onScopeMonth = { month ->
                            val already = row.expanded && scope == month
                            scopes = if (already) scopes - yearStart else scopes + (yearStart to month)
                            tree = tree.withoutAlbums(yearStart).let {
                                if (already || !row.expanded) it.toggleBucket(yearStart) else it
                            }
                        }
                    )
                }

                is TimelineTreeRow.Album -> {
                    LaunchedEffect(row.expanded, row.album.releaseId) {
                        if (row.expanded && tree.tracksByAlbum[row.album.releaseId] == null) {
                            tree = tree.withTracks(
                                row.album.releaseId,
                                queryEngine.getAlbumTracks(row.album.releaseId)
                            )
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

/** Enough rows to fill four quarter cards with art without a query per month. */
private const val ITEMS_PER_YEAR = 240

private fun monthStartUtc(year: Int, month: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, 1, 0, 0, 0)
    return cal.timeInMillis
}

private fun monthOf(epochMs: Long): Int {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epochMs
    return cal.get(Calendar.MONTH) + 1
}

// -----------------------------------------------------
// Quarterly Year Bucket - 4 quarterly stacks per year
// -----------------------------------------------------
@Composable
private fun QuarterlyYearBucket(
    year: Int,
    isExpanded: Boolean,
    /** Which month the open branch is narrowed to, or null for the whole year. */
    scopedMonth: Int?,
    onScopeYear: () -> Unit,
    onScopeMonth: (month: Int) -> Unit,
    monthlyData: Map<Int, List<TimelineItemRow>>,
    verticalPadding: Dp
) {
    val quarters = listOf(
        listOf(1, 2, 3),   // Q1: Jan, Feb, Mar
        listOf(4, 5, 6),   // Q2: Apr, May, Jun
        listOf(7, 8, 9),   // Q3: Jul, Aug, Sep
        listOf(10, 11, 12) // Q4: Oct, Nov, Dec
    )
    val monthNames = listOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    val accent = LocalWallpaperAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tapping the year opens all of it. Tapping a month tile inside
            // narrows the same branch to that month — the tiles claim their own
            // taps, so this only fires on the space between them.
            //
            // No long press. Scoping already has a tap, and a long press that
            // duplicates it only competes with the drag on the covers inside:
            // both timers fire at the same threshold, so grabbing an album was
            // a coin toss against expanding the year underneath it.
            .agPressable(onClick = onScopeYear, pressScale = 0.995f)
            .padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left column: Q1 and Q3 stacked
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuarterCard("Q1", quarters[0], monthNames, monthlyData, scopedMonth, onScopeMonth)
            QuarterCard("Q3", quarters[2], monthNames, monthlyData, scopedMonth, onScopeMonth)
        }

        // Center spine with year label
        Column(
            modifier = Modifier.width(64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AgTimelineSpine(Modifier.height(20.dp))
            // Open years are marked on the spine, where the branch descends from.
            VerticalStackText(
                text = year.toString(),
                modifier = Modifier.padding(vertical = 8.dp),
                fontWeight = if (isExpanded) FontWeight.ExtraBold else FontWeight.Bold,
                color = if (isExpanded) accent else MaterialTheme.colorScheme.onSurface
            )
            AgTimelineSpine(Modifier.height(20.dp))
        }

        // Right column: Q2 and Q4 stacked (offset lower)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuarterCard("Q2", quarters[1], monthNames, monthlyData, scopedMonth, onScopeMonth)
            QuarterCard("Q4", quarters[3], monthNames, monthlyData, scopedMonth, onScopeMonth)
        }
    }
}

// -----------------------------------------------------
// Quarter Card with VerticalPager for 3 months
// -----------------------------------------------------
@Composable
private fun QuarterCard(
    quarterLabel: String,
    months: List<Int>,
    monthNames: List<String>,
    monthlyData: Map<Int, List<TimelineItemRow>>,
    scopedMonth: Int?,
    onScopeMonth: (month: Int) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { months.size })

    AgSurface(
        modifier = Modifier.size(110.dp),
        shape = RoundedCornerShape(16.dp)
        
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val month = months[page]
                val tracks = monthlyData[month] ?: emptyList()
                MonthCardInQuarter(
                    monthName = monthNames[month],
                    tracks = tracks,
                    isScoped = scopedMonth == month,
                    onScope = { onScopeMonth(month) }
                )
            }

            // Quarter label indicator
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = quarterLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // Page indicator dots (vertical)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                months.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                if (index == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                }
            }

        }
    }
}

// -----------------------------------------------------
// Month Card inside Quarter (for YEAR view)
// -----------------------------------------------------
@Composable
private fun MonthCardInQuarter(
    monthName: String,
    tracks: List<TimelineItemRow>,
    /** This month is what the year's open branch is currently showing. */
    isScoped: Boolean,
    onScope: () -> Unit
) {
    // Every distinct release in the month, then the four the tile can hold.
    //
    // This used to filter on `artModel != null` *before* taking four, so a
    // release without a cover was not truncated from the collage - it was
    // deleted from the year entirely. Whether an album existed depended on
    // whether artwork extraction happened to work on it.
    val allAlbums = remember(tracks) {
        tracks.filter { it.releaseId != null }.distinctBy { it.releaseId }
    }
    val albums = remember(allAlbums) { allAlbums.take(4) }
    val hiddenCount = allAlbums.size - albums.size

    val accent = LocalWallpaperAccent.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isScoped) {
                    Modifier.agGlassTinted(RoundedCornerShape(8.dp), tint = accent, opacity = 0.35f)
                } else Modifier
            )
            // Tap narrows the branch to this month. The month itself is not a
            // drag source: a quarter tile is a summary of four covers, and
            // queueing everything behind it from here is a much bigger action
            // than the tile looks like it is offering.
            .agPressable(onClick = onScope, pressScale = 0.95f),
        contentAlignment = Alignment.Center
    ) {
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = monthName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isScoped) accent else AgPalette.TextSecondary
                    )
                    if (tracks.isNotEmpty()) {
                        Text(
                            text = "${tracks.size} tracks",
                            fontFamily = NunitoFamily,
                            fontSize = 10.sp,
                            color = AgPalette.TextMetadata
                        )
                    }
                }
            }
        } else if (albums.size == 1) {
            AlbumCover(
                item = albums[0],
                corner = 8.dp,
                onScope = onScope,
                modifier = Modifier.fillMaxSize()
            )
            MonthLabelOverlay(monthName, isScoped)
        } else {
            // Multiple albums - 2x2 grid, with a count when it cannot hold them all
            Column(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (rowIndex in 0 until 2) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        for (colIndex in 0 until 2) {
                            val album = albums.getOrNull(rowIndex * 2 + colIndex)
                            if (album != null) {
                                AlbumCover(
                                    item = album,
                                    corner = 4.dp,
                                    onScope = onScope,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            } else {
                                Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            MonthLabelOverlay(monthName, isScoped)
            if (hiddenCount > 0) {
                // Four covers out of nine is a fine summary; four covers that
                // look like all of them is not.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "+$hiddenCount",
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * One cover in a month's collage.
 *
 * Its own drag source, so a single album can be pulled out of a month without
 * taking the month with it. Tapping still narrows the branch, so no part of the
 * tile is a dead tap.
 */
@Composable
private fun AlbumCover(
    item: TimelineItemRow,
    corner: Dp,
    onScope: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drag = LocalDragToQueue.current
    val playback = LocalPlayback.current
    val releaseId = item.releaseId

    AsyncImage(
        model = item.artModel ?: "",
        contentDescription = item.effectiveAlbumTitle,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .agAlbumTile(RoundedCornerShape(corner))
            .then(
                if (releaseId == null) Modifier else Modifier.agDraggableRow(
                    onClick = onScope,
                    onDragStart = {
                        drag.start(
                            QueueDragPayload.Album(
                                releaseId = releaseId,
                                label = item.effectiveAlbumTitle ?: "Unknown Album",
                                subtitle = item.effectiveArtistDisplay,
                                artModel = item.artModel,
                                trackCount = 0
                            ),
                            it
                        )
                    },
                    onDrag = { drag.move(it) },
                    onDragEnd = {
                        (drag.drop() as? QueueDragPayload.Album)
                            ?.let { dropped -> playback.addAlbumToQueue(dropped.releaseId) }
                    },
                    onDragCancel = { drag.cancel() },
                    pressScale = 0.9f
                )
            )
    )
}

@Composable
private fun BoxScope.MonthLabelOverlay(monthName: String, isScoped: Boolean) {
    val accent = LocalWallpaperAccent.current
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = monthName,
            style = MaterialTheme.typography.labelSmall,
            color = if (isScoped) accent else Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@file:OptIn(ExperimentalFoundationApi::class)
package com.visibeat.musicui.timeline.day

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.visibeat.viewengine.*
import com.visibeat.musicui.design.*
import com.visibeat.musicui.timeline.shared.*

/**
 * DAY view - Self-contained timeline view for daily buckets.
 * Features:
 * - Month demarcation headers when month changes
 * - Vertical pager for multiple albums (not horizontal!)
 * - Single album art per card (not all albums)
 */

@Composable
fun DayTimelineView(
    listState: LazyListState,
    buckets: List<TimelineBucketRow>,
    query: ViewQuery,
    queryEngine: TimelineQueryEngine,
    onOpenArtist: (artistId: Long) -> Unit,
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

    // No tree here, deliberately.
    //
    // A day bucket holds one release, and the card already draws it with its
    // track list. Branching it below the card produced the same release name and
    // the same track a second time, one indent to the right. The other
    // granularities branch because their cards are collages that cannot say what
    // is inside them; this one can.
    LaunchedEffect(listState.firstVisibleItemIndex, buckets) {
        buckets.getOrNull(listState.firstVisibleItemIndex)
            ?.let { onVisibleBucketChange(it.bucketStartEpochMs) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 14.dp, bottom = 160.dp)
    ) {
        itemsIndexed(
            items = buckets,
            key = { _, bucket -> bucket.bucketStartEpochMs }
        ) { index, bucket ->
            val yearMonthLabel = remember(bucket.bucketStartEpochMs) {
                YearMonthLabel.fromBucketStart(bucket.bucketStartEpochMs)
            }

            LaunchedEffect(bucket.bucketStartEpochMs, query.artistId, query.releaseId, query.genreContains, query.releaseDateQuality, refreshKey) {
                if (!previews.containsKey(bucket.bucketStartEpochMs)) {
                    val items = queryEngine.getBucketPreviewItems(query, bucket.bucketStartEpochMs)
                    if (previews.size >= MAX_CACHED_BUCKETS) previews.clear()
                    previews[bucket.bucketStartEpochMs] = items
                }
            }

            val previewItems = previews[bucket.bucketStartEpochMs] ?: emptyList()

            // The row before this one, straight from its own index. This used to
            // be an indexOfFirst scan of the whole bucket list, re-run on every
            // recomposition of every visible row.
            val previousLabel = remember(buckets, index) {
                buckets.getOrNull(index - 1)
                    ?.let { YearMonthLabel.fromBucketStart(it.bucketStartEpochMs) }
            }

            // Month separator header when month changes
            MonthHeaderIfNeeded(
                currentLabel = yearMonthLabel,
                previousLabel = previousLabel,
                modifier = Modifier.padding(horizontal = 14.dp)
            )

            DayBucketRowUI(
                dayLabel = yearMonthLabel.dayOfMonth.toString(),
                previewItems = previewItems,
                side = sideForIndex(index),
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist
            )
        }
    }
}

/**
 * Track rows drawn inside a day card, before "+N more" takes over.
 *
 * Deliberately a cap and not a scroller. The card already sits inside a
 * LazyColumn, and on a day with several releases inside a VerticalPager as well;
 * a third nested vertical scroll would have to arbitrate against both. Worse,
 * every row here is a long-press drag source, so the inner scroller and the drag
 * would be competing for the same finger on the same pixels. The overflow line
 * costs one row and none of that.
 */
private const val PREVIEW_ROWS = 5

/** A card holding a two-line title, a subtitle and [PREVIEW_ROWS] track rows. */
private val PAGER_HEIGHT = 275.dp

/**
 * The centre column, and what separates it from a card.
 *
 * Every dp here comes off the card, twice over: the card and the empty space
 * opposite it share what is left on equal weights, and they have to stay equal
 * or the spine stops being a straight line. A stacked day number never needed
 * 64dp of column, and the dashes never needed 20dp to read as a connector.
 */
private val SPINE_WIDTH = 48.dp
private val CONNECTOR_WIDTH = 14.dp
private val SPINE_GAP = 6.dp

/**
 * Ceiling on cached bucket previews.
 *
 * The bucket list used to be capped at 60, which bounded this cache by accident.
 * Now that the whole library is reachable a long scroll would otherwise retain a
 * preview for every bucket it passed. Dropping the lot is crude, but refetching
 * is one indexed query per *visible* bucket and only happens after scrolling
 * past this many.
 */
internal const val MAX_CACHED_BUCKETS = 240

// -----------------------------------------------------
// Month header (when month changes in DAY view)
// -----------------------------------------------------
@Composable
private fun MonthHeaderIfNeeded(
    currentLabel: YearMonthLabel,
    previousLabel: YearMonthLabel?,
    modifier: Modifier = Modifier
) {
    val showHeader = previousLabel == null ||
            previousLabel.month != currentLabel.month ||
            previousLabel.year != currentLabel.year

    if (showHeader) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AgTimelineSpine(Modifier.height(10.dp))

                // Show "2024\nJan" style header (year above month)
                VerticalStackText(
                    text = "${currentLabel.year}\n${currentLabel.monthShort}",
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                AgTimelineSpine(Modifier.height(10.dp))
            }
        }
    }
}

// -----------------------------------------------------
// Day bucket row - with vertical album pager
// -----------------------------------------------------
@Composable
private fun DayBucketRowUI(
    dayLabel: String,
    previewItems: List<TimelineItemRow>,
    side: Side,
    onOpenAlbum: (releaseId: Long) -> Unit,
    onOpenArtist: (artistId: Long) -> Unit
) {
    // Group items by release
    val itemsByRelease = remember(previewItems) {
        previewItems.groupBy { it.releaseId }.values.toList()
    }

    val hasMultipleReleases = itemsByRelease.size > 1
    val pagerState = if (hasMultipleReleases) {
        rememberPagerState(pageCount = { itemsByRelease.size })
    } else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (side == Side.LEFT) {
            DayBucketContent(
                hasMultipleReleases = hasMultipleReleases,
                pagerState = pagerState,
                itemsByRelease = itemsByRelease,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                modifier = Modifier.weight(1f)
            )
            // Graphical dashed connector from card to spine
            HorizontalSpineConnector(Modifier.width(CONNECTOR_WIDTH))
        } else {
            Spacer(Modifier.weight(1f))
            // Reserved, not decorative. The connector only exists on the card's
            // side, so without this the spine sat CONNECTOR_WIDTH further along
            // on left rows than on right ones and the trunk wove down the screen.
            Spacer(Modifier.width(CONNECTOR_WIDTH))
        }

        Spacer(Modifier.width(SPINE_GAP))

        CenterSpine(
            label = dayLabel,
            modifier = Modifier.width(SPINE_WIDTH)
        )

        Spacer(Modifier.width(SPINE_GAP))

        if (side == Side.RIGHT) {
            HorizontalSpineConnector(Modifier.width(CONNECTOR_WIDTH))
            DayBucketContent(
                hasMultipleReleases = hasMultipleReleases,
                pagerState = pagerState,
                itemsByRelease = itemsByRelease,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.width(CONNECTOR_WIDTH))
            Spacer(Modifier.weight(1f))
        }
    }
}

// -----------------------------------------------------
// Day bucket content - VERTICAL pager for multiple albums
// -----------------------------------------------------
@Composable
private fun DayBucketContent(
    hasMultipleReleases: Boolean,
    pagerState: PagerState?,
    itemsByRelease: List<List<TimelineItemRow>>,
    onOpenAlbum: (releaseId: Long) -> Unit,
    onOpenArtist: (artistId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (hasMultipleReleases && pagerState != null) {
        // A pager cannot size itself to its tallest page, so the height is fixed
        // to a card holding a title, a subtitle and PREVIEW_ROWS tracks.
        VerticalPager(
            state = pagerState,
            modifier = modifier.height(PAGER_HEIGHT)
        ) { page ->
            DayReleaseCard(
                releaseItems = itemsByRelease[page],
                currentReleaseIndex = page,
                totalReleases = itemsByRelease.size,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist
            )
        }
    } else {
        DayReleaseCard(
            releaseItems = itemsByRelease.flatten(),
            currentReleaseIndex = 0,
            totalReleases = 1,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            modifier = modifier
        )
    }
}

// -----------------------------------------------------
// Day release card - shows ONLY current album art
// -----------------------------------------------------
@Composable
private fun DayReleaseCard(
    releaseItems: List<TimelineItemRow>,
    currentReleaseIndex: Int,
    totalReleases: Int,
    onOpenAlbum: (releaseId: Long) -> Unit,
    onOpenArtist: (artistId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val first = releaseItems.firstOrNull()
    val title = first?.effectiveAlbumTitle ?: "Unknown Album"

    // Artist and count, not just a count. "1 tracks" was both wrong and the
    // least interesting thing the card could have said about a release.
    val subtitle = remember(releaseItems) {
        val artist = releaseItems.firstNotNullOfOrNull { it.effectiveArtistDisplay }
        val n = releaseItems.size
        val count = "$n ${if (n == 1) "track" else "tracks"}"
        if (artist != null) "$artist · $count" else count
    }

    AgCard(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        // A day is a leaf: there is nothing finer to open it into, so the card
        // goes where the release actually lives.
        onClick = { first?.releaseId?.let(onOpenAlbum) },
        trailingTitleContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (first?.artModel != null) {
                    AsyncImage(
                        model = first.artModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(32.dp)
                            .agAlbumTile(RoundedCornerShape(6.dp))
                    )
                }

                // Which release of the day you are on, when there is more than one
                if (totalReleases > 1) {
                    Text(
                        text = "${currentReleaseIndex + 1}/$totalReleases",
                        fontFamily = NunitoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = AgPalette.TextMetadata
                    )
                }
            }
        },
        metadataContent = {
            // The card lives in a fixed-height pager, so the row count is capped
            // rather than letting a forty-track release be clipped mid-row. The
            // rest still play — tapping a row queues the whole release.
            PreviewList(
                previewItems = releaseItems,
                onOpenArtist = onOpenArtist,
                maxVisible = PREVIEW_ROWS,
                showArtist = false
            )
        }
    )
}

// -----------------------------------------------------
// Helper to get previous bucket's label
// -----------------------------------------------------
// -----------------------------------------------------
// Horizontal dashed connector (card → spine)
// -----------------------------------------------------
@Composable
private fun HorizontalSpineConnector(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.height(3.dp)) {
        val centerY = size.height / 2f
        val stroke = 2.dp.toPx()
        val dashOn = 4.dp.toPx()
        val dashOff = 3.dp.toPx()

        // Soft glow
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = stroke * 3f,
            cap = StrokeCap.Round
        )

        // Dashed line
        drawLine(
            color = Color.White.copy(alpha = 0.30f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
        )
    }
}

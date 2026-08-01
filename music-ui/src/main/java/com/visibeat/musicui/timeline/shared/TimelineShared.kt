@file:OptIn(ExperimentalFoundationApi::class)
package com.visibeat.musicui.timeline.shared

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.visibeat.viewengine.*
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.LocalDragToQueue
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.musicui.playback.QueueDragPayload
import java.util.*
import java.text.DateFormatSymbols

/**
 * Shared primitives for all timeline views (DAY, MONTH, YEAR).
 * These components have NO bucket-specific logic.
 */

// -----------------------------------------------------
// Side enum for L/R card placement
// -----------------------------------------------------
enum class Side { LEFT, RIGHT }

/**
 * Which side of the spine a row sits on, from its position in the list.
 *
 * This used to be derived from the *timestamp*: the day-number parity of the
 * bucket's start. That only alternates when consecutive buckets are an odd
 * number of days apart, which months mostly are not — January to February is 31
 * days and flips, February to March is 28 and does not. Five month-pairs a year
 * ended up stacked on the same side, and in day view every gap between days
 * that actually have music did the same thing.
 *
 * Position is what the zigzag is actually about, so position is what it reads.
 */
fun sideForIndex(index: Int): Side =
    if (index % 2 == 0) Side.LEFT else Side.RIGHT

// -----------------------------------------------------
// Date label helpers
// -----------------------------------------------------
data class YearMonthLabel(val year: Int, val month: Int, val monthShort: String, val dayOfMonth: Int) {
    companion object {
        fun fromBucketStart(bucketStartEpochMs: Long): YearMonthLabel {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = bucketStartEpochMs
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) // 0-based
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val short = DateFormatSymbols(Locale.US).shortMonths[m].take(3)
            return YearMonthLabel(y, m + 1, short, d)
        }
    }
}

// -----------------------------------------------------
// Bucket end computation
// -----------------------------------------------------
fun computeBucketEndUtc(bucketStartEpochMs: Long, bucket: TimelineBucket): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = bucketStartEpochMs
    when (bucket) {
        TimelineBucket.YEAR -> cal.add(Calendar.YEAR, 1)
        TimelineBucket.MONTH -> cal.add(Calendar.MONTH, 1)
        TimelineBucket.DAY -> cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

// -----------------------------------------------------
// Center Spine
// -----------------------------------------------------
@Composable
fun CenterSpine(
    label: String,
    modifier: Modifier = Modifier,
    /**
     * The bucket is open and its releases are branching below.
     *
     * Marked on the spine node rather than on the card, because the node is
     * where the branch descends from — it is the thing that has children.
     */
    isExpanded: Boolean = false
) {
    val accent = LocalWallpaperAccent.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top spine segment
        AgTimelineSpine(Modifier.height(12.dp))

        // Label node
        AgTimelineNode {
            VerticalStackText(
                text = label,
                modifier = Modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isExpanded) FontWeight.Bold else FontWeight.Light,
                color = if (isExpanded) accent else MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp,
                letterSpacing = 1.sp
            )
        }

        // Bottom spine segment
        AgTimelineSpine(Modifier.height(12.dp))
    }
}

// -----------------------------------------------------
// Preview List (track previews inside cards)
// -----------------------------------------------------
/**
 * The track list inside a timeline card.
 *
 * Rows were previously bare Text with a clickable that played one track into
 * silence. Now a tap starts the whole card as a queue from that row, a
 * long-press opens track detail, and the live row is marked.
 */
@Composable
fun PreviewList(
    previewItems: List<TimelineItemRow>,
    onOpenArtist: (artistId: Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How many rows to draw. The rest still go into the queue when a row is
     * tapped — a card in a fixed-height pager just cannot show forty of them,
     * and silently clipping the overflow made cards look truncated.
     */
    maxVisible: Int = Int.MAX_VALUE,
    /**
     * Off when the card above already names the artist — a day card is one
     * release, so repeating it on every row says nothing.
     */
    showArtist: Boolean = true
) {
    val accent = LocalWallpaperAccent.current
    val playback = LocalPlayback.current
    val drag = LocalDragToQueue.current
    val visible = if (previewItems.size > maxVisible) previewItems.take(maxVisible) else previewItems

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        visible.forEachIndexed { index, item ->
            val isCurrent = playback.isCurrent(item.trackId)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isCurrent) {
                            Modifier.agGlassTinted(
                                RoundedCornerShape(8.dp),
                                tint = accent,
                                opacity = 0.16f
                            )
                        } else Modifier
                    )
                    // Tap plays from here; long-press lifts this one track
                    // towards the queue. Track detail moved off the long press,
                    // which the drag now owns.
                    .agDraggableRow(
                        onClick = { playback.playTracks(previewItems, index) },
                        onDragStart = { drag.start(QueueDragPayload.Track(item), it) },
                        onDrag = { drag.move(it) },
                        onDragEnd = {
                            drag.drop()?.let { dropped ->
                                if (dropped is QueueDragPayload.Track) {
                                    playback.addToQueue(listOf(dropped.track))
                                }
                            }
                        },
                        onDragCancel = { drag.cancel() },
                        pressScale = 0.98f
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.effectiveTitle ?: "Unknown Title",
                        fontFamily = NunitoFamily,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) accent else AgPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (showArtist) {
                        Text(
                            item.effectiveArtistDisplay ?: "Unknown Artist",
                            fontFamily = NunitoFamily,
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.agPressable(
                                onClick = { item.primaryArtistId?.let { onOpenArtist(it) } },
                                pressScale = 0.95f
                            )
                        )
                    }
                }
                NowPlayingRowIndicator(trackId = item.trackId, size = 12.dp)
            }
        }

        if (previewItems.size > visible.size) {
            Text(
                "+${previewItems.size - visible.size} more",
                fontFamily = NunitoFamily,
                style = MaterialTheme.typography.bodySmall,
                color = AgPalette.TextMetadata,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// -----------------------------------------------------
// Empty state
// -----------------------------------------------------
@Composable
fun EmptyTimelineState(
    hasFilters: Boolean = false,
    onClearFilters: (() -> Unit)? = null,
    onIngest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (hasFilters) {
            AgEmptyState(
                title = "Nothing matches these filters",
                message = "No releases fall in this date-confidence band.",
                icon = Icons.Default.LibraryMusic,
                actionLabel = if (onClearFilters != null) "Clear filters" else null,
                onAction = onClearFilters
            )
        } else {
            AgEmptyState(
                title = "No timeline data yet",
                message = "VisiBeat plots music by release date. Ingest a library and the timeline fills itself in.",
                icon = Icons.Default.LibraryMusic,
                actionLabel = if (onIngest != null) "Scan my library" else null,
                onAction = onIngest
            )
        }
    }
}

// -----------------------------------------------------
// Album art grid tile (shared for MONTH view)
// -----------------------------------------------------
@Composable
fun GridTile(rel: TimelineItemRow?) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .agGlass(RoundedCornerShape(8.dp), opacity = 0.15f),
        contentAlignment = Alignment.Center
    ) {
        if (rel != null && rel.artModel != null) {
            AsyncImage(
                model = rel.artModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .agAlbumTile(RoundedCornerShape(4.dp))
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
            )
        }
    }
}

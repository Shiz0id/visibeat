package com.visibeat.musicui.timeline.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.visibeat.musicui.playback.DragToQueueController
import com.visibeat.musicui.playback.LocalDragToQueue
import com.visibeat.musicui.playback.QueueDragPayload
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.viewengine.AlbumTrackRow
import com.visibeat.viewengine.TimelineAlbumRow

/**
 * The branches that grow off the timeline spine when a bucket is opened.
 *
 * The spine is drawn per row in the centre column, so a child row continues it
 * rather than interrupting it — open a month and its releases hang below on the
 * same line, open a release and its tracks hang below that. What was a decorative
 * rule down the middle becomes a trunk you descend.
 *
 * Indentation is measured from the spine outward rather than from the screen
 * edge, so the tree reads the same whichever side of the zigzag its parent
 * happens to be on.
 */
private val SPINE_COLUMN = 64.dp
private val BRANCH_STEP = 22.dp

/**
 * Draws the piece of trunk this row sits on, plus the elbow into its content.
 *
 * @param isLast the trunk stops half way down, so a branch visibly ends
 */
@Composable
private fun BranchSpine(
    depth: Int,
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = LocalWallpaperAccent.current
    Canvas(modifier.width(SPINE_COLUMN).fillMaxHeight()) {
        val x = size.width / 2f + (depth - 1) * BRANCH_STEP.toPx()
        val midY = size.height / 2f
        val trunkEnd = if (isLast) midY else size.height

        drawLine(
            color = accent.copy(alpha = 0.35f),
            start = Offset(x, 0f),
            end = Offset(x, trunkEnd),
            strokeWidth = 2f
        )
        // The elbow out to the row's content.
        drawLine(
            color = accent.copy(alpha = 0.35f),
            start = Offset(x, midY),
            end = Offset(x + BRANCH_STEP.toPx() * 0.7f, midY),
            strokeWidth = 2f
        )
    }
}

/**
 * A branch row: tap does its own thing, long-press lifts it towards the queue.
 *
 * Long-press to lift, because a short press has to stay available for tapping
 * and the rows sit inside a vertical scroll that must keep working. Which of
 * the two happened is decided by [agDraggableRow] rather than by two detectors
 * racing — see there for why that distinction is load-bearing.
 */
private fun Modifier.queueDraggableRow(
    onClick: () -> Unit,
    payload: () -> QueueDragPayload,
    controller: DragToQueueController,
    onDropped: (QueueDragPayload) -> Unit,
    pressScale: Float = 0.97f
): Modifier = agDraggableRow(
    onClick = onClick,
    onDragStart = { controller.start(payload(), it) },
    onDrag = { controller.move(it) },
    onDragEnd = { controller.drop()?.let(onDropped) },
    onDragCancel = { controller.cancel() },
    pressScale = pressScale
)

/**
 * What a drop on the cube means.
 *
 * Appends rather than interrupts: you are building up what comes next, not
 * replacing what is on.
 */
@Composable
private fun rememberQueueDrop(): (QueueDragPayload) -> Unit {
    val playback = LocalPlayback.current
    return remember(playback) {
        { dropped ->
            when (dropped) {
                is QueueDragPayload.Track -> playback.addToQueue(listOf(dropped.track))
                is QueueDragPayload.Album -> playback.addAlbumToQueue(dropped.releaseId)
            }
        }
    }
}

/** A release hanging off an opened bucket. */
@Composable
fun AlbumBranch(
    album: TimelineAlbumRow,
    expanded: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current
    val drag = LocalDragToQueue.current
    val onDropped = rememberQueueDrop()

    Row(modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        BranchSpine(depth = 1, isLast = isLast)

        Row(
            Modifier
                .weight(1f)
                .padding(end = 16.dp)
                .agGlass(RoundedCornerShape(10.dp), opacity = if (expanded) 0.16f else 0.08f)
                // Long-press lifts the release towards the queue, so opening the
                // album page moved to its artwork below.
                .queueDraggableRow(
                    onClick = onToggle,
                    payload = {
                        QueueDragPayload.Album(
                            releaseId = album.releaseId,
                            label = album.title ?: "Unknown Album",
                            subtitle = album.artistDisplay,
                            artModel = album.artModel,
                            trackCount = album.trackCount
                        )
                    },
                    controller = drag,
                    onDropped = onDropped,
                    pressScale = 0.98f
                )
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (album.artModel != null) {
                AsyncImage(
                    model = album.artModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .agAlbumTile(RoundedCornerShape(5.dp))
                        .agPressable(onClick = onOpenAlbum, pressScale = 0.92f)
                )
            } else {
                // A release with no cover is still a release. The bucket collage
                // filtered these out entirely, which is how albums went missing.
                Box(
                    Modifier.size(40.dp).agGlass(RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = album.title?.take(1)?.uppercase() ?: "?",
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = AgPalette.TextMetadata
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = album.title ?: "Unknown Album",
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (expanded) accent else AgPalette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.artistDisplay ?: "Unknown Artist"} · " +
                        "${album.trackCount} ${if (album.trackCount == 1) "track" else "tracks"}",
                    fontFamily = NunitoFamily,
                    fontSize = 11.sp,
                    color = AgPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AgBareIconButton(
                icon = Icons.Default.PlayArrow,
                contentDescription = "Play ${album.title ?: "album"}",
                onClick = { album.releaseId.let { playback.playAlbum(it) } },
                touchSize = 34.dp,
                iconSize = 18.dp,
                tint = AgPalette.TextSecondary
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = AgPalette.TextMetadata,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * A track hanging off an opened release. Tap plays it, long-press lifts it.
 *
 * No track-detail gesture: long-press is the drag here, and the metadata sheet
 * is reachable from the album page and every other list in the app.
 */
@Composable
fun TrackBranch(
    track: AlbumTrackRow,
    isLast: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playback = LocalPlayback.current
    val drag = LocalDragToQueue.current
    val onDropped = rememberQueueDrop()
    val accent = LocalWallpaperAccent.current
    val isCurrent = playback.isCurrent(track.trackId)

    Row(modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        BranchSpine(depth = 2, isLast = isLast)

        Row(
            Modifier
                .weight(1f)
                .padding(end = 16.dp)
                .queueDraggableRow(
                    onClick = onPlay,
                    payload = { QueueDragPayload.Track(track.toItemRow()) },
                    controller = drag,
                    onDropped = onDropped,
                    pressScale = 0.99f
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    NowPlayingRowIndicator(trackId = track.trackId, color = accent, size = 11.dp)
                } else {
                    Text(
                        text = track.trackNumber?.toString() ?: "–",
                        fontFamily = NunitoFamily,
                        fontSize = 11.sp,
                        color = AgPalette.TextMetadata
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = track.effectiveTitle ?: "Unknown Title",
                fontFamily = NunitoFamily,
                fontSize = 13.sp,
                color = if (isCurrent) accent else AgPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Children asked for but not yet arrived. */
@Composable
fun BranchLoading(depth: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        BranchSpine(depth = depth, isLast = true)
        Text(
            text = "Loading…",
            fontFamily = NunitoFamily,
            fontSize = 11.sp,
            color = AgPalette.TextMetadata
        )
    }
}

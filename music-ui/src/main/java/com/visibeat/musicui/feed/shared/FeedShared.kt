package com.visibeat.musicui.feed.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.viewengine.*

/**
 * Shared primitives for all feed views (DAY, MONTH, YEAR).
 * These components have NO bucket-specific logic.
 */

// -----------------------------------------------------
// Feed Shell — replaces Scaffold in all feed views
// Glass-over-wallpaper with frosted top bar
// -----------------------------------------------------
@Composable
fun FeedShell(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    onPlayAll: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    /**
     * ColumnScope so content can claim the remaining height with weight(1f).
     * Without it a fillMaxSize child is handed the full window height and
     * overflows past the top bar.
     */
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Custom top bar: glass pill with back arrow and title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AgIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = 36.dp
            )

            Spacer(Modifier.width(4.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = AgPalette.TextPrimary,
                    maxLines = 1
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontFamily = NunitoFamily,
                        fontSize = 13.sp,
                        color = AgPalette.TextSecondary,
                        maxLines = 1
                    )
                }
            }

            if (onShuffle != null) {
                AgIconButton(
                    icon = Icons.Default.Shuffle,
                    contentDescription = "Shuffle these tracks",
                    onClick = onShuffle,
                    size = 36.dp,
                    iconSize = 18.dp
                )
            }
            if (onPlayAll != null) {
                AgAccentButton(
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "Play all",
                    onClick = onPlayAll,
                    size = 40.dp
                )
            }
        }

        content()
    }
}

// -----------------------------------------------------
// Feed Row (single track item) — glass card with thumbnail
// -----------------------------------------------------
/**
 * Tapping a row starts the whole feed as a queue from that row, which is what
 * made feeds feel like dead lists before: every tap played one track and then
 * silence.
 *
 * [queueIndex] is resolved by the caller from a precomputed map so a long feed
 * doesn't do a linear scan per row.
 */
@Composable
fun FeedRow(
    row: TimelineItemRow,
    queue: List<TimelineItemRow>,
    queueIndex: Int,
    onOpenArtist: (artistId: Long) -> Unit
) {
    val accent = LocalWallpaperAccent.current
    val playback = LocalPlayback.current
    val isCurrent = playback.isCurrent(row.trackId)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .then(
                if (isCurrent) {
                    Modifier.agGlassTinted(RoundedCornerShape(12.dp), tint = accent, opacity = 0.18f)
                } else {
                    Modifier.agGlass(RoundedCornerShape(12.dp), opacity = 0.06f)
                }
            )
            .agPressable(
                onClick = { playback.playTracks(queue, queueIndex) },
                onLongClick = { playback.openTrackDetail(row.trackId) },
                pressScale = 0.985f
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album art thumbnail
        row.artModel?.let { art ->
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .agAlbumTile(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(10.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                row.effectiveTitle ?: "Unknown Title",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (isCurrent) accent else AgPalette.TextPrimary,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Row {
                Text(
                    row.effectiveArtistDisplay ?: "Unknown Artist",
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    color = accent,
                    maxLines = 1,
                    modifier = Modifier.agPressable(
                        onClick = { row.primaryArtistId?.let { onOpenArtist(it) } },
                        pressScale = 0.94f
                    )
                )
                Text(
                    " · " + (row.effectiveAlbumTitle ?: "Unknown Album"),
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    color = AgPalette.TextSecondary,
                    maxLines = 1
                )
            }
        }

        NowPlayingRowIndicator(trackId = row.trackId)
    }
}

/**
 * trackId → position in the feed, built once per result set so [FeedRow] can
 * resolve its queue position without scanning.
 */
@Composable
fun rememberQueueIndex(items: List<TimelineItemRow>): Map<Long, Int> =
    remember(items) { items.withIndex().associate { (i, row) -> row.trackId to i } }

// -----------------------------------------------------
// Feed Chips Row — uses AgChip instead of AssistChip
// -----------------------------------------------------
@Composable
fun FeedChipsRow(
    query: ViewQuery,
    onQueryChange: (ViewQuery) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AgChip(
                label = if (query.sort == SortDirection.DESC) "Newest first" else "Oldest first",
                selected = true,
                onClick = {
                    onQueryChange(
                        query.copy(
                            sort = if (query.sort == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC
                        )
                    )
                }
            )
        }
        items(DateQuality.entries) { quality ->
            AgChip(
                label = quality.label,
                selected = query.releaseDateQuality == quality.filters,
                onClick = {
                    // Tapping the active filter clears it, so there is always a
                    // way back to "everything" without hunting for a reset.
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
}

/**
 * The date-confidence filters, in the order they are offered.
 *
 * The alpha's chip said "Quality: Any" and toggled a single hardcoded "USER"
 * value, so the other three confidence levels the ingest pipeline records were
 * unreachable from the UI.
 */
enum class DateQuality(val label: String, val filters: Set<String>) {
    /** Hand-edited, MusicBrainz-enriched, or otherwise vouched for. */
    CONFIRMED("Confirmed dates", setOf("USER", "VERIFIED", "MUSICBRAINZ")),
    TAGGED("From tags", setOf("TAGGED")),
    /**
     * Derived from weaker signals — a year with no month, a folder name.
     *
     * Deliberately not "UNKNOWN". The resolver writes that quality only when it
     * found no date at all, which means a null epoch, which every date-ranged
     * query excludes by construction. Including it here made a chip that could
     * never match a single row, and quietly implied those tracks were reachable
     * from a date view. They are counted separately instead.
     */
    INFERRED("Guessed dates", setOf("INFERRED"))
}

// -----------------------------------------------------
// Album Art Hero — glass-framed with rounded corners
// -----------------------------------------------------
@Composable
fun AlbumArtHero(
    artModel: Any?,
    albumTitle: String?
) {
    if (artModel != null) {
        AsyncImage(
            model = artModel,
            contentDescription = albumTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(180.dp)
                .graphicsLayer { shadowElevation = 8.dp.toPx() }
                .agAlbumTile(RoundedCornerShape(20.dp))
        )
    } else if (albumTitle != null) {
        // Glass panel fallback with album title
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(120.dp)
                .agGlass(RoundedCornerShape(20.dp), opacity = 0.08f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                albumTitle,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = AgPalette.TextPrimary
            )
        }
    }
}

// -----------------------------------------------------
// Section Header — horizontal spine rule with frosted pill
// -----------------------------------------------------
@Composable
fun FeedSectionHeader(
    label: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Horizontal rule
        HorizontalDivider(
            color = AgPalette.GlassWhite,
            thickness = 1.dp,
            modifier = Modifier.fillMaxWidth()
        )
        // Frosted pill label
        Row(
            modifier = Modifier
                .agGlass(RoundedCornerShape(999.dp), opacity = 0.10f)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                label,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = AgPalette.TextPrimary
            )
            trailing?.invoke()
        }
    }
}

// -----------------------------------------------------
// Empty Feed State
// -----------------------------------------------------
@Composable
fun EmptyFeedState(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AgEmptyState(
            title = "Nothing in this bucket",
            message = "The filters above may be hiding everything here.",
            actionLabel = if (onBack != null) "Go back" else null,
            onAction = onBack
        )
    }
}

/**
 * Shown while the query is still running.
 *
 * Feeds used to start from an empty list, so every one of them flashed
 * "nothing in this bucket" for a frame before its rows arrived — which reads as
 * a bug even when the data is fine.
 */
@Composable
fun FeedLoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = LocalWallpaperAccent.current,
            strokeWidth = 3.dp,
            modifier = Modifier.size(32.dp)
        )
    }
}

package com.visibeat.musicui.feed.year

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.visibeat.viewengine.*
import com.visibeat.musicui.feed.shared.*
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.LocalPlayback
import java.util.*

/**
 * YEAR Feed - Monthly sections layout with album art hero.
 * Shows tracks grouped by month with month headers.
 */

@Composable
fun YearFeedView(
    queryEngine: FeedQueryEngine,
    bucketStartEpochMs: Long,
    bucketEndEpochMs: Long,
    baseQuery: ViewQuery,
    onBack: () -> Unit,
    onOpenArtist: (artistId: Long) -> Unit
) {
    var query by remember {
        mutableStateOf(
            baseQuery.copy(
                fromEpochMs = bucketStartEpochMs,
                toEpochMs = bucketEndEpochMs
            )
        )
    }

    // Null means "still querying" — distinct from an empty result, so the feed
    // does not flash its empty state before the rows arrive.
    val itemsState = produceState<List<TimelineItemRow>?>(initialValue = null, query) {
        value = queryEngine.listFeedItems(query)
    }
    val items = itemsState.value.orEmpty()
    val isLoading = itemsState.value == null
    val queueIndex = rememberQueueIndex(items)
    val playback = LocalPlayback.current

    val title = remember(bucketStartEpochMs) {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = bucketStartEpochMs
        cal.get(Calendar.YEAR).toString()
    }

    val itemsByMonth = remember(items, query.sort) {
        items.groupBy { row ->
            row.effectiveReleaseDateEpochMs?.let { dateMs ->
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = dateMs
                cal.get(Calendar.MONTH)
            } ?: -1
        }.toSortedMap(if (query.sort == SortDirection.DESC) compareByDescending { it } else compareBy { it })
    }

    val monthNames = remember {
        listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
    }

    val primaryRelease = items.firstOrNull()

    FeedShell(
        title = title,
        subtitle = if (items.isEmpty()) null else "${items.size} tracks",
        onBack = onBack,
        onPlayAll = if (items.isNotEmpty()) ({ playback.playTracks(items, 0) }) else null,
        onShuffle = if (items.size > 1) ({ playback.shuffleTracks(items) }) else null
    ) {
        Column(Modifier.fillMaxSize()) {
            AlbumArtHero(
                artModel = primaryRelease?.artModel,
                albumTitle = primaryRelease?.effectiveAlbumTitle
            )

            Spacer(Modifier.height(8.dp))
            FeedChipsRow(query = query, onQueryChange = { query = it })

            if (isLoading) {
                // weight(1f), not fillMaxSize: a non-weighted child in a Column
                // is handed the full height and would overflow past the rows.
                FeedLoadingState(Modifier.weight(1f))
            } else if (items.isEmpty()) {
                EmptyFeedState(onBack = onBack, modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp)
                ) {
                    itemsByMonth.forEach { (monthIndex, tracks) ->
                        item(key = "month_$monthIndex") {
                            val monthLabel = if (monthIndex in 0..11) monthNames[monthIndex] else "Unknown"
                            FeedSectionHeader(
                                label = monthLabel,
                                trailing = {
                                    AgIconButton(
                                        icon = Icons.Default.PlayArrow,
                                        contentDescription = "Play $monthLabel",
                                        onClick = { playback.playTracks(tracks, 0) },
                                        size = 24.dp,
                                        iconSize = 14.dp,
                                        opacity = 0.14f
                                    )
                                }
                            )
                        }

                        items(tracks, key = { it.trackId }) { row ->
                            FeedRow(
                                row = row,
                                queue = items,
                                queueIndex = queueIndex[row.trackId] ?: 0,
                                onOpenArtist = onOpenArtist
                            )
                        }
                    }
                }
            }
        }
    }
}

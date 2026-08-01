package com.visibeat.musicui.feed.day

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

/**
 * DAY Feed - Album-centric layout.
 * Shows tracks for a single day, grouped by release with album art header.
 */

@Composable
fun DayFeedView(
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

    // Group items by release
    val itemsByRelease = remember(items) { items.groupBy { it.releaseId } }

    val primaryRelease = items.firstOrNull()
    val title = primaryRelease?.effectiveAlbumTitle ?: "Day Feed"

    FeedShell(
        title = title,
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
                    itemsByRelease.forEach { (releaseId, tracks) ->
                        if (itemsByRelease.size > 1) {
                            item(key = "header_$releaseId") {
                                FeedSectionHeader(
                                    label = tracks.first().effectiveAlbumTitle ?: "Unknown Album",
                                    trailing = {
                                        AgIconButton(
                                            icon = Icons.Default.PlayArrow,
                                            contentDescription = "Play this release",
                                            onClick = { playback.playTracks(tracks, 0) },
                                            size = 24.dp,
                                            iconSize = 14.dp,
                                            opacity = 0.14f
                                        )
                                    }
                                )
                            }
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

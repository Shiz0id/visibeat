package com.visibeat.musicui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.viewengine.LibraryFeeds
import com.visibeat.viewengine.PlaylistOrdering
import com.visibeat.viewengine.PlaylistRow
import com.visibeat.viewengine.PlaylistSort
import com.visibeat.viewengine.LibraryArtistRow
import com.visibeat.viewengine.LibraryDao
import com.visibeat.viewengine.TimelineItemRow

/**
 * Home: greeting, a shuffle-everything shortcut, quick-play albums, recently
 * added, top artists and the full album shelf.
 *
 * Playback comes from [LocalPlayback] rather than parameters — tapping a track
 * here now starts the section it lives in as a queue instead of playing one
 * track into silence, and every tile has a real play control rather than only a
 * navigation tap.
 */
@Composable
fun HomeScreen(
    feeds: LibraryFeeds,
    onOpenArtist: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onIngest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Shared flows, so leaving Home and coming back replays the last value
    // instead of re-running six queries. See LibraryFeeds.
    val recentTracks by feeds.recentTracks.collectAsState()
    val topAlbums by feeds.topAlbums.collectAsState()
    val topArtists by feeds.topArtists.collectAsState()
    val playlists by feeds.playlists.collectAsState()
    val trackCount by feeds.trackCount.collectAsState()
    val artistCount by feeds.artistCount.collectAsState()
    val albumCount by feeds.albumCount.collectAsState()

    val playback = LocalPlayback.current
    val greeting = remember { greetingForTime() }

    // One de-duplicated list, shared by the shelf and by the queue a tap starts,
    // so the row you press is the row that plays.
    // Pinned first, then most recently opened or edited. The ordering is
    // PlaylistOrdering's, the same one the Playlists screen uses, so the top of
    // Home cannot disagree with the list it links into.
    val topPlaylists = remember(playlists) {
        PlaylistOrdering.order(playlists, PlaylistSort.RECENTS).take(4)
    }

    val recentShelf = remember(recentTracks) {
        recentTracks.distinctBy { it.releaseId ?: it.trackId }.take(15)
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 160.dp, top = statusBarTop + 8.dp)
    ) {
        // ── Greeting Header ──
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = greeting,
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        color = Color.White
                    )
                    if (trackCount > 0) {
                        Text(
                            text = "$trackCount tracks · $artistCount artists · $albumCount albums",
                            fontFamily = NunitoFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            color = AgPalette.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // ── Shuffle everything ──
        if (trackCount > 0) {
            item {
                Box(Modifier.padding(start = 20.dp, bottom = 4.dp)) {
                    AgAccentButton(
                        icon = Icons.Default.Shuffle,
                        contentDescription = "Shuffle your whole library",
                        label = "Shuffle all",
                        onClick = { playback.shuffleLibrary() }
                    )
                }
            }
        }

        // ── Quick Play Grid (playlists, 2-column compact) ──
        if (topPlaylists.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                QuickPlayGrid(
                    playlists = topPlaylists,
                    onOpenPlaylist = onOpenPlaylist
                )
            }
        }

        // ── Recently Added ──
        if (recentShelf.isNotEmpty()) {
            item {
                AgSectionHeader(
                    title = "Recently Added",
                    trailing = {
                        AgIconButton(
                            icon = Icons.Default.PlayArrow,
                            contentDescription = "Play recently added",
                            onClick = { playback.playTracks(recentShelf, 0) },
                            size = 36.dp,
                            iconSize = 20.dp
                        )
                    }
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(recentShelf) { index, track ->
                        AlbumTile(
                            track = track,
                            // Tapping a tile opens the album. It used to be the
                            // same lambda as onPlay, so the tile and the play
                            // button on it did the same thing and there was no
                            // way in to the album at all.
                            onClick = {
                                val releaseId = track.releaseId
                                if (releaseId != null) onOpenAlbum(releaseId)
                                // A loose track with no release has no album to
                                // open; its details are the only thing to show.
                                else playback.openTrackDetail(track.trackId)
                            },
                            onLongClick = { playback.openTrackDetail(track.trackId) },
                            onPlay = { playback.playTracks(recentShelf, index) }
                        )
                    }
                }
            }
        }

        // ── Top Artists ──
        if (topArtists.isNotEmpty()) {
            item {
                AgSectionHeader(title = "Your Top Artists")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(topArtists) { artist ->
                        ArtistCircle(
                            artist = artist,
                            onClick = { onOpenArtist(artist.artistId) },
                            onPlay = { playback.playArtist(artist.artistId) }
                        )
                    }
                }
            }
        }

        // ── Top Albums (horizontal scroll) ──
        //
        // Ranked by plays rather than by date added: Recently Added sits a few
        // rows above this one, and two shelves of the newest thing in the
        // library told you the same fact twice. Empty until something has
        // actually been played, which is the honest state.
        if (topAlbums.isNotEmpty()) {
            item {
                AgSectionHeader(title = "Top Albums")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(topAlbums) { album ->
                        AlbumTile(
                            track = album,
                            onClick = { album.releaseId?.let { onOpenAlbum(it) } },
                            onLongClick = { playback.openTrackDetail(album.trackId) },
                            onPlay = { album.releaseId?.let { playback.playAlbum(it) } }
                        )
                    }
                }
            }
        }

        // ── Empty state ──
        if (trackCount == 0) {
            item {
                AgEmptyState(
                    title = "No music yet",
                    message = "Point VisiBeat at your music and it will build your timeline.",
                    icon = Icons.Default.LibraryMusic,
                    actionLabel = "Scan my library",
                    onAction = onIngest
                )
            }
        }
    }
}

/**
 * Compact 2-column grid of playlists — the first thing on Home.
 *
 * Was six albums by date, which duplicated the Albums shelf further down the
 * same screen and put the least personal thing in the most prominent place. Four
 * playlists is what you actually made.
 *
 * The tile navigates; the trailing button plays without leaving Home.
 */
@Composable
private fun QuickPlayGrid(
    playlists: List<PlaylistRow>,
    onOpenPlaylist: (Long) -> Unit
) {
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        playlists.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { playlist ->
                    AgSurface(
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .agPressable(
                                onClick = { onOpenPlaylist(playlist.playlistId) },
                                pressScale = 0.98f
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (playlist.artModel != null) {
                                AsyncImage(
                                    model = playlist.artModel,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                                )
                            } else {
                                // An empty playlist has no cover to borrow.
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                                        .agGlass(RoundedCornerShape(0.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = AgPalette.TextMetadata,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp)
                            ) {
                                // Without this, a pinned playlist sitting at the
                                // top looks like an arbitrary ordering.
                                if (playlist.isPinned) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = "Pinned",
                                        tint = accent,
                                        modifier = Modifier
                                            .size(12.dp)
                                            .padding(end = 0.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = playlist.name,
                                    fontFamily = NunitoFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            AgIconButton(
                                icon = Icons.Default.PlayArrow,
                                contentDescription = "Play ${playlist.name}",
                                onClick = { playback.playPlaylist(playlist.playlistId) },
                                size = 32.dp,
                                iconSize = 18.dp,
                                enabled = playlist.trackCount > 0,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
                // Fill remaining space if odd number
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}


/**
 * Album art tile with title beneath (horizontal scroll item).
 * The art carries a play button and a now-playing badge.
 */
@Composable
private fun AlbumTile(
    track: TimelineItemRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(140.dp)
            .agPressable(onClick = onClick, onLongClick = onLongClick, pressScale = 0.96f)
    ) {
        Box(Modifier.size(140.dp)) {
            AsyncImage(
                model = track.artModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .agAlbumTile()
            )
            AgAccentButton(
                icon = Icons.Default.PlayArrow,
                contentDescription = "Play",
                onClick = onPlay,
                size = 34.dp,
                iconSize = 20.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )
            NowPlayingRowIndicator(
                trackId = track.trackId,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.effectiveAlbumTitle ?: track.effectiveTitle ?: "Unknown",
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = track.effectiveArtistDisplay ?: "",
            fontFamily = NunitoFamily,
            fontSize = 12.sp,
            color = AgPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Circular artist avatar (Spotify style), now playable in place.
 */
@Composable
private fun ArtistCircle(
    artist: LibraryArtistRow,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(100.dp)
            .agPressable(onClick = onClick, pressScale = 0.95f)
    ) {
        Box(Modifier.size(100.dp)) {
            AgArtistAvatar(
                artistName = artist.artistName,
                imageModel = artist.imageUrl,
                fallbackModel = artist.albumArtModel,
                size = 100.dp,
                initialsCount = 2
            )
            AgAccentButton(
                icon = Icons.Default.PlayArrow,
                contentDescription = "Play ${artist.artistName}",
                onClick = onPlay,
                size = 32.dp,
                iconSize = 18.dp,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.artistName,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${artist.trackCount} tracks",
            fontFamily = NunitoFamily,
            fontSize = 11.sp,
            color = AgPalette.TextMetadata,
            textAlign = TextAlign.Center
        )
    }
}

private fun greetingForTime(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> "Good night"
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}

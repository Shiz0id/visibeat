package com.visibeat.musicui.playlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.feed.shared.FeedShell
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.viewengine.PlaylistDao
import com.visibeat.viewengine.TimelineItemRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One playlist's contents.
 *
 * Tracks keep the order they were added in, and a tap starts the playlist as a
 * queue from that point rather than playing a single track.
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistDao: PlaylistDao,
    onBack: () -> Unit,
    onOpenArtist: (artistId: Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current

    val playlist by playlistDao.observePlaylist(playlistId).collectAsState(initial = null)
    val tracks by playlistDao.observeTracks(playlistId).collectAsState(initial = emptyList())

    var renaming by remember { mutableStateOf(false) }

    val name = playlist?.name ?: "Playlist"

    FeedShell(
        title = name,
        subtitle = if (tracks.isEmpty()) null
            else "${tracks.size} ${if (tracks.size == 1) "song" else "songs"}",
        onBack = onBack,
        onPlayAll = if (tracks.isNotEmpty()) ({ playback.playTracks(tracks, 0) }) else null,
        onShuffle = if (tracks.size > 1) ({ playback.shuffleTracks(tracks) }) else null
    ) {
        if (tracks.isEmpty()) {
            AgEmptyState(
                title = "Nothing in \"$name\" yet",
                message = "Long-press any track anywhere in VisiBeat and choose \"Add to playlist\".",
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Header actions live above the list rather than in the top bar,
                // which already carries back / shuffle / play.
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        playlist?.let { row ->
                            AgIconButton(
                                icon = Icons.Default.PushPin,
                                contentDescription = if (row.isPinned) "Unpin" else "Pin to top",
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            playlistDao.togglePinned(row.playlistId, row.isPinned)
                                        }
                                    }
                                },
                                size = 34.dp,
                                iconSize = 17.dp,
                                tint = if (row.isPinned) accent else AgPalette.TextPrimary,
                                opacity = if (row.isPinned) 0.20f else 0.12f
                            )
                        }
                        AgIconButton(
                            icon = Icons.Default.Edit,
                            contentDescription = "Rename playlist",
                            onClick = { renaming = true },
                            size = 34.dp,
                            iconSize = 17.dp
                        )
                    }
                }

                itemsIndexed(tracks, key = { _, t -> t.trackId }) { index, track ->
                    PlaylistTrackRow(
                        track = track,
                        position = index + 1,
                        onClick = { playback.playTracks(tracks, index) },
                        onLongClick = { playback.openTrackDetail(track.trackId) },
                        onOpenArtist = onOpenArtist,
                        onRemove = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    playlistDao.removeTrack(playlistId, track.trackId)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (renaming) {
        PlaylistNameDialog(
            title = "Rename playlist",
            confirmLabel = "Save",
            initialName = name,
            onDismiss = { renaming = false },
            onConfirm = { newName ->
                renaming = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        playlistDao.renamePlaylist(playlistId, newName, System.currentTimeMillis())
                    }
                }
            }
        )
    }
}

@Composable
private fun PlaylistTrackRow(
    track: TimelineItemRow,
    position: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenArtist: (artistId: Long) -> Unit,
    onRemove: () -> Unit
) {
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current
    val isCurrent = playback.isCurrent(track.trackId)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .then(
                if (isCurrent) {
                    Modifier.agGlassTinted(RoundedCornerShape(14.dp), tint = accent, opacity = 0.18f)
                } else {
                    Modifier.agGlass(RoundedCornerShape(14.dp), opacity = 0.05f)
                }
            )
            .agPressable(onClick = onClick, onLongClick = onLongClick, pressScale = 0.985f)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$position",
            fontFamily = NunitoFamily,
            fontSize = 12.sp,
            color = AgPalette.TextMetadata,
            modifier = Modifier.width(24.dp)
        )

        AsyncImage(
            model = track.artModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .agAlbumTile(RoundedCornerShape(6.dp))
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = track.effectiveTitle ?: "Unknown",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (isCurrent) accent else AgPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.effectiveArtistDisplay ?: "Unknown Artist",
                fontFamily = NunitoFamily,
                fontSize = 13.sp,
                color = AgPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.agPressable(
                    onClick = { track.primaryArtistId?.let { onOpenArtist(it) } },
                    pressScale = 0.95f
                )
            )
        }

        NowPlayingRowIndicator(trackId = track.trackId)

        AgIconButton(
            icon = Icons.Default.Close,
            contentDescription = "Remove from playlist",
            onClick = onRemove,
            size = 30.dp,
            iconSize = 15.dp,
            tint = Color.White.copy(alpha = 0.55f),
            opacity = 0.06f
        )
    }
}

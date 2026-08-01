package com.visibeat.musicui.playlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.viewengine.LibraryFeeds
import com.visibeat.viewengine.PlaylistDao
import com.visibeat.viewengine.PlaylistOrdering
import com.visibeat.viewengine.PlaylistRow
import com.visibeat.viewengine.PlaylistSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The playlists list.
 *
 * Pinned playlists sit above everything else and stay there whichever way the
 * rest is sorted — the ordering rules live in [PlaylistOrdering] so they can be
 * tested without a database.
 */
@Composable
fun PlaylistsScreen(
    playlistDao: PlaylistDao,
    feeds: LibraryFeeds,
    sort: PlaylistSort,
    onSortChange: (PlaylistSort) -> Unit,
    onOpenPlaylist: (playlistId: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current

    val allPlaylists by feeds.playlists.collectAsState()
    val ordered = remember(allPlaylists, sort) { PlaylistOrdering.order(allPlaylists, sort) }

    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistRow?>(null) }
    var deleting by remember { mutableStateOf<PlaylistRow?>(null) }
    var actionsFor by remember { mutableStateOf<PlaylistRow?>(null) }

    Column(modifier.fillMaxSize()) {
        PlaylistsHeader(
            count = allPlaylists.size,
            onBack = onBack,
            onCreate = { showCreate = true }
        )

        // Sort control. Spotify's equivalent opens a sheet for one binary choice;
        // a single tappable label that names the current mode is fewer taps and
        // still says what it will do.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .agGlass(RoundedCornerShape(999.dp), opacity = 0.08f)
                    .agPressable(
                        onClick = {
                            onSortChange(
                                if (sort == PlaylistSort.RECENTS) PlaylistSort.NAME
                                else PlaylistSort.RECENTS
                            )
                        },
                        pressScale = 0.95f
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.SwapVert,
                    contentDescription = "Change sort order",
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when (sort) {
                        PlaylistSort.RECENTS -> "Recents"
                        PlaylistSort.NAME -> "Name"
                    },
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = AgPalette.TextPrimary
                )
            }
        }

        if (ordered.isEmpty()) {
            AgEmptyState(
                title = "No playlists yet",
                message = "Long-press any track and choose \"Add to playlist\" to start one.",
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                actionLabel = "New playlist",
                onAction = { showCreate = true },
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(ordered, key = { it.playlistId }) { playlist ->
                    PlaylistListItem(
                        playlist = playlist,
                        onClick = {
                            // Opening counts as activity, which is what "Recents"
                            // orders on.
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    playlistDao.touchOpened(playlist.playlistId, System.currentTimeMillis())
                                }
                            }
                            onOpenPlaylist(playlist.playlistId)
                        },
                        onLongClick = { actionsFor = playlist },
                        onPlay = {
                            scope.launch {
                                val tracks = withContext(Dispatchers.IO) {
                                    playlistDao.getTracks(playlist.playlistId)
                                }
                                if (tracks.isNotEmpty()) playback.playTracks(tracks, 0)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                scope.launch {
                    withContext(Dispatchers.IO) { playlistDao.createPlaylist(name) }
                }
            }
        )
    }

    renaming?.let { target ->
        PlaylistNameDialog(
            title = "Rename playlist",
            confirmLabel = "Save",
            initialName = target.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        playlistDao.renamePlaylist(target.playlistId, name, System.currentTimeMillis())
                    }
                }
            }
        )
    }

    deleting?.let { target ->
        PlaylistDeleteDialog(
            playlistName = target.name,
            trackCount = target.trackCount,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    withContext(Dispatchers.IO) { playlistDao.deletePlaylist(target.playlistId) }
                }
            }
        )
    }

    actionsFor?.let { target ->
        PlaylistActionsSheet(
            playlist = target,
            onDismiss = { actionsFor = null },
            onTogglePin = {
                actionsFor = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        playlistDao.togglePinned(target.playlistId, target.isPinned)
                    }
                }
            },
            onRename = {
                actionsFor = null
                renaming = target
            },
            onDelete = {
                actionsFor = null
                deleting = target
            },
            onShuffle = {
                actionsFor = null
                scope.launch {
                    val tracks = withContext(Dispatchers.IO) { playlistDao.getTracks(target.playlistId) }
                    if (tracks.isNotEmpty()) playback.shuffleTracks(tracks)
                }
            }
        )
    }
}

@Composable
private fun PlaylistsHeader(
    count: Int,
    onBack: () -> Unit,
    onCreate: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AgIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to library",
            onClick = onBack,
            size = 36.dp
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "Playlists",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                color = AgPalette.TextPrimary
            )
            Text(
                text = "$count ${if (count == 1) "playlist" else "playlists"}",
                fontFamily = NunitoFamily,
                fontSize = 12.sp,
                color = AgPalette.TextMetadata
            )
        }
        AgAccentButton(
            icon = Icons.Default.Add,
            contentDescription = "New playlist",
            onClick = onCreate,
            size = 40.dp
        )
    }
}

@Composable
private fun PlaylistListItem(
    playlist: PlaylistRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit
) {
    val accent = LocalWallpaperAccent.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .agGlass(RoundedCornerShape(14.dp), opacity = 0.05f)
            .agPressable(onClick = onClick, onLongClick = onLongClick, pressScale = 0.985f)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        PlaylistCover(playlist)

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = AgPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // The pin marker sits in the subtitle rather than as a trailing
                // icon, so a pinned playlist reads as pinned even when the list
                // is sorted by name and its position no longer says so.
                if (playlist.isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = accent,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "song" else "songs"}",
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    color = if (playlist.isPinned) accent else AgPalette.TextSecondary
                )
            }
        }

        if (playlist.trackCount > 0) {
            AgIconButton(
                icon = Icons.Default.PlayArrow,
                contentDescription = "Play ${playlist.name}",
                onClick = onPlay,
                size = 34.dp,
                iconSize = 18.dp
            )
        }
    }
}

@Composable
private fun PlaylistCover(playlist: PlaylistRow, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val art = playlist.artModel
    if (art != null) {
        AsyncImage(
            model = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .agAlbumTile(RoundedCornerShape(6.dp))
        )
    } else {
        // An empty playlist has no art to borrow. A glass tile with a glyph reads
        // as "nothing here yet" rather than as a failed image load.
        Box(
            Modifier
                .size(size)
                .agGlass(RoundedCornerShape(6.dp), opacity = 0.10f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(size / 2)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistActionsSheet(
    playlist: PlaylistRow,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShuffle: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AgModalSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaylistCover(playlist, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = playlist.name,
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = AgPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "song" else "songs"}",
                        fontFamily = NunitoFamily,
                        fontSize = 13.sp,
                        color = AgPalette.TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            SheetAction(
                icon = Icons.Default.PushPin,
                label = if (playlist.isPinned) "Unpin from top" else "Pin to top",
                onClick = onTogglePin,
                highlighted = playlist.isPinned
            )
            if (playlist.trackCount > 1) {
                SheetAction(Icons.Default.Shuffle, "Shuffle", onShuffle)
            }
            SheetAction(Icons.Default.Edit, "Rename", onRename)
            SheetAction(Icons.Default.Delete, "Delete playlist", onDelete, destructive = true)
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    destructive: Boolean = false
) {
    val accent = LocalWallpaperAccent.current
    val tint = when {
        destructive -> Color(0xFFEF4444)
        highlighted -> accent
        else -> AgPalette.TextPrimary
    }

    Row(
        Modifier
            .fillMaxWidth()
            .agPressable(onClick = onClick, pressScale = 0.99f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = tint
        )
    }
}

package com.visibeat.musicui.playlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import com.visibeat.viewengine.LibraryFeeds
import com.visibeat.viewengine.PlaylistDao
import com.visibeat.viewengine.PlaylistOrdering
import com.visibeat.viewengine.PlaylistRow
import com.visibeat.viewengine.PlaylistSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picker for filing tracks into playlists.
 *
 * Without this the whole playlist feature is inert — you could create, pin and
 * sort playlists forever and never get a song into one. Playlists that already
 * contain the track are marked, and tapping one is a toggle so a mistaken add is
 * one tap to undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    trackIds: List<Long>,
    playlistDao: PlaylistDao,
    feeds: LibraryFeeds,
    onDismiss: () -> Unit,
    /** Shown in the header so it is clear what is being filed. */
    subjectLabel: String? = null
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = LocalWallpaperAccent.current

    val playlists by feeds.playlists.collectAsState()
    val ordered = remember(playlists) { PlaylistOrdering.order(playlists, PlaylistSort.RECENTS) }

    // Membership is only tracked for a single track. For a multi-track add there
    // is no single answer to "is it already in there", so no marks are shown.
    val singleTrackId = trackIds.singleOrNull()
    val containingIds by if (singleTrackId != null) {
        playlistDao.observePlaylistIdsContaining(singleTrackId).collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<Long>()) }
    }

    var showCreate by remember { mutableStateOf(false) }

    AgModalSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Add to playlist",
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = AgPalette.TextPrimary
                    )
                    val label = subjectLabel ?: if (trackIds.size == 1) {
                        "1 track"
                    } else {
                        "${trackIds.size} tracks"
                    }
                    Text(
                        text = label,
                        fontFamily = NunitoFamily,
                        fontSize = 13.sp,
                        color = AgPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AgAccentButton(
                    icon = Icons.Default.Add,
                    contentDescription = "New playlist",
                    onClick = { showCreate = true },
                    size = 38.dp,
                    iconSize = 20.dp
                )
            }

            if (ordered.isEmpty()) {
                AgEmptyState(
                    title = "No playlists yet",
                    message = "Create one and this track goes straight into it.",
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    actionLabel = "New playlist",
                    onAction = { showCreate = true }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 32.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    items(ordered, key = { it.playlistId }) { playlist ->
                        val alreadyIn = playlist.playlistId in containingIds
                        PickerRow(
                            playlist = playlist,
                            checked = alreadyIn,
                            accent = accent,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (alreadyIn && singleTrackId != null) {
                                            playlistDao.removeTrack(playlist.playlistId, singleTrackId)
                                        } else {
                                            playlistDao.addTracks(playlist.playlistId, trackIds)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.navigationBarsPadding())
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
                    withContext(Dispatchers.IO) {
                        // Create and fill in one go — creating an empty playlist
                        // from here and then having to add the track again would
                        // be a pointless second step.
                        val newId = playlistDao.createPlaylist(name)
                        playlistDao.addTracks(newId, trackIds)
                    }
                }
            }
        )
    }
}

@Composable
private fun PickerRow(
    playlist: PlaylistRow,
    checked: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .then(
                if (checked) {
                    Modifier.agGlassTinted(RoundedCornerShape(12.dp), tint = accent, opacity = 0.16f)
                } else {
                    Modifier.agGlass(RoundedCornerShape(12.dp), opacity = 0.05f)
                }
            )
            .agPressable(onClick = onClick, pressScale = 0.985f)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val art = playlist.artModel
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .agAlbumTile(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                Modifier
                    .size(44.dp)
                    .agGlass(RoundedCornerShape(6.dp), opacity = 0.10f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = AgPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "song" else "songs"}",
                fontFamily = NunitoFamily,
                fontSize = 12.sp,
                color = AgPalette.TextSecondary
            )
        }

        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Already in this playlist — tap to remove",
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

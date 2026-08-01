package com.visibeat.musicui.search

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.viewengine.LibraryArtistRow
import com.visibeat.viewengine.LibraryDao
import com.visibeat.viewengine.TimelineItemRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    libraryDao: LibraryDao,
    onOpenArtist: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = LocalWallpaperAccent.current
    val playback = LocalPlayback.current

    // Recent searches survive tab switches within a session. Kept in memory
    // rather than prefs: it is a convenience, not a record worth persisting.
    val recentSearches = remember { mutableStateListOf<String>() }
    fun recordSearch(term: String) {
        val trimmed = term.trim()
        if (trimmed.length < 2) return
        recentSearches.remove(trimmed)
        recentSearches.add(0, trimmed)
        while (recentSearches.size > MAX_RECENT_SEARCHES) recentSearches.removeAt(recentSearches.lastIndex)
    }

    // Search results (reactive), against a debounced term rather than the raw
    // field. Each of the three queries is a `LIKE '%term%'` over every resolved
    // track — unindexable by construction — so firing them on every keystroke
    // meant three full-table scans per character typed. On a large library that
    // is the difference between a search box and a stutter.
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.length < SEARCH_MIN_CHARS) {
            debouncedQuery = ""
        } else {
            delay(SEARCH_DEBOUNCE_MS)
            debouncedQuery = query
        }
    }

    val tracks by remember(debouncedQuery) {
        if (debouncedQuery.length >= SEARCH_MIN_CHARS) libraryDao.searchTracks(debouncedQuery, 30)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val artists by remember(debouncedQuery) {
        if (debouncedQuery.length >= SEARCH_MIN_CHARS) libraryDao.searchArtists(debouncedQuery, 10)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val albums by remember(debouncedQuery) {
        if (debouncedQuery.length >= SEARCH_MIN_CHARS) libraryDao.searchAlbums(debouncedQuery, 10)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = statusBarTop + 8.dp)
    ) {
        // ── Search Header ──
        Text(
            text = "Search",
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 28.sp,
            color = Color.White,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
        )

        // ── Search Bar ──
        AgSurface(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = AgPalette.TextMetadata,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = NunitoFamily,
                        fontSize = 16.sp,
                        color = Color.White
                    ),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        recordSearch(query)
                        keyboard?.hide()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    "Songs, artists, or albums",
                                    fontFamily = NunitoFamily,
                                    fontSize = 16.sp,
                                    color = AgPalette.TextMetadata
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    AgIconButton(
                        icon = Icons.Default.Clear,
                        contentDescription = "Clear",
                        onClick = { query = "" },
                        size = 28.dp,
                        iconSize = 16.dp,
                        tint = AgPalette.TextSecondary,
                        opacity = 0.10f
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (query.length < 2) {
            // ── Recent searches, or a hint if there are none yet ──
            if (recentSearches.isNotEmpty()) {
                AgSectionHeader(
                    title = "Recent",
                    trailing = {
                        Text(
                            text = "Clear",
                            fontFamily = NunitoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = AgPalette.TextSecondary,
                            modifier = Modifier
                                .agPressable(onClick = { recentSearches.clear() }, pressScale = 0.94f)
                                .padding(8.dp)
                        )
                    }
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentSearches.toList()) { term ->
                        AgChip(
                            label = term,
                            onClick = { query = term },
                            onLongClick = { recentSearches.remove(term) }
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AgEmptyState(
                    title = "Search your library",
                    message = "Find any song, artist or album you've ingested.",
                    icon = Icons.Default.Search
                )
            }
        } else {
            // ── Results ──
            LazyColumn(
                contentPadding = PaddingValues(bottom = 160.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Artists
                if (artists.isNotEmpty()) {
                    item { ResultSectionHeader("Artists") }
                    items(artists, key = { it.artistId }) { artist ->
                        ArtistResultRow(
                            artist = artist,
                            onClick = {
                                recordSearch(query)
                                onOpenArtist(artist.artistId)
                            }
                        )
                    }
                }

                // Albums
                if (albums.isNotEmpty()) {
                    item { ResultSectionHeader("Albums") }
                    items(albums, key = { it.releaseId ?: it.trackId }) { album ->
                        AlbumResultRow(
                            album = album,
                            onClick = {
                                recordSearch(query)
                                album.releaseId?.let { onOpenAlbum(it) }
                            }
                        )
                    }
                }

                // Tracks — tapping one queues the whole result set from that point
                if (tracks.isNotEmpty()) {
                    item {
                        ResultSectionHeader(
                            "Songs",
                            trailing = {
                                AgIconButton(
                                    icon = Icons.Default.PlayArrow,
                                    contentDescription = "Play all results",
                                    onClick = {
                                        recordSearch(query)
                                        playback.playTracks(tracks, 0)
                                    },
                                    size = 32.dp,
                                    iconSize = 18.dp
                                )
                            }
                        )
                    }
                    itemsIndexed(tracks, key = { _, t -> t.trackId }) { index, track ->
                        TrackResultRow(
                            track = track,
                            onClick = {
                                recordSearch(query)
                                playback.playTracks(tracks, index)
                            },
                            onLongClick = { playback.openTrackDetail(track.trackId) }
                        )
                    }
                }

                // No results
                if (artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No results for \"$query\"",
                                fontFamily = NunitoFamily,
                                fontSize = 15.sp,
                                color = AgPalette.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_RECENT_SEARCHES = 8

/** Long enough to outlast typing, short enough to still feel instant. */
private const val SEARCH_DEBOUNCE_MS = 250L
private const val SEARCH_MIN_CHARS = 2

@Composable
private fun ResultSectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
private fun ArtistResultRow(artist: LibraryArtistRow, onClick: () -> Unit) {
    val playback = LocalPlayback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .agGlass(RoundedCornerShape(14.dp), opacity = 0.05f)
            .agPressable(onClick = onClick, pressScale = 0.985f)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        AgArtistAvatar(
            artistName = artist.artistName,
            imageModel = artist.imageUrl,
            fallbackModel = artist.albumArtModel,
            size = 48.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.artistName,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Artist · ${artist.trackCount} tracks",
                fontFamily = NunitoFamily,
                fontSize = 13.sp,
                color = AgPalette.TextSecondary
            )
        }
        AgIconButton(
            icon = Icons.Default.PlayArrow,
            contentDescription = "Play ${artist.artistName}",
            onClick = { playback.playArtist(artist.artistId) },
            size = 32.dp,
            iconSize = 18.dp
        )
    }
}

@Composable
private fun AlbumResultRow(album: TimelineItemRow, onClick: () -> Unit) {
    val playback = LocalPlayback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .agGlass(RoundedCornerShape(14.dp), opacity = 0.05f)
            .agPressable(onClick = onClick, pressScale = 0.985f)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        AsyncImage(
            model = album.artModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .agAlbumTile(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.effectiveAlbumTitle ?: "Unknown",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Album · ${album.effectiveArtistDisplay ?: ""}",
                fontFamily = NunitoFamily,
                fontSize = 13.sp,
                color = AgPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AgIconButton(
            icon = Icons.Default.PlayArrow,
            contentDescription = "Play ${album.effectiveAlbumTitle ?: "album"}",
            onClick = { album.releaseId?.let { playback.playAlbum(it) } },
            size = 32.dp,
            iconSize = 18.dp
        )
    }
}

@Composable
private fun TrackResultRow(
    track: TimelineItemRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit
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
        AsyncImage(
            model = track.artModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .agAlbumTile(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.effectiveTitle ?: "Unknown",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (isCurrent) accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.effectiveArtistDisplay ?: ""} · ${track.effectiveAlbumTitle ?: ""}",
                fontFamily = NunitoFamily,
                fontSize = 13.sp,
                color = AgPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        NowPlayingRowIndicator(trackId = track.trackId)
    }
}

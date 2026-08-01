package com.visibeat.musicui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.viewengine.LibraryFeeds
import com.visibeat.viewengine.LikesDao
import com.visibeat.viewengine.LibraryArtistRow
import com.visibeat.viewengine.LibraryDao
import com.visibeat.viewengine.PlayHistoryDao
import com.visibeat.viewengine.TimelineItemRow

/** Destinations the library shell can drill into. */
enum class LibrarySection { LIKED_SONGS, LIKED_ALBUMS, LIKED_ARTISTS, PLAYLISTS, ALBUMS, TRACKS, ARTISTS }

/**
 * The library shell.
 *
 * Replaces the three-chip tab layout with a Tidal-style index: a short list of
 * categories that open full screens, and recent listening along the bottom.
 * Categories are only listed if VisiBeat actually has them — no Mixes, Videos or
 * Downloads rows that lead nowhere.
 */
@Composable
fun LibraryScreen(
    feeds: LibraryFeeds,
    likedSongCount: Int,
    likedAlbumCount: Int,
    likedArtistCount: Int,
    playlistCount: Int,
    onOpenSection: (LibrarySection) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onIngest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val playback = LocalPlayback.current

    val trackCount by feeds.trackCount.collectAsState()
    val albumCount by feeds.albumCount.collectAsState()
    val artistCount by feeds.artistCount.collectAsState()
    val recentlyPlayed by feeds.recentlyPlayed.collectAsState()
    val recentlyAdded by feeds.recentTracks.collectAsState()

    // Recently played is the point of the shelf, but it is empty until something
    // has actually been played. Falling back to recently added — and saying so —
    // beats an empty strip on a fresh install.
    val shelfIsHistory = recentlyPlayed.isNotEmpty()
    val shelf = remember(recentlyPlayed, recentlyAdded) {
        if (recentlyPlayed.isNotEmpty()) recentlyPlayed
        else recentlyAdded.distinctBy { it.releaseId ?: it.trackId }.take(15)
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = statusBarTop + 8.dp, bottom = 160.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Collection",
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                    color = AgPalette.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (trackCount > 0) {
                    AgIconButton(
                        icon = Icons.Default.Shuffle,
                        contentDescription = "Shuffle your whole library",
                        onClick = { playback.shuffleLibrary() },
                        size = 38.dp,
                        iconSize = 20.dp
                    )
                }
                // The only way into settings. It lives here rather than on every
                // screen because it is somewhere you go deliberately, not a
                // control you need at hand while browsing.
                AgIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    size = 38.dp,
                    iconSize = 20.dp
                )
            }
        }

        item {
            SectionRow(
                icon = Icons.Default.Favorite,
                label = "Liked Songs",
                count = likedSongCount,
                onClick = { onOpenSection(LibrarySection.LIKED_SONGS) }
            )
        }
        item {
            SectionRow(
                icon = Icons.Default.Album,
                label = "Liked Albums",
                count = likedAlbumCount,
                onClick = { onOpenSection(LibrarySection.LIKED_ALBUMS) }
            )
        }
        item {
            SectionRow(
                icon = Icons.Default.Person,
                label = "Liked Artists",
                count = likedArtistCount,
                onClick = { onOpenSection(LibrarySection.LIKED_ARTISTS) }
            )
        }
        item {
            SectionRow(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                label = "Playlists",
                count = playlistCount,
                onClick = { onOpenSection(LibrarySection.PLAYLISTS) }
            )
        }
        item {
            SectionRow(
                icon = Icons.Default.Album,
                label = "Albums",
                count = albumCount,
                onClick = { onOpenSection(LibrarySection.ALBUMS) }
            )
        }
        item {
            SectionRow(
                icon = Icons.Default.MusicNote,
                label = "Tracks",
                count = trackCount,
                onClick = { onOpenSection(LibrarySection.TRACKS) }
            )
        }
        item {
            SectionRow(
                icon = Icons.Default.Person,
                label = "Artists",
                count = artistCount,
                onClick = { onOpenSection(LibrarySection.ARTISTS) }
            )
        }

        if (trackCount == 0) {
            item {
                AgEmptyState(
                    title = "Your collection is empty",
                    message = "Scan your music and VisiBeat will fill this in.",
                    icon = Icons.Default.LibraryMusic,
                    actionLabel = "Scan my library",
                    onAction = onIngest
                )
            }
        } else if (shelf.isNotEmpty()) {
            item {
                AgSectionHeader(
                    title = if (shelfIsHistory) "Recent activity" else "Recently added",
                    subtitle = if (shelfIsHistory) null else "Play something and this becomes your history",
                    trailing = {
                        AgIconButton(
                            icon = Icons.Default.PlayArrow,
                            contentDescription = "Play this shelf",
                            onClick = { playback.playTracks(shelf, 0) },
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
                    itemsIndexed(shelf, key = { _, t -> t.trackId }) { index, track ->
                        RecentTile(
                            track = track,
                            onClick = { playback.playTracks(shelf, index) },
                            onLongClick = { playback.openTrackDetail(track.trackId) },
                            onOpenAlbum = { track.releaseId?.let(onOpenAlbum) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One category in the library index. Deliberately quiet — these are signposts,
 * not content, so they get a glyph, a name and a count.
 */
@Composable
private fun SectionRow(
    icon: ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .agGlass(RoundedCornerShape(14.dp), opacity = 0.05f)
            .agPressable(onClick = onClick, pressScale = 0.99f)
            .padding(horizontal = 14.dp, vertical = 16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AgPalette.TextPrimary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            color = AgPalette.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            fontFamily = NunitoFamily,
            fontSize = 14.sp,
            color = AgPalette.TextMetadata
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AgPalette.TextMetadata,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** A tile in the recent shelf. Tap plays, long-press edits, the art opens the album. */
@Composable
private fun RecentTile(
    track: TimelineItemRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenAlbum: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(128.dp)
            .agPressable(onClick = onClick, onLongClick = onLongClick, pressScale = 0.96f)
    ) {
        Box(Modifier.size(128.dp)) {
            AsyncImage(
                model = track.artModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .agAlbumTile()
            )
            AgIconButton(
                icon = Icons.Default.Album,
                contentDescription = "Open album",
                onClick = onOpenAlbum,
                size = 30.dp,
                iconSize = 16.dp,
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
            text = track.effectiveTitle ?: "Unknown",
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = AgPalette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.effectiveArtistDisplay ?: "",
            fontFamily = NunitoFamily,
            fontSize = 12.sp,
            color = AgPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =====================================================
// Drill-down destinations
//
// These were the three tab bodies. As full screens they get room for a header
// with a play-all control, which the tabs had nowhere to put.
// =====================================================

@Composable
fun LibraryArtistsScreen(
    feeds: LibraryFeeds,
    onBack: () -> Unit,
    onOpenArtist: (Long) -> Unit
) {
    val artists by feeds.allArtists.collectAsState()
    val playback = LocalPlayback.current

    LibraryListShell(
        title = "Artists",
        subtitle = "${artists.size} ${if (artists.size == 1) "artist" else "artists"}",
        onBack = onBack,
        onShuffle = if (artists.isNotEmpty()) ({ playback.shuffleLibrary() }) else null
    ) {
        if (artists.isEmpty()) {
            AgEmptyState(
                title = "No artists yet",
                message = "Scan a library from the menu to fill this in.",
                icon = Icons.Default.Person,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(artists, key = { it.artistId }) { artist ->
                    ArtistListItem(artist = artist, onClick = { onOpenArtist(artist.artistId) })
                }
            }
        }
    }
}

@Composable
fun LibraryAlbumsScreen(
    feeds: LibraryFeeds,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit
) {
    val albums by feeds.allAlbums.collectAsState()

    LibraryListShell(
        title = "Albums",
        subtitle = "${albums.size} ${if (albums.size == 1) "album" else "albums"}",
        onBack = onBack
    ) {
        if (albums.isEmpty()) {
            AgEmptyState(
                title = "No albums yet",
                message = "Albums appear once tracks resolve to a release.",
                icon = Icons.Default.Album,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(albums, key = { it.releaseId ?: it.trackId }) { album ->
                    AlbumGridItem(album = album, onClick = { album.releaseId?.let { onOpenAlbum(it) } })
                }
            }
        }
    }
}

@Composable
fun LibraryTracksScreen(
    feeds: LibraryFeeds,
    onBack: () -> Unit
) {
    val tracks by feeds.allTracks.collectAsState()
    val playback = LocalPlayback.current

    LibraryListShell(
        title = "Tracks",
        subtitle = "${tracks.size} ${if (tracks.size == 1) "song" else "songs"}",
        onBack = onBack,
        onPlayAll = if (tracks.isNotEmpty()) ({ playback.playTracks(tracks, 0) }) else null,
        onShuffle = if (tracks.size > 1) ({ playback.shuffleTracks(tracks) }) else null
    ) {
        if (tracks.isEmpty()) {
            AgEmptyState(
                title = "No songs yet",
                message = "Scan a library from the menu to fill this in.",
                icon = Icons.Default.MusicNote,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(tracks, key = { _, t -> t.trackId }) { index, track ->
                    TrackListItem(
                        track = track,
                        onClick = { playback.playTracks(tracks, index) },
                        onLongClick = { playback.openTrackDetail(track.trackId) }
                    )
                }
            }
        }
    }
}

/** Shared header for the drill-down screens. */
@Composable
private fun LibraryListShell(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onPlayAll: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AgIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to collection",
                onClick = onBack,
                size = 36.dp
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = AgPalette.TextPrimary
                )
                Text(
                    text = subtitle,
                    fontFamily = NunitoFamily,
                    fontSize = 12.sp,
                    color = AgPalette.TextMetadata
                )
            }
            if (onShuffle != null) {
                AgIconButton(
                    icon = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
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

// =====================================================
// Shared rows
// =====================================================

@Composable
private fun ArtistListItem(artist: LibraryArtistRow, onClick: () -> Unit) {
    val playback = LocalPlayback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .agGlass(RoundedCornerShape(14.dp), opacity = 0.05f)
            .agPressable(onClick = onClick, pressScale = 0.985f)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        AgArtistAvatar(
            artistName = artist.artistName,
            imageModel = artist.imageUrl,
            fallbackModel = artist.albumArtModel,
            size = 52.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.artistName,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = AgPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${artist.trackCount} tracks",
                fontFamily = NunitoFamily,
                fontSize = 13.sp,
                color = AgPalette.TextSecondary
            )
        }
        AgIconButton(
            icon = Icons.Default.PlayArrow,
            contentDescription = "Play ${artist.artistName}",
            onClick = { playback.playArtist(artist.artistId) },
            size = 34.dp,
            iconSize = 18.dp
        )
    }
}

@Composable
private fun AlbumGridItem(album: TimelineItemRow, onClick: () -> Unit) {
    val playback = LocalPlayback.current
    Column(
        modifier = Modifier.agPressable(
            onClick = onClick,
            onLongClick = { playback.openTrackDetail(album.trackId) },
            pressScale = 0.96f
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            AsyncImage(
                model = album.artModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .agAlbumTile()
            )
            AgAccentButton(
                icon = Icons.Default.PlayArrow,
                contentDescription = "Play ${album.effectiveAlbumTitle ?: "album"}",
                onClick = { album.releaseId?.let { playback.playAlbum(it) } },
                size = 36.dp,
                iconSize = 20.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = album.effectiveAlbumTitle ?: "Unknown",
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = AgPalette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.effectiveArtistDisplay ?: "",
            fontFamily = NunitoFamily,
            fontSize = 12.sp,
            color = AgPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TrackListItem(
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
                color = if (isCurrent) accent else AgPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    track.effectiveArtistDisplay?.let { append(it) }
                    track.effectiveAlbumTitle?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                },
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

// -----------------------------------------------------
// Liked collections
// -----------------------------------------------------
/**
 * Liked songs, newest first.
 *
 * Deliberately not a playlist: a like is a primary-key lookup that every track
 * row in the app has to be able to answer, and this list must not be renameable
 * or deletable the way a real playlist is.
 */
@Composable
fun LikedSongsScreen(
    likesDao: LikesDao,
    onBack: () -> Unit,
    onOpenTrackDetail: (trackId: Long) -> Unit
) {
    val tracks by likesDao.observeLikedTracks().collectAsState(initial = emptyList())
    val playback = LocalPlayback.current

    LibraryListShell(
        title = "Liked Songs",
        subtitle = "${tracks.size} ${if (tracks.size == 1) "song" else "songs"}",
        onBack = onBack,
        onPlayAll = if (tracks.isNotEmpty()) ({ playback.playTracks(tracks, 0) }) else null,
        onShuffle = if (tracks.size > 1) ({ playback.shuffleTracks(tracks) }) else null
    ) {
        if (tracks.isEmpty()) {
            LibraryEmptyNote("Tap the heart on any track to collect it here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
                items(tracks, key = { it.trackId }) { track ->
                    TrackListItem(
                        track = track,
                        onClick = { playback.playTracks(tracks, tracks.indexOfFirst { it.trackId == track.trackId }.coerceAtLeast(0)) },
                        onLongClick = { onOpenTrackDetail(track.trackId) }
                    )
                }
            }
        }
    }
}

/** Liked albums, newest first. Independent of which songs are liked. */
@Composable
fun LikedAlbumsScreen(
    likesDao: LikesDao,
    onBack: () -> Unit,
    onOpenAlbum: (releaseId: Long) -> Unit
) {
    val albums by likesDao.observeLikedReleases().collectAsState(initial = emptyList())

    LibraryListShell(
        title = "Liked Albums",
        subtitle = "${albums.size} ${if (albums.size == 1) "album" else "albums"}",
        onBack = onBack
    ) {
        if (albums.isEmpty()) {
            LibraryEmptyNote("Like an album from its page to collect it here.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(albums, key = { it.releaseId ?: it.trackId }) { album ->
                    AlbumGridItem(album = album, onClick = { album.releaseId?.let(onOpenAlbum) })
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontFamily = NunitoFamily,
            fontSize = 14.sp,
            color = AgPalette.TextMetadata,
            modifier = Modifier.padding(horizontal = 40.dp)
        )
    }
}

/** Followed artists, most recently followed first. */
@Composable
fun LikedArtistsScreen(
    likesDao: LikesDao,
    onBack: () -> Unit,
    onOpenArtist: (artistId: Long) -> Unit
) {
    val artists by likesDao.observeLikedArtists().collectAsState(initial = emptyList())
    val playback = LocalPlayback.current

    LibraryListShell(
        title = "Liked Artists",
        subtitle = "${artists.size} ${if (artists.size == 1) "artist" else "artists"}",
        onBack = onBack,
        onShuffle = if (artists.isNotEmpty()) ({ playback.shuffleLibrary() }) else null
    ) {
        if (artists.isEmpty()) {
            LibraryEmptyNote("Follow an artist from their page to collect them here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
                items(artists, key = { it.artistId }) { artist ->
                    ArtistListItem(artist = artist, onClick = { onOpenArtist(artist.artistId) })
                }
            }
        }
    }
}

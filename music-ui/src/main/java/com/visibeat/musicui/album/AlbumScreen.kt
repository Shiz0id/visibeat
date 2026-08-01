package com.visibeat.musicui.album

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
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
import com.visibeat.radio.RadioOrigin
import com.visibeat.radio.RadioSeed
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.viewengine.AlbumDao
import com.visibeat.viewengine.AlbumTrackRow
import com.visibeat.viewengine.AudioFormat
import com.visibeat.viewengine.LikesDao
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * The album.
 *
 * There was no album page before this. Tapping an album opened the *timeline
 * feed* filtered to one release, which is why it was headed "January 1970" —
 * that is `bucketStartEpochMs = 0` formatted as a month, not a release date —
 * and why it carried date-confidence chips and day separators that mean nothing
 * for a record.
 *
 * Tracks are in album order, which is also new: disc and track numbers have been
 * captured at ingest since the schema was written and were read by nothing until
 * the 8->9 migration surfaced them onto `resolved_tracks`.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    releaseId: Long,
    albumDao: AlbumDao,
    likesDao: LikesDao,
    onBack: () -> Unit,
    onOpenArtist: (artistId: Long) -> Unit,
    onOpenTrackDetail: (trackId: Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current

    val header by albumDao.observeAlbumHeader(releaseId).collectAsState(initial = null)
    val tracks by albumDao.observeAlbumTracks(releaseId).collectAsState(initial = emptyList())
    val isLiked by likesDao.observeReleaseLiked(releaseId).collectAsState(initial = false)
    val likedTrackIds by likesDao.observeLikedTrackIds().collectAsState(initial = emptyList())
    val likedSet = remember(likedTrackIds) { likedTrackIds.toHashSet() }

    var showInfo by remember { mutableStateOf(false) }

    val itemRows = remember(tracks) { tracks.map { it.toItemRow() } }
    val format = remember(tracks) { AudioFormat.dominant(tracks.map { it.mimeType }) }
    val discs = remember(tracks) { tracks.groupBy { it.discNumber ?: 1 }.toSortedMap() }
    val multiDisc = discs.size > 1

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AgIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = 36.dp
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 180.dp)
        ) {
            item {
                AlbumHero(
                    artModel = header?.artModel,
                    title = header?.title ?: "Unknown Album",
                    artist = header?.artistDisplay ?: "Unknown Artist",
                    year = header?.dateEpochMs?.let { yearOf(it) },
                    format = format,
                    onOpenArtist = { header?.primaryArtistId?.let(onOpenArtist) }
                )

                Spacer(Modifier.height(18.dp))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PrimaryAction(
                        icon = Icons.Default.PlayArrow,
                        label = "Play",
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { if (itemRows.isNotEmpty()) playback.playTracks(itemRows, 0) }
                    )
                    PrimaryAction(
                        icon = Icons.Default.Shuffle,
                        label = "Shuffle",
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = { if (itemRows.isNotEmpty()) playback.shuffleTracks(itemRows) }
                    )
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AlbumAction(
                        icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        label = if (isLiked) "Liked" else "Like",
                        tint = if (isLiked) accent else AgPalette.TextPrimary,
                        onClick = { scope.launch { likesDao.toggleReleaseLiked(releaseId) } }
                    )
                    AlbumAction(
                        icon = Icons.Default.Info,
                        label = "Info",
                        onClick = { showInfo = true }
                    )
                    AlbumAction(
                        icon = Icons.Default.Radio,
                        label = "Radio",
                        onClick = {
                            RadioSeed.forAlbum(tracks.map { it.trackId })
                                ?.let { playback.startRadio(it, RadioOrigin.ALBUM) }
                        }
                    )
                    AlbumAction(
                        icon = Icons.Default.Person,
                        label = "Artist",
                        onClick = { header?.primaryArtistId?.let(onOpenArtist) }
                    )
                }

                Spacer(Modifier.height(20.dp))
            }

            discs.forEach { (disc, discTracks) ->
                if (multiDisc) {
                    item(key = "disc-$disc") {
                        // "Disc", never "Volume".
                        Text(
                            text = "Disc $disc",
                            fontFamily = NunitoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = AgPalette.TextSecondary,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp)
                        )
                    }
                }
                items(discTracks, key = { it.trackId }) { track ->
                    AlbumTrackListItem(
                        track = track,
                        isLiked = track.trackId in likedSet,
                        onClick = {
                            val index = itemRows.indexOfFirst { it.trackId == track.trackId }
                            playback.playTracks(itemRows, index.coerceAtLeast(0))
                        },
                        onLongClick = { onOpenTrackDetail(track.trackId) },
                        onToggleLike = { scope.launch { likesDao.toggleTrackLiked(track.trackId) } }
                    )
                }
            }
        }
    }

    if (showInfo) {
        AlbumInfoSheet(
            title = header?.title,
            artist = header?.artistDisplay,
            year = header?.dateEpochMs?.let { yearOf(it) },
            dateQuality = header?.releaseDateQuality,
            trackCount = tracks.size,
            discCount = discs.size,
            formats = remember(tracks) {
                tracks.mapNotNull { it.format }.groupingBy { it }.eachCount()
            },
            onDismiss = { showInfo = false }
        )
    }
}

// ── Header ────────────────────────────────────────────────

@Composable
private fun AlbumHero(
    artModel: Any?,
    title: String,
    artist: String,
    year: Int?,
    format: AudioFormat?,
    onOpenArtist: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (artModel != null) {
            AsyncImage(
                model = artModel,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f)
                    .agAlbumTile(RoundedCornerShape(12.dp))
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f)
                    .agGlass(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.take(1).uppercase(),
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 48.sp,
                    color = AgPalette.TextMetadata
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = title,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            color = AgPalette.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Album by $artist",
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = AgPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .agPressable(onClick = onOpenArtist, pressScale = 0.97f)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (year != null) {
                Text(
                    text = year.toString(),
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    color = AgPalette.TextMetadata
                )
            }
            if (format != null) {
                // Where a streaming app puts a quality tier. For a local library
                // the honest badge is what the file actually is.
                Box(
                    Modifier
                        .agGlassTinted(RoundedCornerShape(4.dp), tint = LocalWallpaperAccent.current)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = format.label,
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// ── Controls ──────────────────────────────────────────────

@Composable
private fun PrimaryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = LocalWallpaperAccent.current
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = modifier
            .height(48.dp)
            .then(
                if (filled) Modifier.agGlassTinted(shape, tint = accent, opacity = 0.55f)
                else Modifier.agGlass(shape, opacity = 0.14f)
            )
            .agPressable(onClick = onClick, pressScale = 0.97f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color.White
        )
    }
}

@Composable
private fun AlbumAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = AgPalette.TextPrimary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .agPressable(onClick = onClick, pressScale = 0.94f)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontFamily = NunitoFamily,
            fontSize = 11.sp,
            color = AgPalette.TextSecondary
        )
    }
}

// ── Track row ─────────────────────────────────────────────

@Composable
private fun AlbumTrackListItem(
    track: AlbumTrackRow,
    isLiked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleLike: () -> Unit
) {
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current
    val isCurrent = playback.isCurrent(track.trackId)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .agPressable(onClick = onClick, onLongClick = onLongClick, pressScale = 0.99f)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                NowPlayingRowIndicator(trackId = track.trackId, color = accent)
            } else {
                Text(
                    text = track.trackNumber?.toString() ?: "–",
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    color = AgPalette.TextMetadata
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = track.effectiveTitle ?: "Unknown Title",
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
                fontSize = 12.sp,
                color = AgPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // The only route a track has into Liked Songs.
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .agPressable(onClick = onToggleLike, pressScale = 0.88f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isLiked) "Remove from Liked Songs" else "Add to Liked Songs",
                tint = if (isLiked) accent else AgPalette.TextMetadata,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Info sheet ────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AlbumInfoSheet(
    title: String?,
    artist: String?,
    year: Int?,
    dateQuality: String?,
    trackCount: Int,
    discCount: Int,
    formats: Map<AudioFormat, Int>,
    onDismiss: () -> Unit
) {
    AgModalSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = title ?: "Unknown Album",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                color = AgPalette.TextPrimary
            )
            Spacer(Modifier.height(16.dp))
            InfoRow("Artist", artist ?: "Unknown")
            InfoRow("Released", year?.toString() ?: "Not known")
            // The provenance of that date, which is the thing the timeline sorts on.
            InfoRow("Date source", dateQuality?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Unknown")
            InfoRow("Tracks", trackCount.toString())
            if (discCount > 1) InfoRow("Discs", discCount.toString())
            InfoRow(
                "Format",
                if (formats.isEmpty()) "Unknown"
                else formats.entries.sortedByDescending { it.value }
                    .joinToString(", ") { (f, n) -> if (formats.size == 1) f.label else "${f.label} ($n)" }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            fontFamily = NunitoFamily,
            fontSize = 13.sp,
            color = AgPalette.TextMetadata,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = AgPalette.TextPrimary
        )
    }
}

private fun yearOf(epochMs: Long): Int {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epochMs
    return cal.get(Calendar.YEAR)
}

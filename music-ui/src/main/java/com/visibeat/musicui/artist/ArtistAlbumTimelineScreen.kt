package com.visibeat.musicui.artist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.feed.shared.FeedShell
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.musicui.timeline.shared.CenterSpine
import com.visibeat.musicui.timeline.shared.Side
import com.visibeat.viewengine.LibraryDao
import com.visibeat.viewengine.SortDirection
import com.visibeat.viewengine.TimelineBucket
import com.visibeat.viewengine.TimelineItemRow
import com.visibeat.viewengine.artist.AlbumBucketRow
import com.visibeat.viewengine.artist.AlbumCardRow
import com.visibeat.viewengine.artist.ArtistAlbumTimelineEngine
import java.text.DateFormatSymbols
import java.util.*

/**
 * An artist's releases laid out on the same vertical spine as the main timeline.
 *
 * This screen was the least finished thing in the app: the header said
 * "Artist" because the real name was never plumbed through, album rows were
 * plain text with a click handler wired to a commented-out no-op, and nothing
 * on it could be played. It now resolves the artist, shows cover art, plays,
 * and navigates into a release.
 */
@Composable
fun ArtistAlbumTimelineScreen(
    artistId: Long,
    engine: ArtistAlbumTimelineEngine,
    libraryDao: LibraryDao,
    bucket: TimelineBucket = TimelineBucket.MONTH,
    sort: SortDirection = SortDirection.DESC,
    onBack: () -> Unit,
    onOpenReleaseFeed: (releaseId: Long) -> Unit
) {
    val playback = LocalPlayback.current

    val bucketsState = produceState<List<AlbumBucketRow>>(initialValue = emptyList(), artistId, bucket, sort) {
        value = engine.getBuckets(artistId = artistId, sort = sort)
    }

    // One query gives us the display name, the play queue, and the cover art for
    // every release — the album timeline engine returns none of those.
    val artistState = produceState<com.visibeat.viewengine.LibraryArtistRow?>(null, artistId) {
        value = libraryDao.getArtist(artistId)
    }
    val tracksState = produceState<List<TimelineItemRow>>(emptyList(), artistId) {
        value = libraryDao.getTracksForArtist(artistId)
    }

    val artistTracks = tracksState.value
    val artistName = artistState.value?.artistName
        ?: artistTracks.firstOrNull()?.effectiveArtistDisplay
        ?: "Unknown Artist"

    /** releaseId → art model and the tracks to play for that release. */
    val tracksByRelease = remember(artistTracks) {
        artistTracks.filter { it.releaseId != null }.groupBy { it.releaseId!! }
    }

    val previews = remember { mutableStateMapOf<Long, List<AlbumCardRow>>() }
    LaunchedEffect(artistId) { previews.clear() }

    val albumCount = remember(tracksByRelease) { tracksByRelease.size }

    FeedShell(
        title = artistName,
        subtitle = if (artistTracks.isEmpty()) null
            else "$albumCount ${if (albumCount == 1) "release" else "releases"} · ${artistTracks.size} tracks",
        onBack = onBack,
        onPlayAll = if (artistTracks.isNotEmpty()) ({ playback.playTracks(artistTracks, 0) }) else null,
        onShuffle = if (artistTracks.size > 1) ({ playback.shuffleTracks(artistTracks) }) else null
    ) {
        // An artist with tracks must never show an empty page. The timeline needs
        // resolved release dates to plot anything, and plenty of tracks have
        // none — so when there is nothing to plot but there is something to
        // play, fall back to a plain list rather than a dead end.
        val hasTimeline = bucketsState.value.isNotEmpty()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 14.dp, bottom = 160.dp)
        ) {
            // Hero: the artist's face if one has been found, their own album art
            // if not, initials if neither.
            item {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AgArtistAvatar(
                        artistName = artistName,
                        imageModel = artistState.value?.imageUrl,
                        fallbackModel = artistState.value?.albumArtModel
                            ?: artistTracks.firstOrNull { it.artModel != null }?.artModel,
                        size = 120.dp,
                        initialsCount = 2
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = artistName,
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        color = AgPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$albumCount ${if (albumCount == 1) "release" else "releases"} · " +
                            "${artistTracks.size} tracks",
                        fontFamily = NunitoFamily,
                        fontSize = 12.sp,
                        color = AgPalette.TextMetadata
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (!hasTimeline) {
                if (artistTracks.isEmpty()) {
                    item {
                        AgEmptyState(
                            title = "Nothing here yet",
                            message = "No tracks are linked to $artistName."
                        )
                    }
                } else {
                    item {
                        AgSectionHeader(
                            title = "Tracks",
                            subtitle = "No release dates resolved yet, so there is no timeline to plot"
                        )
                    }
                    itemsIndexed(artistTracks, key = { _, t -> t.trackId }) { index, track ->
                        ArtistTrackRow(
                            track = track,
                            onClick = { playback.playTracks(artistTracks, index) },
                            onLongClick = { playback.openTrackDetail(track.trackId) }
                        )
                    }
                }
            }

            items(bucketsState.value, key = { it.bucketStartEpochMs }) { bucketRow ->
                val ym = remember(bucketRow.bucketStartEpochMs) { yearMonth(bucketRow.bucketStartEpochMs) }

                LaunchedEffect(bucketRow.bucketStartEpochMs, artistId, bucket) {
                    if (!previews.containsKey(bucketRow.bucketStartEpochMs)) {
                        previews[bucketRow.bucketStartEpochMs] =
                            engine.getAlbumCardsForBucket(artistId, bucketRow.bucketStartEpochMs, bucket)
                    }
                }

                ArtistYearHeaderIfNeeded(
                    year = ym.year,
                    previousYear = previousYearForAlbumBucket(bucketsState.value, bucketRow.bucketStartEpochMs),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )

                ArtistAlbumBucketRowUI(
                    bucketStartEpochMs = bucketRow.bucketStartEpochMs,
                    monthLabel = ym.monthShort,
                    releaseCount = bucketRow.releaseCount,
                    albumCards = previews[bucketRow.bucketStartEpochMs] ?: emptyList(),
                    isLoaded = previews.containsKey(bucketRow.bucketStartEpochMs),
                    tracksByRelease = tracksByRelease,
                    onOpenReleaseFeed = onOpenReleaseFeed
                )
            }
        }
    }
}

/** Plain track row, for artists with nothing datable to plot. */
@Composable
private fun ArtistTrackRow(
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
                // The full credit, so a guest appearance still reads as one.
                text = track.effectiveArtistDisplay ?: "",
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

@Composable
private fun ArtistAlbumBucketRowUI(
    bucketStartEpochMs: Long,
    monthLabel: String,
    releaseCount: Int,
    albumCards: List<AlbumCardRow>,
    isLoaded: Boolean,
    tracksByRelease: Map<Long, List<TimelineItemRow>>,
    onOpenReleaseFeed: (releaseId: Long) -> Unit
) {
    // Alternate sides off the bucket itself. Hashing the month name put every
    // "Mar" in the catalogue on the same side regardless of year.
    val side = remember(bucketStartEpochMs) {
        if ((bucketStartEpochMs / (1000L * 60L * 60L * 24L * 30L)) % 2L == 0L) Side.LEFT else Side.RIGHT
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (side == Side.LEFT) {
            AlbumBucketCard(
                modifier = Modifier.weight(1f),
                monthLabel = monthLabel,
                subtitle = "$releaseCount ${if (releaseCount == 1) "release" else "releases"}",
                albumCards = albumCards,
                isLoaded = isLoaded,
                tracksByRelease = tracksByRelease,
                onOpenReleaseFeed = onOpenReleaseFeed
            )
        } else Spacer(Modifier.weight(1f))

        CenterSpine(monthLabel, modifier = Modifier.width(64.dp))

        if (side == Side.RIGHT) {
            AlbumBucketCard(
                modifier = Modifier.weight(1f),
                monthLabel = monthLabel,
                subtitle = "$releaseCount ${if (releaseCount == 1) "release" else "releases"}",
                albumCards = albumCards,
                isLoaded = isLoaded,
                tracksByRelease = tracksByRelease,
                onOpenReleaseFeed = onOpenReleaseFeed
            )
        } else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun AlbumBucketCard(
    modifier: Modifier,
    monthLabel: String,
    subtitle: String,
    albumCards: List<AlbumCardRow>,
    isLoaded: Boolean,
    tracksByRelease: Map<Long, List<TimelineItemRow>>,
    onOpenReleaseFeed: (releaseId: Long) -> Unit
) {
    AgSurface(
        modifier = modifier.heightIn(min = 92.dp),
        shape = RoundedCornerShape(18.dp)
        
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                monthLabel,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = AgPalette.TextPrimary
            )
            Text(
                subtitle,
                fontFamily = NunitoFamily,
                fontSize = 12.sp,
                color = AgPalette.TextSecondary
            )

            Spacer(Modifier.height(10.dp))

            if (albumCards.isEmpty()) {
                Text(
                    if (isLoaded) "No releases in this month" else "Loading…",
                    fontFamily = NunitoFamily,
                    fontSize = 12.sp,
                    color = AgPalette.TextMetadata
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    albumCards.take(6).forEach { album ->
                        AlbumRow(
                            album = album,
                            tracks = tracksByRelease[album.releaseId].orEmpty(),
                            onOpenReleaseFeed = onOpenReleaseFeed
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    album: AlbumCardRow,
    tracks: List<TimelineItemRow>,
    onOpenReleaseFeed: (releaseId: Long) -> Unit
) {
    val playback = LocalPlayback.current
    val art = tracks.firstOrNull { it.artModel != null }?.artModel

    Row(
        Modifier
            .fillMaxWidth()
            .agPressable(
                onClick = { onOpenReleaseFeed(album.releaseId) },
                onLongClick = tracks.firstOrNull()?.let { first ->
                    { playback.openTrackDetail(first.trackId) }
                },
                pressScale = 0.98f
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .agAlbumTile(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .agGlass(RoundedCornerShape(6.dp), opacity = 0.10f)
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                album.albumTitle ?: "Unknown Album",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = AgPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${album.trackCount} ${if (album.trackCount == 1) "track" else "tracks"}",
                fontFamily = NunitoFamily,
                fontSize = 12.sp,
                color = AgPalette.TextSecondary
            )
        }

        tracks.firstOrNull()?.let { NowPlayingRowIndicator(trackId = it.trackId, size = 12.dp) }

        if (tracks.isNotEmpty()) {
            AgIconButton(
                icon = Icons.Default.PlayArrow,
                contentDescription = "Play ${album.albumTitle ?: "release"}",
                onClick = { playback.playTracks(tracks, 0) },
                size = 30.dp,
                iconSize = 16.dp
            )
        }
    }
}

@Composable
private fun ArtistYearHeaderIfNeeded(year: Int, previousYear: Int?, modifier: Modifier = Modifier) {
    if (previousYear == null || previousYear != year) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Horizontal spine rule
            HorizontalDivider(color = AgPalette.GlassWhite, modifier = Modifier.fillMaxWidth())
            // Year pill
            Box(
                modifier = Modifier
                    .agGlass(RoundedCornerShape(999.dp), opacity = 0.10f)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    "$year",
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = AgPalette.TextPrimary
                )
            }
        }
    }
}

private data class YM(val year: Int, val monthShort: String)

private fun yearMonth(bucketStartEpochMs: Long): YM {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = bucketStartEpochMs
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH)
    val short = DateFormatSymbols(Locale.US).shortMonths[m].take(3)
    return YM(y, short)
}

private fun previousYearForAlbumBucket(buckets: List<AlbumBucketRow>, bucketStart: Long): Int? {
    val idx = buckets.indexOfFirst { it.bucketStartEpochMs == bucketStart }
    if (idx <= 0) return null
    val prev = buckets[idx - 1].bucketStartEpochMs
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = prev
    return cal.get(Calendar.YEAR)
}

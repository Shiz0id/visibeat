package com.visibeat.musicui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visibeat.musicui.design.NowPlayingBars
import com.visibeat.radio.RadioOrigin
import com.visibeat.viewengine.TimelineItemRow

/**
 * Everything a list row needs in order to behave like part of a music player.
 *
 * Track rows live four or five composables deep (a preview list inside a card
 * inside a bucket row inside a lazy column), so threading half a dozen playback
 * callbacks down by parameter would touch every intermediate signature for no
 * benefit. This travels down the tree instead, and it is what lets any row
 * anywhere start a queue, open track detail, or show that it is the live one.
 */
@Immutable
data class PlaybackBinding(
    val nowPlayingTrackId: Long? = null,
    val isPlaying: Boolean = false,
    /** Start [tracks] as the queue, beginning at [index]. */
    val playTracks: (tracks: List<TimelineItemRow>, index: Int) -> Unit = { _, _ -> },
    val shuffleTracks: (tracks: List<TimelineItemRow>) -> Unit = {},
    val addToQueue: (tracks: List<TimelineItemRow>) -> Unit = {},
    /**
     * Play everything on a release or by an artist. Screens hold one
     * representative row per album, not the album, so resolving the real track
     * list is the host's job.
     */
    val playAlbum: (releaseId: Long) -> Unit = {},
    val shuffleAlbum: (releaseId: Long) -> Unit = {},
    val playArtist: (artistId: Long) -> Unit = {},
    val shuffleArtist: (artistId: Long) -> Unit = {},
    /**
     * Play a playlist in its own order.
     *
     * Same reasoning as [playAlbum]: a tile knows a playlist id, not its tracks,
     * and resolving the membership is the host's job.
     */
    val playPlaylist: (playlistId: Long) -> Unit = {},
    /**
     * Append a whole release to the queue without interrupting playback.
     *
     * What dropping an album on the cube does. A tile knows a release id, not
     * its tracks, so resolving the membership is the host's job — same as
     * [playAlbum].
     */
    val addAlbumToQueue: (releaseId: Long) -> Unit = {},
    val shuffleLibrary: () -> Unit = {},
    /**
     * Builds a station seeded on [trackId] and plays it.
     *
     * Travels with the rest of playback rather than as a screen parameter,
     * because three unrelated places offer a Radio button — an album, an artist
     * and the expanded player — and the alternative is threading the same
     * callback through three signatures to reach a lambda the host owns anyway.
     *
     * A track id, never an album or artist id: the index holds one vector per
     * track, and averaging a whole artist into a seed would blur exactly the
     * character the station is meant to follow. Callers with a collection pick a
     * representative track and pass that.
     *
     * [origin] is what the listener tapped, and it is not decoration. The seed
     * is a track either way, but an artist station that fines its own artist —
     * which is what one shared set of rules produced — is answering a different
     * question from the one that was asked.
     */
    val startRadio: (trackId: Long, origin: RadioOrigin) -> Unit = { _, _ -> },
    /** Opens the metadata editor sheet. Reached by long-pressing any track. */
    val openTrackDetail: (trackId: Long) -> Unit = {},
    val togglePlayPause: () -> Unit = {}
) {
    fun isCurrent(trackId: Long): Boolean = nowPlayingTrackId == trackId
}

val LocalPlayback = compositionLocalOf { PlaybackBinding() }

/**
 * Drops the bouncing equaliser onto a row when that row is the one playing.
 * Sized to sit where a trailing duration or chevron would otherwise go.
 */
@Composable
fun NowPlayingRowIndicator(
    trackId: Long,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color? = null
) {
    val playback = LocalPlayback.current
    AnimatedVisibility(
        visible = playback.isCurrent(trackId),
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.6f),
        modifier = modifier
    ) {
        Box(Modifier.size(size + 6.dp), contentAlignment = Alignment.Center) {
            if (color != null) {
                NowPlayingBars(isPlaying = playback.isPlaying, size = size, color = color)
            } else {
                NowPlayingBars(isPlaying = playback.isPlaying, size = size)
            }
        }
    }
}

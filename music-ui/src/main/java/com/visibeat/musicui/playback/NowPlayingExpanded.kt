package com.visibeat.musicui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visibeat.radio.RadioOrigin
import com.visibeat.musicui.design.*
import kotlinx.coroutines.flow.StateFlow

/**
 * The full-height player.
 *
 * The alpha had a play/pause row and nothing else: no idea how far into a track
 * you were, no way to move within it, no shuffle or repeat, and no view of what
 * was coming next — because none of that state existed. It does now.
 *
 * The cube itself is [NowPlayingSlab] and is used here exactly as-is.
 */
@Composable
fun NowPlayingExpanded(
    state: PlaybackState,
    /**
     * The flow itself, not a snapshot of it.
     *
     * Collecting the position here would put a value that changes twice a second
     * into this composable's recompose scope, dragging the whole player — art,
     * queue list and all — through a recomposition on every tick. [Scrubber]
     * collects it instead, so only the two lines that display it invalidate.
     */
    progressFlow: StateFlow<PlaybackProgress>,
    fftData: ByteArray,
    visualizerColors: List<Color>,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onClose: () -> Unit,
    /** Liked state of the playing track. Its own flow — see LikesDao. */
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    /** Opens the metadata sheet for the playing track. */
    onOpenInfo: () -> Unit
) {
    val track = state.currentTrack ?: return
    val accent = LocalWallpaperAccent.current
    val playback = LocalPlayback.current
    var showQueue by remember { mutableStateOf(false) }

    // Tall enough for the cube and the controls, and not one pixel more.
    //
    // This was a flat 0.88 of the screen, which on a big phone left a band of
    // dead space between the artist name and the scrubber — the panel was
    // reserving room it had nothing to put in. It now wraps its content and only
    // grows to [MAX_PANEL_FRACTION] when the queue is open and needs the room.
    val maxPanelHeight = LocalConfiguration.current.screenHeightDp.dp * MAX_PANEL_FRACTION

    AgAcrylicSurface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxPanelHeight)
            // Absorbs taps that land on the panel but not on a control. Without
            // it those points have no pointer-input node, so the hit test falls
            // through to whatever screen is underneath and you end up driving the
            // UI you cannot see.
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        // Denser than a dialog: this covers a whole screen of live content,
        // and whatever reads through lands right behind the track title.
        opacity = 0.95f
    ) {
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Everything on this panel except the cube is fixed-height chrome, and a
        // Column clips rather than shrinks. So the cube takes whatever height is
        // left over, capped at the size we actually want — on a short screen it
        // gives ground instead of pushing the transport controls off the bottom.
        val slabScale = ((maxHeight - EXPANDED_CHROME_HEIGHT) / NowPlayingSlabMetrics.height(true))
            .coerceIn(MIN_SLAB_SCALE, NowPlayingSlabMetrics.ExpandedScale)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: position in the queue, and a way out. The queue toggle
            // moved to the bottom row alongside radio and info.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (state.queue.size > 1) {
                    Text(
                        text = "${state.queueIndex + 1} of ${state.queue.size}",
                        fontFamily = NunitoFamily,
                        fontSize = 12.sp,
                        color = AgPalette.TextMetadata
                    )
                }
                Spacer(Modifier.weight(1f))
                AgBareIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close",
                    onClick = onClose,
                    touchSize = 36.dp,
                    iconSize = 20.dp,
                    tint = AgPalette.TextSecondary
                )
            }

            if (showQueue) {
                QueuePanel(
                    state = state,
                    onPlayQueueIndex = onPlayQueueIndex,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.height(4.dp))

                // 3D Slab (Replaces static art)
                NowPlayingSlab(
                    state = state,
                    fftData = fftData,
                    visualizerColors = visualizerColors,
                    // Only here: there is room, the tap target was doing nothing
                    // (the panel is already open), and nothing else wants the drag.
                    interactive = true,
                    sizeScale = slabScale,
                    onClick = { /* Already expanded */ }
                )

                Spacer(Modifier.height(16.dp))

                // Title and artist read left-to-right like text, with the like
                // control on the far right of the same line. Centred, the two
                // most-read strings on the panel moved for every track depending
                // on how long they happened to be.
                val artists = track.effectiveArtistDisplay?.split(Regex(", | & | feat\\. | featuring "), 3)
                    ?.take(2)
                    ?.joinToString(", ") ?: "Unknown Artist"

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track.effectiveTitle ?: "Unknown Title",
                            fontFamily = NunitoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = AgPalette.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artists,
                            fontFamily = NunitoFamily,
                            fontSize = 16.sp,
                            color = AgPalette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    AgBareIconButton(
                        icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isLiked) "Remove from Liked Songs" else "Add to Liked Songs",
                        onClick = onToggleLike,
                        iconSize = 22.dp,
                        tint = if (isLiked) accent else AgPalette.TextSecondary
                    )
                }

                // A fixed gap, not weight(1f). The weighted spacer is what
                // stretched the panel to its full reserved height and put an
                // empty band under the artist name.
                Spacer(Modifier.height(20.dp))
            }

            // ── Scrubber ──
            Scrubber(progressFlow = progressFlow, onSeek = onSeek)

            Spacer(Modifier.height(12.dp))

            // ── Transport ──
            //
            // Bare icons rather than frosted circles. Five bordered discs in a
            // row read as five competing objects next to the artwork; stripped
            // back, colour is free to mean state — shuffle on, repeat mode —
            // instead of decoration.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AgBareIconButton(
                    icon = Icons.Default.Shuffle,
                    contentDescription = if (state.shuffleEnabled) "Shuffle on" else "Shuffle off",
                    onClick = onToggleShuffle,
                    touchSize = 44.dp,
                    iconSize = 20.dp,
                    tint = if (state.shuffleEnabled) accent else AgPalette.TextSecondary
                )

                AgBareIconButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDescription = "Previous track",
                    onClick = onPrevious,
                    touchSize = 52.dp,
                    iconSize = 32.dp
                )

                // The one control that stays large, by size alone.
                AgBareIconButton(
                    icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    onClick = onTogglePlay,
                    touchSize = 64.dp,
                    iconSize = 52.dp,
                    tint = Color.White
                )

                AgBareIconButton(
                    icon = Icons.Default.SkipNext,
                    contentDescription = "Next track",
                    onClick = onNext,
                    touchSize = 52.dp,
                    iconSize = 32.dp,
                    enabled = state.hasNext
                )

                AgBareIconButton(
                    icon = if (state.repeatMode == PlaybackRepeat.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = when (state.repeatMode) {
                        PlaybackRepeat.OFF -> "Repeat off"
                        PlaybackRepeat.ALL -> "Repeat queue"
                        PlaybackRepeat.ONE -> "Repeat track"
                    },
                    onClick = onCycleRepeat,
                    touchSize = 44.dp,
                    iconSize = 20.dp,
                    tint = if (state.repeatMode == PlaybackRepeat.OFF) AgPalette.TextSecondary else accent
                )
            }

            Spacer(Modifier.height(18.dp))

            // ── Queue / Radio / Info ──
            //
            // Replaces a full-width "View release day" button. That route is not
            // lost: it moved into the metadata sheet Info opens, next to the
            // release date it acts on.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AgBareIconButton(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = if (showQueue) "Hide queue" else "Show queue",
                    onClick = { showQueue = !showQueue },
                    iconSize = 22.dp,
                    tint = if (showQueue) accent else AgPalette.TextSecondary
                )
                AgBareIconButton(
                    icon = Icons.Default.Radio,
                    contentDescription = "Radio",
                    // The most direct version of the gesture: a station from
                    // whatever is playing right now.
                    onClick = { playback.startRadio(track.trackId, RadioOrigin.TRACK) },
                    iconSize = 22.dp,
                    tint = AgPalette.TextSecondary
                )
                AgBareIconButton(
                    icon = Icons.Outlined.Info,
                    contentDescription = "Track info",
                    onClick = onOpenInfo,
                    iconSize = 22.dp,
                    tint = AgPalette.TextSecondary
                )
            }

            Spacer(Modifier.height(4.dp))

            Spacer(Modifier.navigationBarsPadding())
        }
      }
    }
}

/**
 * Combined height of everything on the expanded panel other than the cube:
 * header, titles, scrubber, transport row, the queue/radio/info row and insets.
 *
 * A measured estimate, rounded up. It only decides how much room the cube is
 * allowed, so erring high costs a little cube size and erring low would clip
 * controls — hence the generous rounding.
 */
private val EXPANDED_CHROME_HEIGHT = 330.dp

/**
 * Ceiling on the panel's height, as a fraction of the screen.
 *
 * Only reached when the queue is open — with it closed the panel wraps its
 * content and is typically well under this.
 */
private const val MAX_PANEL_FRACTION = 0.88f

/** Floor for the cube on short screens, below which it stops being the feature. */
private const val MIN_SLAB_SCALE = 0.85f

/**
 * What is playing next. Tapping a row jumps the row into play.
 */
@Composable
private fun QueuePanel(
    state: PlaybackState,
    onPlayQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalWallpaperAccent.current

    if (state.queue.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            AgEmptyState(title = "Queue is empty")
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(state.queue, key = { _, t -> t.trackId }) { index, item ->
            val isCurrent = index == state.queueIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isCurrent) {
                            Modifier.agGlassTinted(RoundedCornerShape(10.dp), tint = accent, opacity = 0.18f)
                        } else Modifier
                    )
                    .agPressable(onClick = { onPlayQueueIndex(index) }, pressScale = 0.985f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    fontFamily = NunitoFamily,
                    fontSize = 12.sp,
                    color = AgPalette.TextMetadata,
                    modifier = Modifier.width(28.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.effectiveTitle ?: "Unknown Title",
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (isCurrent) accent else AgPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.effectiveArtistDisplay ?: "Unknown Artist",
                        fontFamily = NunitoFamily,
                        fontSize = 12.sp,
                        color = AgPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isCurrent) {
                    NowPlayingBars(isPlaying = state.isPlaying, size = 14.dp)
                }
            }
        }
    }
}

/**
 * The seek bar and its two time labels — the only thing in the player that reads
 * the playback position.
 *
 * Its own composable purely so the 2Hz position updates invalidate this and
 * nothing else. Collected here rather than passed in from the caller, because a
 * parameter would move the state read back up into the caller's scope and undo
 * the point of the split.
 */
@Composable
private fun Scrubber(
    progressFlow: StateFlow<PlaybackProgress>,
    onSeek: (Float) -> Unit
) {
    val progress by progressFlow.collectAsState()
    AgSeekBar(
        progress = progress.fraction,
        onSeek = onSeek,
        enabled = progress.durationMs > 0
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatDuration(progress.positionMs),
            fontFamily = NunitoFamily,
            fontSize = 11.sp,
            color = AgPalette.TextMetadata
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (progress.durationMs > 0) formatDuration(progress.durationMs) else "--:--",
            fontFamily = NunitoFamily,
            fontSize = 11.sp,
            color = AgPalette.TextMetadata
        )
    }
}

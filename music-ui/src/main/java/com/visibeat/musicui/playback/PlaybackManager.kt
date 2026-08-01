package com.visibeat.musicui.playback

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.compose.runtime.Immutable
import androidx.media3.session.SessionToken
import com.visibeat.viewengine.TimelineItemRow
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How the queue behaves when it runs off the end. */
enum class PlaybackRepeat { OFF, ALL, ONE }

/**
 * What the visualiser is actually receiving.
 *
 * The cube is driven entirely by FFT frames, so when it stops moving there are
 * two very different explanations and no way to tell them apart by looking:
 * either no usable audio is reaching us, or the audio is fine and the
 * normalisation is flattening it. [summary] is written to distinguish exactly
 * those two.
 */
data class VisualizerDiagnostics(
    /** Session ids tried and how each went, oldest first. */
    val attempts: List<String> = emptyList(),
    val boundSessionId: Int? = null,
    /** Where the id came from — 0 in either slot means that source is empty. */
    val sessionIdFromService: Int = 0,
    val sessionIdFromExtras: Int = 0,
    val framesReceived: Long = 0,
    /** Frames whose bass bins were entirely zero. */
    val silentFrames: Long = 0,
    val minBass: Float = 0f,
    val maxBass: Float = 0f,
    val lastBass: Float = 0f
) {
    /** Spread between the quietest and loudest bass frame seen. */
    val bassRange: Float get() = (maxBass - minBass).coerceAtLeast(0f)

    val summary: String
        get() = when {
            // Prefer an attempt that names a reason over the bare "gave up" line.
            boundSessionId == null ->
                "No capture (service=$sessionIdFromService extras=$sessionIdFromExtras) — " +
                    (attempts.lastOrNull { it.contains(':') } ?: attempts.lastOrNull() ?: "not attempted")
            framesReceived == 0L -> "Bound to session $boundSessionId, no frames yet"
            silentFrames == framesReceived ->
                "Session $boundSessionId: $framesReceived frames, ALL SILENT (capture is a dud)"
            else -> "Session $boundSessionId: $framesReceived frames, " +
                "bass %.1f-%.1f (range %.1f), now %.1f".format(minBass, maxBass, bassRange, lastBass)
        }
}

/**
 * Where playback is inside the current track.
 *
 * Deliberately *not* part of [PlaybackState]. The position ticks twice a second,
 * and MainActivity reads playback state in the root composition scope — so with
 * the two combined, the entire UI was invalidated 2x/second for the whole time
 * music was playing. Nothing outside the scrubber cares about the position, so
 * it gets its own flow and only the scrubber collects it.
 */
@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0
) {
    /** 0..1, or 0 when the duration is not known yet. */
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Everything about playback that changes on a *event* rather than on a clock:
 * which track, playing or not, what the queue holds.
 *
 * @see PlaybackProgress for the part that ticks.
 */
@Immutable
data class PlaybackState(
    val currentTrack: TimelineItemRow? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: PlaybackRepeat = PlaybackRepeat.OFF,
    /** The full queue, in the order it was handed to the player. */
    val queue: List<TimelineItemRow> = emptyList(),
    val queueIndex: Int = -1
)

/**
 * Owns the MediaController and the queue the UI thinks in.
 *
 * The alpha pushed one MediaItem at a time, so a track that reached its end
 * simply stopped: ExoPlayer had nothing queued behind it and no listener asked
 * for the next one. Everything here hangs off giving the player the whole
 * playlist and letting it do the advancing, with [onMediaItemTransition] pulling
 * our own state back into sync afterwards.
 */
class PlaybackManager(context: Context) {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /**
     * Null until the session is connected, and null forever if it never can be.
     *
     * `isDone` is also true for a future that completed *exceptionally* — which is
     * what happens when the service cannot be bound — so calling `get()` on the
     * strength of `isDone` alone throws [java.util.concurrent.ExecutionException]
     * out of a `directExecutor` callback, on whatever thread media3 happened to
     * complete it on. Every caller here already handles a null controller.
     */
    private val controller: MediaController?
        get() {
            val future = controllerFuture ?: return null
            if (!future.isDone || future.isCancelled) return null
            return try {
                future.get()
            } catch (_: Exception) {
                null
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var positionTicker: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Ticks while playing. Collect this only where the position is actually shown. */
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /** Tracks the player is holding, index-aligned with the ExoPlayer timeline. */
    private var queue: List<TimelineItemRow> = emptyList()

    /** Set when a queue is handed over before the controller has connected. */
    private var pendingQueue: Pair<List<TimelineItemRow>, Int>? = null

    /**
     * Turns the media IDs already loaded in the session back into rows.
     *
     * The queue lives in this object, but the playback session outlives the
     * activity — rotate the phone, or come back to a still-playing app, and a
     * fresh PlaybackManager connects to a session it knows nothing about. Without
     * this the music kept playing while the mini player and queue showed empty.
     * The loader must return rows in the same order it was given IDs.
     */
    var queueRehydrator: (suspend (trackIds: List<Long>) -> List<TimelineItemRow>)? = null

    /**
     * Called once each time a track becomes the current one, whether by a skip,
     * a new queue, or the player advancing on its own.
     *
     * This is what records play history. Nothing in the app tracked playback
     * before, so every "recent" list was really ordered by ingest time.
     */
    var onTrackStarted: (suspend (trackId: Long) -> Unit)? = null

    /** Guards against reporting the same track twice for one playthrough. */
    private var lastReportedTrackId: Long? = null

    // Frequency data for visualizer
    private var visualizer: Visualizer? = null

    /** Which audio session the live Visualizer is attached to, if any. */
    private var visualizerSessionId: Int? = null
    private var knownAudioSessionId: Int = 0
    private val _fftData = MutableStateFlow(ByteArray(0))
    val fftData: StateFlow<ByteArray> = _fftData.asStateFlow()

    private val _diagnostics = MutableStateFlow(VisualizerDiagnostics())
    val diagnostics: StateFlow<VisualizerDiagnostics> = _diagnostics.asStateFlow()

    init {
        val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                /**
                 * The service republishes the audio session id here if the
                 * platform ever changes it. Without this listener a late id was
                 * never noticed and the visualiser stayed on whatever it had
                 * grabbed at connect time.
                 */
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    if (controller.isPlaying) setupVisualizer()
                }
            })
            .buildAsync()
        controllerFuture?.addListener({
            setupController()
        }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
        val controller = this.controller ?: return
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    setupVisualizer()
                    startPositionTicker()
                } else {
                    releaseVisualizer()
                    stopPositionTicker()
                    // One last read so a paused scrubber shows where it actually stopped.
                    syncFromController()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                knownAudioSessionId = audioSessionId
                // Real session ID now available — retry visualizer
                if (audioSessionId != 0 && visualizer == null) {
                    visualizerRetryCount = 0
                    setupVisualizer()
                }
            }

            /**
             * Fires both when the user skips and when a track ends and the player
             * moves itself along. This is what makes continuous playback work.
             */
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncFromController()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    isLoading = playbackState == Player.STATE_BUFFERING
                )
                syncFromController()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _state.value = _state.value.copy(shuffleEnabled = shuffleModeEnabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _state.value = _state.value.copy(repeatMode = repeatMode.toRepeatMode())
            }
        })

        // A queue set before the connection completed still needs to reach the player.
        val pending = pendingQueue
        if (pending != null) {
            pendingQueue = null
            setQueue(pending.first, pending.second)
        } else {
            rehydrateFromSession()
        }
        syncFromController()
        if (controller.isPlaying) startPositionTicker()
    }

    /**
     * Adopts a queue that was already playing when this manager connected.
     * No-op when we put the queue there ourselves.
     */
    private fun rehydrateFromSession() {
        val controller = this.controller ?: return
        if (queue.isNotEmpty()) return
        val count = controller.mediaItemCount
        if (count == 0) return
        val loader = queueRehydrator ?: return

        val ids = (0 until count).mapNotNull { controller.getMediaItemAt(it).mediaId.toLongOrNull() }
        // Anything less than a full set would misalign queue indices with the
        // player's timeline, which is worse than showing no queue at all.
        if (ids.size != count) return

        scope.launch {
            val rows = loader(ids)
            if (rows.size == count) {
                queue = rows
                syncFromController()
            }
        }
    }

    /**
     * Pulls index, duration, position and skip availability off the controller.
     * Called from every listener callback rather than each one copying its own
     * subset, so the state can't end up half-updated.
     */
    private fun syncFromController() {
        val controller = this.controller ?: return
        val index = controller.currentMediaItemIndex
        val track = queue.getOrNull(index)
        val duration = controller.duration.let { if (it == C.TIME_UNSET) 0L else it }
        _state.value = _state.value.copy(
            currentTrack = track ?: _state.value.currentTrack,
            isPlaying = controller.isPlaying,
            queue = queue,
            queueIndex = if (queue.isEmpty()) -1 else index,
            durationMs = duration,
            hasNext = controller.hasNextMediaItem(),
            hasPrevious = controller.hasPreviousMediaItem(),
            shuffleEnabled = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode.toRepeatMode()
        )
        publishProgress(controller, duration)

        // syncFromController runs on every listener callback and twice a second
        // from the ticker, so the report is gated on the track actually changing.
        if (track != null && track.trackId != lastReportedTrackId) {
            lastReportedTrackId = track.trackId
            onTrackStarted?.let { report ->
                scope.launch { report(track.trackId) }
            }
        }
    }

    private fun publishProgress(controller: MediaController, duration: Long) {
        _progress.value = PlaybackProgress(
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = duration
        )
    }

    /**
     * Writes to [_progress] only. It used to tick [_state], which is read in the
     * host's root composition scope — so every tick invalidated the whole UI.
     */
    private fun startPositionTicker() {
        if (positionTicker?.isActive == true) return
        positionTicker = scope.launch {
            while (isActive) {
                val controller = this@PlaybackManager.controller
                if (controller != null) {
                    publishProgress(
                        controller,
                        controller.duration.let { if (it == C.TIME_UNSET) 0L else it }
                    )
                }
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTicker?.cancel()
        positionTicker = null
    }

    // ── Queue control ─────────────────────────────────────

    /**
     * Replace the queue and start at [startIndex].
     *
     * Tracks without a playable URI are dropped rather than left as holes, and
     * [startIndex] is re-resolved against the filtered list so tapping the third
     * row still starts the third row.
     */
    fun setQueue(tracks: List<TimelineItemRow>, startIndex: Int = 0) {
        val resolved = QueueResolver.resolve(tracks, startIndex) ?: return
        val (playable, resolvedIndex) = resolved

        val controller = this.controller
        if (controller == null) {
            // Connection still in flight — replay this once it lands.
            pendingQueue = tracks to startIndex
            queue = playable
            _state.value = _state.value.copy(
                currentTrack = playable[resolvedIndex],
                queue = playable,
                queueIndex = resolvedIndex,
                isLoading = true
            )
            return
        }

        queue = playable
        controller.setMediaItems(playable.map { it.toMediaItem() }, resolvedIndex, 0L)
        controller.prepare()
        controller.play()
        syncFromController()
    }

    /** Play a single track (clears queue). */
    fun playTrack(track: TimelineItemRow) = setQueue(listOf(track), 0)

    /** Play [track] with [queueTracks] as the surrounding context. */
    fun playTrackWithQueue(track: TimelineItemRow, queueTracks: List<TimelineItemRow>) {
        val index = queueTracks.indexOfFirst { it.trackId == track.trackId }
        if (index < 0) {
            setQueue(listOf(track) + queueTracks, 0)
        } else {
            setQueue(queueTracks, index)
        }
    }

    /**
     * Start [tracks] in shuffled order.
     *
     * The list is handed over in its natural order and ExoPlayer's own shuffle
     * mode does the reordering, so turning shuffle back off mid-listen returns
     * to the real album sequence instead of a scrambled one.
     */
    fun shuffleQueue(tracks: List<TimelineItemRow>) {
        val playable = tracks.filter { !it.mediaStoreUri.isNullOrBlank() }
        if (playable.isEmpty()) return
        controller?.shuffleModeEnabled = true
        _state.value = _state.value.copy(shuffleEnabled = true)
        setQueue(playable, playable.indices.random())
    }

    /** Append to the end of the current queue without interrupting playback. */
    fun addToQueue(tracks: List<TimelineItemRow>) {
        val playable = tracks.filter { !it.mediaStoreUri.isNullOrBlank() }
        if (playable.isEmpty()) return
        val controller = this.controller
        if (controller == null || queue.isEmpty()) {
            setQueue(playable, 0)
            return
        }
        queue = queue + playable
        controller.addMediaItems(playable.map { it.toMediaItem() })
        syncFromController()
    }

    /** Jump to a position in the current queue. */
    fun playQueueIndex(index: Int) {
        val controller = this.controller ?: return
        if (index !in queue.indices) return
        controller.seekTo(index, 0L)
        controller.play()
        syncFromController()
    }

    // ── Transport ─────────────────────────────────────────

    fun skipToNext() {
        val controller = this.controller ?: return
        // Under REPEAT_MODE_ALL the player reports a next item at the end of the
        // queue and wraps for us, so this covers looping too.
        if (!controller.hasNextMediaItem()) return
        controller.seekToNextMediaItem()
        controller.play()
        syncFromController()
    }

    /**
     * Restarts the current track if the user is more than [RESTART_THRESHOLD_MS]
     * into it — the behaviour every other music player has, and the reason a
     * plain "previous" felt broken on long tracks.
     */
    fun skipToPrevious() {
        val controller = this.controller ?: return
        if (controller.currentPosition > RESTART_THRESHOLD_MS || !controller.hasPreviousMediaItem()) {
            controller.seekTo(0L)
        } else {
            controller.seekToPreviousMediaItem()
        }
        controller.play()
        syncFromController()
    }

    fun togglePlayPause() {
        val controller = this.controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    /**
     * The duration check is not decoration: [C.TIME_UNSET] is `Long.MIN_VALUE + 1`,
     * so clamping against it unguarded turns a step forward into a seek to a large
     * negative position whenever the duration is not known yet.
     */
    fun seekForward() {
        val controller = this.controller ?: return
        val target = controller.currentPosition + SEEK_STEP_MS
        val duration = controller.duration
        controller.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        syncFromController()
    }

    fun seekBackward() {
        val controller = this.controller ?: return
        controller.seekTo((controller.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
        syncFromController()
    }

    /** Seek to a 0..1 fraction of the current track. */
    fun seekToFraction(fraction: Float) {
        val controller = this.controller ?: return
        val duration = controller.duration
        if (duration <= 0) return
        controller.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        syncFromController()
    }

    fun toggleShuffle() {
        val controller = this.controller ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
        _state.value = _state.value.copy(shuffleEnabled = controller.shuffleModeEnabled)
    }

    /** Cycles OFF → ALL → ONE → OFF. */
    fun cycleRepeatMode() {
        val controller = this.controller ?: return
        val next = when (_state.value.repeatMode) {
            PlaybackRepeat.OFF -> PlaybackRepeat.ALL
            PlaybackRepeat.ALL -> PlaybackRepeat.ONE
            PlaybackRepeat.ONE -> PlaybackRepeat.OFF
        }
        controller.repeatMode = next.toPlayerRepeatMode()
        _state.value = _state.value.copy(repeatMode = next)
    }

    fun setVolume(volume: Float) {
        val controller = this.controller ?: return
        controller.volume = volume
    }

    /** True when [trackId] is the track currently loaded in the player. */
    fun isCurrent(trackId: Long): Boolean = _state.value.currentTrack?.trackId == trackId

    // ── Visualizer ────────────────────────────────────────

    private var visualizerRetryCount = 0
    private val maxVisualizerRetries = 10

    /**
     * The stream the visualiser should be capturing.
     *
     * The session extras are authoritative: the playback service publishes the
     * id it gave ExoPlayer. A MediaController never receives
     * [Player.Listener.onAudioSessionIdChanged] — that is an ExoPlayer-level
     * callback and does not cross the session boundary — so
     * [knownAudioSessionId] is only a fallback in case a future media3 starts
     * forwarding it.
     */
    private fun extrasSessionId(): Int =
        controller?.sessionExtras?.getInt(KEY_AUDIO_SESSION_ID, 0) ?: 0

    /**
     * The service's own field comes first. Reading the id back out of the
     * session extras returned 0 on device — the extras set at build time were
     * not visible to the controller — which sent the visualiser to the output
     * mix, where construction fails outright.
     */
    private fun sessionAudioSessionId(): Int = VisualizerBinding.preferredSessionId(
        listOf(
            MediaPlaybackService.audioSessionId,
            extrasSessionId(),
            knownAudioSessionId
        )
    )

    private fun setupVisualizer() {
        val preferred = sessionAudioSessionId()
        _diagnostics.value = _diagnostics.value.copy(
            sessionIdFromService = MediaPlaybackService.audioSessionId,
            sessionIdFromExtras = extrasSessionId()
        )
        if (!VisualizerBinding.shouldRebind(visualizerSessionId, preferred)) return

        // Swapping to a better session id: keep the last levels on screen so the
        // cube does not flatten during the handover.
        if (visualizer != null) releaseVisualizer(clearData = false)

        for (id in VisualizerBinding.candidates(preferred)) {
            if (tryCreateVisualizer(id)) return
        }

        noteAttempt(preferred, "all candidates failed, retry #$visualizerRetryCount")
        retryVisualizerSetup()
    }

    private fun tryCreateVisualizer(audioSessionId: Int): Boolean {
        var viz: Visualizer? = null
        return try {
            viz = Visualizer(audioSessionId)
            viz.captureSize = Visualizer.getCaptureSizeRange()[1]
            viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    val data = fft?.copyOf() ?: ByteArray(0)
                    _fftData.value = data
                    recordFrame(data)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            viz.enabled = true
            visualizer = viz
            visualizerSessionId = audioSessionId
            visualizerRetryCount = 0
            noteAttempt(audioSessionId, "bound")
            true
        } catch (e: Exception) {
            // Release partially-constructed Visualizer to avoid native resource leak
            try { viz?.release() } catch (_: Exception) { }
            noteAttempt(audioSessionId, e.javaClass.simpleName + ": " + (e.message ?: "failed"))
            false
        }
    }

    private fun noteAttempt(audioSessionId: Int, outcome: String) {
        val label = "session $audioSessionId -> $outcome"
        android.util.Log.i(VIZ_LOG_TAG, label)
        _diagnostics.value = _diagnostics.value.copy(
            attempts = (_diagnostics.value.attempts + label).takeLast(6),
            boundSessionId = if (outcome == "bound") audioSessionId else _diagnostics.value.boundSessionId
        )
    }

    /**
     * Measures each captured frame with the same function the cube uses, so the
     * numbers on screen are the numbers driving the animation.
     */
    private fun recordFrame(fft: ByteArray) {
        val bass = CubeGeometry.bassMagnitude(fft)
        val current = _diagnostics.value
        val first = current.framesReceived == 0L
        _diagnostics.value = current.copy(
            framesReceived = current.framesReceived + 1,
            silentFrames = current.silentFrames + if (bass <= 0f) 1 else 0,
            minBass = if (first) bass else minOf(current.minBass, bass),
            maxBass = if (first) bass else maxOf(current.maxBass, bass),
            lastBass = bass
        )
    }

    /**
     * Call when RECORD_AUDIO permission is granted to set up the visualizer.
     */
    fun onRecordAudioGranted() {
        visualizerRetryCount = 0
        setupVisualizer()
    }

    private fun retryVisualizerSetup() {
        if (visualizerRetryCount >= maxVisualizerRetries) return
        visualizerRetryCount++
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (visualizer == null) {
                setupVisualizer()
            }
        }, 500L * visualizerRetryCount)
    }

    /**
     * @param clearData false when rebinding to a better session id, so the cube
     *   keeps its last levels across the swap instead of snapping to flat.
     */
    private fun releaseVisualizer(clearData: Boolean = true) {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
        visualizerSessionId = null
        visualizerRetryCount = 0
        if (clearData) _fftData.value = ByteArray(0)
    }

    fun release() {
        stopPositionTicker()
        scope.cancel()
        releaseVisualizer()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controllerFuture = null
    }

    companion object {
        /** `adb logcat -s VisiBeatViz` to watch capture binding. */
        private const val VIZ_LOG_TAG = "VisiBeatViz"
        private const val POSITION_TICK_MS = 500L
        private const val SEEK_STEP_MS = 10_000L
        private const val RESTART_THRESHOLD_MS = 3_000L
    }
}

private fun TimelineItemRow.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(trackId.toString())
        .setUri(mediaStoreUri)
        .build()

private fun Int.toRepeatMode(): PlaybackRepeat = when (this) {
    Player.REPEAT_MODE_ONE -> PlaybackRepeat.ONE
    Player.REPEAT_MODE_ALL -> PlaybackRepeat.ALL
    else -> PlaybackRepeat.OFF
}

private fun PlaybackRepeat.toPlayerRepeatMode(): Int = when (this) {
    PlaybackRepeat.OFF -> Player.REPEAT_MODE_OFF
    PlaybackRepeat.ALL -> Player.REPEAT_MODE_ALL
    PlaybackRepeat.ONE -> Player.REPEAT_MODE_ONE
}

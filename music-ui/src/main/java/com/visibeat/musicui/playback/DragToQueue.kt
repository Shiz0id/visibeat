package com.visibeat.musicui.playback

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.visibeat.viewengine.TimelineItemRow

/** What is being dragged towards the queue. */
@Immutable
sealed interface QueueDragPayload {
    val label: String
    val subtitle: String?
    val artModel: Any?

    @Immutable
    data class Track(val track: TimelineItemRow) : QueueDragPayload {
        override val label get() = track.effectiveTitle ?: "Unknown Title"
        override val subtitle get() = track.effectiveArtistDisplay
        override val artModel get() = track.artModel
    }

    @Immutable
    data class Album(
        val releaseId: Long,
        override val label: String,
        override val subtitle: String?,
        override val artModel: Any?,
        val trackCount: Int
    ) : QueueDragPayload

}

/**
 * Drag-to-queue, held for the whole app rather than for a screen.
 *
 * The drag source and the drop target are in different subtrees — a row inside
 * the timeline's `AnimatedContent`, and the slab, which is a sibling of it in the
 * activity's root `Box`. Neither can see the other, so the gesture cannot be
 * expressed by wiring one composable to another; the state has to live above
 * both. Same reasoning as [LocalPlayback].
 *
 * Compose 1.6 does ship `Modifier.dragAndDropSource`, but it is built on the
 * platform's cross-application `DragEvent`/`ClipData` machinery. For moving a
 * domain object a few hundred pixels inside one screen that is a lot of ceremony
 * and very little control over how the dragged thing looks.
 */
@Stable
class DragToQueueController {

    /** Null when nothing is being dragged. */
    var payload by mutableStateOf<QueueDragPayload?>(null)
        private set

    /** Finger position in root coordinates. */
    var position by mutableStateOf(Offset.Zero)
        private set

    /**
     * The slab's bounds, published by whoever draws it.
     *
     * The slab is pinned while the timeline is open precisely so this stays
     * still — a target that moves while you aim at it is not a target.
     */
    var targetBounds by mutableStateOf(Rect.Zero)

    val isOverTarget: Boolean
        get() = payload != null && !targetBounds.isEmpty && targetBounds.contains(position)

    fun start(payload: QueueDragPayload, at: Offset) {
        this.payload = payload
        this.position = at
    }

    fun move(to: Offset) {
        if (payload != null) position = to
    }

    /** @return what was dropped on the target, or null if it was dropped elsewhere. */
    fun drop(): QueueDragPayload? {
        val hit = if (isOverTarget) payload else null
        payload = null
        return hit
    }

    fun cancel() {
        payload = null
    }
}

/**
 * Defaults to a controller nobody listens to, so a row can offer the gesture
 * without every screen having to provide one.
 */
val LocalDragToQueue = compositionLocalOf { DragToQueueController() }

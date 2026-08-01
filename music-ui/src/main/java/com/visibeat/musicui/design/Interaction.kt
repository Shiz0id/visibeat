package com.visibeat.musicui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The app's single touch-response primitive.
 *
 * Glass has weight: pressing it should push it away from you slightly, and a
 * long-press — which is how the app reveals track detail and expanded grids —
 * should confirm itself in the hand before anything moves on screen. Both were
 * missing everywhere outside AgChip, which is most of why the alpha felt inert.
 *
 * @param pressScale how far the surface recedes under a finger. Large targets
 *   want less travel than small ones or they look rubbery.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.agPressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    pressScale: Float = 0.97f
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "pressScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick?.let {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    it()
                }
            }
        )
}

/**
 * A row you can tap, or lift out with a long press and carry somewhere.
 *
 * One gesture loop decides which it was. Stacking [agPressable] under a separate
 * drag detector does not work: both track the same pointer and only communicate
 * through consumption, so a long press released without moving consumes nothing
 * and the tap fires too — the row acts on a gesture the user abandoned.
 *
 * Once the press becomes a lift, this claims the pointer on
 * [PointerEventPass.Initial], which runs outside-in. Nothing nested inside the
 * row and nothing above it — a play button, an artwork thumbnail, the list
 * scrolling under the finger — sees the rest of the gesture. That is what lets a
 * row carry its own small tap targets and still be a single drag handle.
 *
 * Offsets given to the drag callbacks are in root coordinates. A drop target is
 * somewhere else entirely in the hierarchy, so local coordinates say nothing
 * about where it is. They are measured ahead of the press transform below, or
 * the spring would drift the point the drag is reckoned from.
 */
fun Modifier.agDraggableRow(
    onClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    enabled: Boolean = true,
    pressScale: Float = 0.97f
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val origin = remember { RootOrigin() }

    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "pressScale"
    )

    this
        .onGloballyPositioned { origin.value = it.positionInRoot() }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pressed = true
                try {
                    var position = down.position
                    // Set when the gesture resolved before the long-press timer.
                    var settled = false
                    var isTap = false

                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes
                                .firstOrNull { it.id == down.id }
                            if (change == null) {
                                settled = true
                                break
                            }
                            position = change.position
                            if (change.changedToUpIgnoreConsumed()) {
                                settled = true
                                isTap = !change.isConsumed
                                // Claim the tap. This runs on Main, which is
                                // inside-out, so an enclosing clickable — a month
                                // tile sitting inside a year card — sees it as
                                // taken and does not also fire.
                                if (isTap) change.consume()
                                break
                            }
                            val outside = position.x < 0f || position.y < 0f ||
                                position.x > size.width || position.y > size.height
                            if (change.isConsumed || outside) {
                                settled = true
                                break
                            }
                        }
                    }

                    when {
                        // Lifted in time, and nothing nested took it first.
                        settled && isTap -> onClick()
                        // Lifted in time but claimed elsewhere, or dragged out of
                        // the row — the list scrolling under the finger is the
                        // usual case. Nothing to do.
                        settled -> Unit
                        else -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDragStart(origin.value + position)
                            var completed = false
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.firstOrNull { it.id == down.id } ?: break
                                val up = change.changedToUpIgnoreConsumed()
                                change.consume()
                                if (up) {
                                    completed = true
                                    break
                                }
                                onDrag(origin.value + change.position)
                            }
                            if (completed) onDragEnd() else onDragCancel()
                        }
                    }
                } finally {
                    pressed = false
                }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Where the row is, in root coordinates.
 *
 * Deliberately not snapshot state: it is written on every layout pass and read
 * only from pointer callbacks, never from composition.
 */
private class RootOrigin {
    var value: Offset = Offset.Zero
}

/**
 * Fires a short confirmation tick. Used by transport controls, where the visual
 * change (a play glyph becoming a pause glyph) is too small to read as feedback
 * on its own.
 */
@androidx.compose.runtime.Composable
fun rememberTickHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}

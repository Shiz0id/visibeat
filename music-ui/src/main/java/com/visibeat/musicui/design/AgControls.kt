package com.visibeat.musicui.design

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Shared controls for the Aero surface language. Every screen was hand-rolling
 * its own frosted circle and its own "nothing here" block; these are the single
 * versions of each.
 */

// -----------------------------------------------------
// Frosted circular icon button
// -----------------------------------------------------
@Composable
fun AgIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = AgPalette.TextPrimary,
    opacity: Float = 0.12f,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(size)
            .agGlass(RoundedCornerShape(50), opacity = if (enabled) opacity else opacity * 0.5f)
            .agPressable(onClick = onClick, enabled = enabled, pressScale = 0.90f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * An icon and nothing else.
 *
 * [AgIconButton] wraps everything in a frosted circle, which is right for
 * controls scattered over content but wrong where a whole cluster of them sits
 * together: five bordered circles in a transport row read as five objects
 * competing with the artwork rather than as one set of controls. This keeps the
 * touch target and the press animation and drops the chrome, so colour is free
 * to mean *state* — shuffle on, repeat mode, liked — instead of decoration.
 */
@Composable
fun AgBareIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    touchSize: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    tint: Color = AgPalette.TextPrimary,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(touchSize)
            .agPressable(onClick = onClick, enabled = enabled, pressScale = 0.86f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.28f),
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * The accented primary action — play/shuffle. Tinted with the wallpaper accent so
 * it reads as "the thing to press" against a field of neutral glass.
 */
@Composable
fun AgAccentButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    label: String? = null
) {
    val accent = LocalWallpaperAccent.current
    val shape = RoundedCornerShape(50)

    if (label == null) {
        Box(
            modifier = modifier
                .size(size)
                .agGlassTinted(shape, tint = accent, opacity = 0.30f)
                .agPressable(onClick = onClick, pressScale = 0.90f),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
        }
    } else {
        Row(
            modifier = modifier
                .height(size)
                .agGlassTinted(shape, tint = accent, opacity = 0.30f)
                .agPressable(onClick = onClick, pressScale = 0.95f)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
            Text(
                text = label,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color.White
            )
        }
    }
}

// -----------------------------------------------------
// Now-playing indicator
// -----------------------------------------------------
/**
 * Three bars that bounce while a track plays and rest flat when it is paused.
 * Lists had no way at all to show which row was live.
 */
@Composable
fun NowPlayingBars(
    modifier: Modifier = Modifier,
    color: Color = LocalWallpaperAccent.current,
    isPlaying: Boolean = true,
    barCount: Int = 3,
    size: Dp = 16.dp
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val phases = List(barCount) { index ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 420 + index * 130,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "eqBar$index"
        )
    }

    Canvas(modifier = modifier.size(size)) {
        val gap = this.size.width / (barCount * 2f - 1f)
        val barWidth = gap
        for (i in 0 until barCount) {
            val fraction = if (isPlaying) phases[i].value else 0.22f
            val barHeight = this.size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), this.size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

// -----------------------------------------------------
// Seek / progress bar
// -----------------------------------------------------
/**
 * Glass scrubber. Drag anywhere on the track to seek; [onSeek] receives a
 * 0..1 fraction and only fires when the finger lifts, so the caller is not
 * spammed with seeks mid-drag.
 */
@Composable
fun AgSeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    enabled: Boolean = true
) {
    val accent = LocalWallpaperAccent.current
    var widthPx by remember { mutableIntStateOf(1) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val shown = (dragFraction ?: progress).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            // Tap-to-seek, in its own pointerInput because a single one runs only
            // the first detector it is given. Without this the bar responded to
            // drags and silently ignored taps, which on a scrubber reads as
            // broken rather than as unsupported.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onSeek((offset.x / widthPx).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let(onSeek)
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val r = CornerRadius(this.size.height / 2f)
            // Track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.15f),
                cornerRadius = r
            )
            // Filled portion, lit from the top like the rest of the material
            if (shown > 0f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.9f),
                        1f to accent
                    ),
                    size = Size(this.size.width * shown, this.size.height),
                    cornerRadius = r
                )
            }
        }
        // Thumb
        if (enabled) {
            val density = LocalDensity.current
            val thumbX = with(density) {
                ((widthPx * shown) - 6.dp.toPx()).coerceAtLeast(0f).toDp()
            }
            Box(Modifier.fillMaxWidth().height(24.dp)) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = thumbX)
                        .size(12.dp)
                        .agGlass(RoundedCornerShape(50), opacity = 0.9f)
                )
            }
        }
    }
}

// -----------------------------------------------------
// Section header with optional trailing action
// -----------------------------------------------------
@Composable
fun AgSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = AgPalette.TextPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontFamily = NunitoFamily,
                    fontSize = 12.sp,
                    color = AgPalette.TextMetadata
                )
            }
        }
        trailing?.invoke()
    }
}

// -----------------------------------------------------
// Empty state with a way out
// -----------------------------------------------------
/**
 * Every empty state in the alpha was a dead sentence. If there is something the
 * user can do about it, [actionLabel] puts that action in reach.
 */
@Composable
fun AgEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = title,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = AgPalette.TextPrimary,
                textAlign = TextAlign.Center
            )
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    fontFamily = NunitoFamily,
                    fontSize = 14.sp,
                    color = AgPalette.TextMetadata,
                    textAlign = TextAlign.Center
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .agGlass(RoundedCornerShape(12.dp), opacity = 0.12f)
                        .agPressable(onClick = onAction, pressScale = 0.95f)
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = actionLabel,
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = AgPalette.TextPrimary
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------
// Segmented glass control
// -----------------------------------------------------
/**
 * A row of mutually exclusive options that reads as one physical switch rather
 * than a bag of chips. Used for the timeline's bucket granularity, where the
 * old single cycling chip hid two of the three choices at any moment.
 */
@Composable
fun <T> AgSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    val accent = LocalWallpaperAccent.current
    val outerShape = RoundedCornerShape(999.dp)

    Row(
        modifier = modifier
            .agGlass(outerShape, opacity = 0.10f)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .then(
                        if (isSelected) {
                            Modifier.agGlassTinted(outerShape, tint = accent, opacity = 0.28f)
                        } else Modifier
                    )
                    .agPressable(onClick = { onSelect(option) }, pressScale = 0.94f)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label(option),
                    fontFamily = NunitoFamily,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = if (isSelected) Color.White else AgPalette.TextSecondary
                )
            }
        }
    }
}

// -----------------------------------------------------
// Settings rows
// -----------------------------------------------------
/**
 * One line of settings: a glyph, a label, an optional explanation, and either a
 * trailing control or a tap target.
 *
 * [subtitle] is where a setting says what it actually does. The drawer these
 * replaced offered bare labels like "Regenerate DB" with no hint that it wiped
 * the library.
 */
@Composable
fun AgSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val titleColor = when {
        !enabled -> AgPalette.TextMetadata
        destructive -> Color(0xFFEF4444)
        else -> AgPalette.TextPrimary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .agGlass(RoundedCornerShape(14.dp), opacity = 0.05f)
            .then(
                if (onClick != null) {
                    Modifier.agPressable(onClick = onClick, enabled = enabled, pressScale = 0.99f)
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = titleColor
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontFamily = NunitoFamily,
                    fontSize = 12.sp,
                    color = AgPalette.TextSecondary
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A settings row whose control is a switch. The whole row toggles, not just the
 * switch — a 40dp target beside a full-width row is a needless miss.
 */
@Composable
fun AgToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null
) {
    AgSettingRow(
        title = title,
        modifier = modifier,
        icon = icon,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = { AgSwitch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

/**
 * Glass switch. Material's Switch would be the only stock-Android control left
 * in the app and reads as a foreign object against the rest of the material.
 */
@Composable
fun AgSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalWallpaperAccent.current
    val trackShape = RoundedCornerShape(999.dp)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "switchThumb"
    )

    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .then(
                if (checked) {
                    Modifier.agGlassTinted(trackShape, tint = accent, opacity = 0.45f)
                } else {
                    Modifier.agGlass(trackShape, opacity = 0.08f)
                }
            )
            .agPressable(onClick = { onCheckedChange(!checked) }, pressScale = 0.94f)
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(22.dp)
                .agGlass(RoundedCornerShape(999.dp), opacity = if (checked) 0.95f else 0.55f)
        )
    }
}

// -----------------------------------------------------
// Confirmation
// -----------------------------------------------------
/**
 * Generic confirm-or-cancel dialog for anything irreversible.
 */
@Composable
fun AgConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false
) {
    Dialog(onDismissRequest = onDismiss) {
        AgAcrylicSurface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = AgPalette.TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    fontFamily = NunitoFamily,
                    fontSize = 14.sp,
                    color = AgPalette.TextSecondary
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ConfirmButton(dismissLabel, onDismiss, Modifier.weight(1f))
                    ConfirmButton(
                        confirmLabel,
                        onConfirm,
                        Modifier.weight(1f),
                        tint = if (destructive) Color(0xFFEF4444) else LocalWallpaperAccent.current
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .then(
                if (tint != null) Modifier.agGlassTinted(shape, tint = tint, opacity = 0.28f)
                else Modifier.agGlass(shape, opacity = 0.06f)
            )
            .agPressable(onClick = onClick, pressScale = 0.96f)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = AgPalette.TextPrimary
        )
    }
}

// -----------------------------------------------------
// Duration formatting
// -----------------------------------------------------
/**
 * m:ss, or h:mm:ss past an hour. Negative and unknown durations render as "--:--"
 * so a track whose length ExoPlayer has not resolved yet does not show "0:00".
 */
fun formatDuration(millis: Long): String {
    if (millis < 0) return "--:--"
    val totalSeconds = millis / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

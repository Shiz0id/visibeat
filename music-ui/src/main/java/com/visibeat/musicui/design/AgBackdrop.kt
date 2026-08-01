package com.visibeat.musicui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * The luminous plane everything else floats over.
 *
 * The app's window background is a plain light Material theme, and the wallpaper
 * image is only drawn once the user has picked one. Until then every screen was
 * white text on white — the whole UI was invisible on first run. This is the
 * floor beneath the glass: it renders whether or not a wallpaper exists, so a
 * fresh install looks like the app rather than a blank page.
 *
 * Visually it is the Frutiger Aero move: a deep cool base with two soft light
 * blooms drifting slowly through it, so the glass above always has something
 * with colour and gradient to refract.
 */
@Composable
fun AgAmbientBackdrop(
    modifier: Modifier = Modifier,
    animated: Boolean = true
) {
    val accent = LocalWallpaperAccent.current

    // One slow phase drives both blooms. A 40-second cycle is under the
    // threshold where drift reads as motion, which keeps it from competing
    // with the visualiser for attention.
    val transition = rememberInfiniteTransition(label = "backdropDrift")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 40_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Base plane: deep space at the top settling into graphite at the bottom.
        drawRect(
            brush = Brush.verticalGradient(
                0f to AgPalette.DeepSpace,
                0.55f to Color(0xFF101820),
                1f to AgPalette.Graphite
            ),
            size = size
        )

        // Upper bloom, tinted by whatever accent the wallpaper (or the default)
        // gave us, so the backdrop and the chrome always agree on a hue.
        val driftX = cos(phase * Math.PI).toFloat()
        val driftY = sin(phase * Math.PI).toFloat()

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.30f),
                    accent.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = Offset(w * (0.25f + 0.10f * driftX), h * (0.12f + 0.05f * driftY)),
                radius = maxOf(w, h) * 0.75f
            ),
            size = size
        )

        // Lower bloom in a cooler green-teal — the other half of the Aero pairing.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF3FB7A6).copy(alpha = 0.18f),
                    Color(0xFF3FB7A6).copy(alpha = 0.05f),
                    Color.Transparent
                ),
                center = Offset(w * (0.82f - 0.12f * driftX), h * (0.78f - 0.06f * driftY)),
                radius = maxOf(w, h) * 0.65f
            ),
            size = size
        )

        // Vignette, so glass panels near the edges keep their contrast.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                center = Offset(w / 2f, h / 2f),
                radius = maxOf(w, h) * 0.7f
            ),
            size = Size(w, h)
        )
    }
}

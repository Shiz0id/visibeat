package com.visibeat.musicui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Timeline is centered vertically. Scroll = time."
 * Luminous dashed rail with soft glow.
 */
@Composable
fun AgTimelineSpine(
    modifier: Modifier = Modifier,
    alpha: Float = 0.30f,
    strokeWidth: Float = 3f
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.width((strokeWidth + 6).dp).fillMaxHeight()) {
            val centerX = size.width / 2f
            val stroke = strokeWidth.dp.toPx()

            // Soft glow behind the line
            drawLine(
                color = Color.White.copy(alpha = alpha * 0.25f),
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = stroke * 3f,
                cap = StrokeCap.Round
            )

            // Main dashed line
            val dashOn = 8.dp.toPx()
            val dashOff = 6.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = alpha),
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
            )
        }
    }
}

/**
 * A persistent node on the spine (e.g. for month/day marking).
 */
@Composable
fun AgTimelineNode(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Renders text vertically (character by character) on the spine.
 * ExtraBold with subtle text shadow for presence.
 */
@Composable
fun VerticalStackText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge,
    fontWeight: FontWeight = FontWeight.ExtraBold,
    color: Color = MaterialTheme.colorScheme.onSurface,
    alpha: Float = 1.0f,
    lineHeight: androidx.compose.ui.unit.TextUnit = 24.sp,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 1.sp
) {
    Column(
        modifier = modifier.graphicsLayer {
            shadowElevation = 4.dp.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        text.forEach { char ->
            Text(
                text = char.toString(),
                style = style.copy(
                    fontFamily = NunitoFamily,
                    letterSpacing = letterSpacing,
                    lineHeight = lineHeight
                ),
                fontWeight = fontWeight,
                color = color.copy(alpha = alpha)
            )
        }
    }
}

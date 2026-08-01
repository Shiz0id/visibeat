package com.visibeat.musicui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Chips stack, combine, and reorder freely."
 * Aero glass pill with press animation and wallpaper-accent selection.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgChip(
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "chipScale"
    )

    val accent = LocalWallpaperAccent.current

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .agGlass(
                shape = RoundedCornerShape(999.dp),
                opacity = if (selected) 0.14f else 0.12f
            )
            .then(
                if (selected) {
                    Modifier.agGlassBorder(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.5f)
                    )
                } else Modifier
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = if (selected) accent else AgPalette.TextSecondary
        )
    }
}

/**
 * Helper: colored border for selected state.
 */
fun Modifier.agGlassBorder(
    shape: RoundedCornerShape = RoundedCornerShape(999.dp),
    color: androidx.compose.ui.graphics.Color
) = this.then(
    Modifier.border(
        width = 1.dp,
        color = color,
        shape = shape
    )
)

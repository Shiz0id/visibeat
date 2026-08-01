package com.visibeat.musicui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class NavDestination { HOME, TIMELINE, SEARCH, LIBRARY }

data class NavItem(
    val destination: NavDestination,
    val label: String,
    val icon: ImageVector
)

/**
 * Aero glass bottom navigation bar.
 * Translucent glass material with wallpaper-accent selected state.
 */
@Composable
fun BottomNavBar(
    items: List<NavItem>,
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalWallpaperAccent.current
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
    ) {
        // Glass background layers
        Box(
            Modifier
                .matchParentSize()
                .blur(24.dp)
                .agGlass(shape = shape, opacity = 0.12f)
        )
        // Extra darkening for legibility
        Box(
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.35f), shape = shape)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = item.destination == selected
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.05f else 1f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                    label = "navScale"
                )
                val alpha by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.55f,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
                    label = "navAlpha"
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .agPressable(
                            onClick = { onSelect(item.destination) },
                            pressScale = 0.90f
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (isSelected) accent else Color.White.copy(alpha = alpha),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        fontFamily = NunitoFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = if (isSelected) accent else Color.White.copy(alpha = alpha)
                    )
                }
            }
        }
    }
}

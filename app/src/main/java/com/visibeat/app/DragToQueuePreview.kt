package com.visibeat.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.QueueDragPayload
import kotlin.math.roundToInt

/**
 * The thing under your finger while dragging music to the queue.
 *
 * Held slightly above and left of the touch point so the finger is not covering
 * what it is carrying, and lifted off the surface so it reads as picked up
 * rather than as part of the list it came from.
 */
@Composable
fun DragToQueuePreview(
    payload: QueueDragPayload,
    position: Offset,
    isOverTarget: Boolean
) {
    val accent = LocalWallpaperAccent.current
    val density = LocalDensity.current
    val lift = with(density) { 28.dp.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (position.x - lift).roundToInt(),
                    (position.y - lift * 2f).roundToInt()
                )
            }
            .graphicsLayer {
                // Grows a touch when it is over the slab, so the drop is
                // confirmed before you let go rather than after.
                val s = if (isOverTarget) 1.08f else 1f
                scaleX = s
                scaleY = s
                shadowElevation = 18f
            }
            .agGlassTinted(
                RoundedCornerShape(12.dp),
                tint = accent,
                opacity = if (isOverTarget) 0.75f else 0.45f
            )
            .padding(8.dp)
            .widthIn(max = 220.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (payload.artModel != null) {
                AsyncImage(
                    model = payload.artModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(38.dp).agAlbumTile(RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    text = payload.label,
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        isOverTarget -> "Release to queue"
                        payload is QueueDragPayload.Album ->
                            "${payload.trackCount} ${if (payload.trackCount == 1) "track" else "tracks"}"
                        else -> payload.subtitle ?: "Drag to the cube"
                    },
                    fontFamily = NunitoFamily,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

package com.visibeat.musicui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/**
 * Paints [brush] across the composable's bounds, clipped to [shape].
 *
 * The glass layers used to draw themselves as a rounded rect with a fixed 16.dp
 * radius, which meant every pill and circle in the app (chips, the frosted
 * transport buttons, artist avatars) got a square-cornered highlight sitting
 * inside a round border. Deriving the outline from the shape keeps the light
 * on the same silhouette as the fill.
 */
private fun DrawScope.drawGlassLayer(shape: Shape, brush: Brush) {
    drawOutline(
        outline = shape.createOutline(size, layoutDirection, this),
        brush = brush
    )
}

/**
 * "Glass is a material, not an effect."
 * Multi-layer Aero glass: base fill, specular highlight, gradient border, inner shadow.
 */
fun Modifier.agGlass(
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    opacity: Float = 0.10f
) = this
    // Base translucent fill
    .background(Color.White.copy(alpha = opacity), shape = shape)
    // Inner top highlight (specular edge — the "Aero shine")
    .drawBehind {
        drawGlassLayer(
            shape,
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.25f),
                0.4f to Color.White.copy(alpha = 0.05f),
                1f to Color.Transparent
            )
        )
    }
    // Outer border: bright top fading to subtle bottom
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.35f),
            0.5f to Color.White.copy(alpha = 0.10f),
            1f to Color.White.copy(alpha = 0.03f)
        ),
        shape = shape
    )
    // Subtle inner shadow at bottom for depth
    .drawBehind {
        drawGlassLayer(
            shape,
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.7f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.08f)
            )
        )
    }

/**
 * agGlassTinted: glass that picks up a colour — used for "live" surfaces such as
 * the currently playing row or a selected filter. Same material, warmer light.
 */
fun Modifier.agGlassTinted(
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    tint: Color,
    opacity: Float = 0.16f
) = this
    .background(tint.copy(alpha = opacity), shape = shape)
    .drawBehind {
        drawGlassLayer(
            shape,
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.22f),
                0.5f to Color.White.copy(alpha = 0.04f),
                1f to Color.Transparent
            )
        )
    }
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            0f to tint.copy(alpha = 0.55f),
            1f to tint.copy(alpha = 0.15f)
        ),
        shape = shape
    )

/**
 * agAlbumTile: album artwork, with elevation and a sheen but no outline.
 *
 * There used to be a 1dp [AgPalette.VividBlue] border here, inherited by every
 * album tile in the app. Cover art already has its own edges, and a saturated
 * blue rectangle around all of them competed with the artwork and read as a
 * selection state that nothing ever set — the `isSelected` parameter that chose
 * between blue and white was never passed by a single call site. The drop shadow
 * and the diagonal gloss do the separating work instead.
 */
fun Modifier.agAlbumTile(
    shape: RoundedCornerShape = RoundedCornerShape(4.dp)
) = this
    .drawBehind {
        // Outer drop shadow, offset down-right and following the tile silhouette
        translate(left = 2f, top = 2f) {
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                color = Color.Black.copy(alpha = 0.2f)
            )
        }
    }
    .drawBehind {
        // Inner gloss/sheen (top-left across the diagonal)
        drawGlassLayer(
            shape,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.15f),
                0.5f to Color.Transparent,
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        )
    }
    .clip(shape)

/**
 * agAcrylic: A denser version of glass for overlays.
 *
 * Deep glass, not frosted white. This used to fill with pure white at 70%
 * opacity, which over the app's dark backdrop mixed down to a milky grey around
 * #A8A8A8 — and every label drawn on it is white or white at 62%, so the Now
 * Playing panel was white-on-off-white. [AgAcrylicSurface] then layered 15%
 * black over the top trying to rescue it, which only made the grey dirtier.
 *
 * @param opacity how solid the glass is. Wallpaper still reads through below it,
 *   which is the point of the material — but only just. Overlays have to be
 *   legible over an arbitrary photo, and at 0.80 a bright wallpaper put the whole
 *   Home screen through the Now Playing panel.
 * @param tint the body colour. Dark by default because everything drawn on top
 *   of this surface is light.
 */
fun Modifier.agAcrylic(
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    opacity: Float = 0.92f,
    tint: Color = AgPalette.DeepSpace
) = this
    .background(tint.copy(alpha = opacity), shape = shape)
    // Specular top highlight — the Aero signature, and it finally reads as light
    // on the material rather than disappearing into a white fill.
    .drawBehind {
        drawGlassLayer(
            shape,
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f),
                0.35f to Color.White.copy(alpha = 0.04f),
                1f to Color.Transparent
            )
        )
    }
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.28f),
            0.5f to Color.White.copy(alpha = 0.08f),
            1f to Color.White.copy(alpha = 0.03f)
        ),
        shape = shape
    )

/**
 * AgSurface: The primary depth container.
 *
 * ### Why there is no blur parameter any more
 *
 * This used to put the fill in its own `matchParentSize` child and wrap it in
 * `Modifier.blur(blurRadius)`. That does not blur the backdrop — Compose has no
 * such thing — it blurs *the layer's own content*, and the only content in that
 * layer was a flat colour. Blurring a flat colour changes nothing in the middle
 * and everything at the edges: the default [androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle]
 * samples outside the layer as transparent, so the fill faded out over roughly
 * two to three times the blur radius, inward from all four sides.
 *
 * So the surfaces were see-through exactly where their edges were, and paid a
 * render-effect layer for the privilege. Two call sites had already worked
 * around it by passing `0.dp`.
 *
 * The fill is now drawn straight onto the container. If real frosted glass is
 * ever wanted, it has to come from blurring a *copy of the backdrop* drawn
 * inside the surface — not from blurring the surface itself.
 */
@Composable
fun AgSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.clip(shape).agGlass(shape = shape)) {
        content()
    }
}

/**
 * AgAcrylicSurface: For full-screen or high-contrast overlays — the Now Playing
 * panel and the app's dialogs.
 *
 * A deep pane of glass lit from above by the wallpaper's own accent, so it reads
 * as a lit material rather than as flat black. The accent bloom replaces a flat
 * 15% black wash that was only there to knock back the old white fill.
 */
/**
 * The app's bottom sheet: same material as [AgAcrylicSurface], same density.
 *
 * Every sheet used to configure its own `containerColor` inline, and all three
 * picked something around `Color.Black.copy(alpha = 0.3f)` — which is 70%
 * see-through. Material3 supplies the modality, so touches were never the
 * problem here; the sheets were simply transparent, and a track list or a
 * library screen read straight through the metadata editor sitting on top of it.
 *
 * The default scrim is also lifted. Material3's is 32% black, which is fine over
 * a dark app and does very little over a bright wallpaper.
 *
 * Wrapped rather than fixed three times so the next sheet inherits it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgModalSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    /** Overlays are read against an arbitrary wallpaper; they have to be dense. */
    opacity: Float = 0.95f,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = AgPalette.DeepSpace.copy(alpha = opacity),
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.35f))
        },
        content = content
    )
}

/**
 * @param opacity how solid the pane is. The full-height Now Playing panel wants
 *   more than a small dialog does: it covers a whole screen of live content, and
 *   anything that reads through it lands directly behind track titles.
 *
 * See [AgSurface] for why this no longer takes a blur radius — the short version
 * is that it was blurring its own flat fill, which did nothing except feather the
 * pane's edges to transparent.
 */
@Composable
fun AgAcrylicSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(32.dp),
    opacity: Float = 0.92f,
    content: @Composable () -> Unit
) {
    val accent = LocalWallpaperAccent.current

    Box(
        modifier = modifier
            .clip(shape)
            // Body first: fill, specular highlight and border, all on the
            // container itself rather than in a child layer.
            .agAcrylic(shape = shape, opacity = opacity)
    ) {
        // Accent bloom falling from the top edge.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.22f),
                        0.45f to accent.copy(alpha = 0.06f),
                        1f to Color.Transparent
                    ),
                    shape = shape
                )
        )
        content()
    }
}

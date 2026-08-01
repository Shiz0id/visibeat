package com.visibeat.musicui.playback

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.visibeat.musicui.design.CubePalette
import com.visibeat.musicui.playback.CubeGeometry.CubeFace
import com.visibeat.musicui.playback.CubeGeometry.Projected
import kotlin.math.abs

/**
 * The cube's material, separated from its geometry.
 *
 * Geometry is computed at runtime because the album art changes every track. The
 * material is the part that wants to be prerendered: real glass has refraction and
 * caustics that gradient fills cannot imitate.
 *
 * Every method receives a [CubePose] rather than screen coordinates, so a baked
 * implementation can mesh-map its textures through the identical transform the art is
 * drawn with. Colour always arrives via [CubePalette] and never lives in the asset —
 * that is what keeps a baked material to a handful of files instead of one per album.
 */
interface CubeMaterial {
    fun drawContactShadow(scope: DrawScope, pose: CubePose, palette: CubePalette, alpha: Float)
    fun drawBloom(scope: DrawScope, pose: CubePose, palette: CubePalette, intensity: Float)
    fun drawRim(scope: DrawScope, pose: CubePose, palette: CubePalette)
    fun drawInnerWall(scope: DrawScope, pose: CubePose, palette: CubePalette)
    fun drawGlass(scope: DrawScope, pose: CubePose, palette: CubePalette)
    fun drawEdgeHighlight(scope: DrawScope, pose: CubePose, palette: CubePalette)
}

/**
 * Placeholder material built from gradients.
 *
 * Deliberately structured so each method maps one-to-one onto a baked asset later:
 * [drawSideFace] becomes a tinted luminance strip, [drawGlass] becomes an alpha overlay
 * screened over the art, [drawContactShadow] and [drawBloom] become blurred sprites.
 */
object ProceduralCubeMaterial : CubeMaterial {

    override fun drawContactShadow(
        scope: DrawScope,
        pose: CubePose,
        palette: CubePalette,
        alpha: Float
    ) = with(scope) {
        val left = pose.boundsLeft
        val right = pose.boundsRight
        val width = (right - left) * 1.18f
        val height = width * 0.16f
        val cx = (left + right) / 2f
        val cy = pose.bottom - height * 0.25f
        if (width <= 1f || height <= 1f) return@with

        drawOval(
            brush = Brush.radialGradient(
                0.00f to Color.Black.copy(alpha = alpha),
                0.50f to Color.Black.copy(alpha = alpha * 0.45f),
                1.00f to Color.Transparent,
                center = Offset(cx, cy),
                radius = width / 2f
            ),
            topLeft = Offset(cx - width / 2f, cy - height / 2f),
            size = Size(width, height)
        )
    }

    override fun drawBloom(
        scope: DrawScope,
        pose: CubePose,
        palette: CubePalette,
        intensity: Float
    ) = with(scope) {
        if (intensity <= 0.01f) return@with
        val cx = (pose.boundsLeft + pose.boundsRight) / 2f
        val cy = (pose.boundsTop + pose.bottom) / 2f
        val radius = (pose.boundsRight - pose.boundsLeft) * 0.85f
        if (radius <= 1f) return@with

        drawCircle(
            brush = Brush.radialGradient(
                0.00f to palette.specularHighlight.copy(alpha = 0.22f * intensity),
                0.45f to palette.sideFacePrimary.copy(alpha = 0.14f * intensity),
                1.00f to Color.Transparent,
                center = Offset(cx, cy),
                radius = radius
            ),
            radius = radius,
            center = Offset(cx, cy)
        )
    }

    override fun drawRim(scope: DrawScope, pose: CubePose, palette: CubePalette) = with(scope) {
        val quads = pose.visibleRim
        if (quads.isEmpty()) return@with

        // One ribbon, one gradient. The extrusion offset is constant in model space, so
        // a single front-to-back direction serves every segment and leaves no seams.
        val (sweepX, sweepY) = pose.rimSweep
        if (sweepX * sweepX + sweepY * sweepY < 0.25f) return@with

        val ribbon = Path().apply {
            for (q in quads) {
                moveTo(q[0].x, q[0].y)
                lineTo(q[1].x, q[1].y)
                lineTo(q[2].x, q[2].y)
                lineTo(q[3].x, q[3].y)
                close()
            }
        }
        val anchor = quads.first()[0]
        drawPath(
            ribbon,
            Brush.linearGradient(
                0.00f to palette.specularHighlight,
                0.14f to palette.sideFacePrimary,
                0.62f to palette.sideFaceShadow,
                1.00f to palette.sideFaceShadow,
                start = Offset(anchor.x, anchor.y),
                end = Offset(anchor.x + sweepX, anchor.y + sweepY)
            )
        )
    }

    override fun drawEdgeHighlight(
        scope: DrawScope,
        pose: CubePose,
        palette: CubePalette
    ) = with(scope) {
        // Bright lip along the front edge of the visible rim.
        for (q in pose.visibleRim) {
            drawLine(
                palette.specularHighlight.copy(alpha = 0.55f),
                Offset(q[0].x, q[0].y),
                Offset(q[1].x, q[1].y),
                strokeWidth = 1.2f
            )
        }
        drawPath(pose.outline.toPath(), Color.White.copy(alpha = 0.30f), style = OUTLINE_STROKE)
    }

    /**
     * The inside of the side wall, seen through the front face.
     *
     * Fills the gap between the art plane and the face outline on the far edges. Without
     * it the art reads as printed on the front surface rather than sunk into the solid.
     */
    override fun drawInnerWall(
        scope: DrawScope,
        pose: CubePose,
        palette: CubePalette
    ) = with(scope) {
        val quads = pose.visibleInnerWall
        if (quads.isEmpty()) return@with

        val (sweepX, sweepY) = pose.artSweep
        val wall = Path().apply {
            for (q in quads) {
                moveTo(q[0].x, q[0].y)
                lineTo(q[1].x, q[1].y)
                lineTo(q[2].x, q[2].y)
                lineTo(q[3].x, q[3].y)
                close()
            }
        }
        val anchor = quads.first()[0]
        if (sweepX * sweepX + sweepY * sweepY < 0.25f) {
            drawPath(wall, palette.sideFaceShadow)
            return@with
        }
        // Runs from the front surface inward: bright where it catches the face light,
        // dropping into shadow where it meets the art.
        drawPath(
            wall,
            Brush.linearGradient(
                0.00f to palette.specularHighlight.copy(alpha = 0.85f),
                0.30f to palette.sideFacePrimary,
                1.00f to palette.sideFaceShadow,
                start = Offset(anchor.x, anchor.y),
                end = Offset(anchor.x + sweepX, anchor.y + sweepY)
            )
        )
        // Contact line where the art meets the wall.
        for (q in quads) {
            drawLine(
                Color.Black.copy(alpha = 0.35f),
                Offset(q[3].x, q[3].y),
                Offset(q[2].x, q[2].y),
                strokeWidth = 1.2f
            )
        }
    }

    override fun drawGlass(scope: DrawScope, pose: CubePose, palette: CubePalette) = with(scope) {
        val quad = pose.corners.frontQuad
        val xs = quad.map { it.x }
        val ys = quad.map { it.y }
        val bounds = Rect(xs.min(), ys.min(), xs.max(), ys.max())
        if (bounds.width < 1f || bounds.height < 1f) return@with

        // Specular band across the top — the Longhorn shine.
        drawRect(
            brush = Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.28f),
                0.10f to Color.White.copy(alpha = 0.20f),
                0.30f to Color.White.copy(alpha = 0.09f),
                0.44f to Color.Transparent,
                1.00f to Color.Transparent,
                startY = bounds.top,
                endY = bounds.bottom
            ),
            topLeft = bounds.topLeft,
            size = bounds.size
        )
        // Diagonal shimmer.
        drawRect(
            brush = Brush.linearGradient(
                0.00f to Color.Transparent,
                0.38f to Color.Transparent,
                0.48f to Color.White.copy(alpha = 0.10f),
                0.56f to Color.White.copy(alpha = 0.10f),
                0.66f to Color.Transparent,
                1.00f to Color.Transparent,
                start = Offset(bounds.left, bounds.top + bounds.height * 0.1f),
                end = Offset(bounds.right, bounds.bottom)
            ),
            topLeft = bounds.topLeft,
            size = bounds.size
        )
        // Weight at the bottom of the glass.
        drawRect(
            brush = Brush.verticalGradient(
                0.80f to Color.Transparent,
                1.00f to Color.Black.copy(alpha = 0.16f),
                startY = bounds.top,
                endY = bounds.bottom
            ),
            topLeft = bounds.topLeft,
            size = bounds.size
        )
    }

    private val OUTLINE_STROKE =
        androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
}

internal fun List<Projected>.toPath(): Path = Path().apply {
    forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}

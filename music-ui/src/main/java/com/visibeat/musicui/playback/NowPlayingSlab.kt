package com.visibeat.musicui.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visibeat.musicui.design.AgPalette
import com.visibeat.musicui.design.CubePalette
import com.visibeat.musicui.design.PlaybackColors
import com.visibeat.musicui.playback.CubeGeometry.CubeCorners
import com.visibeat.musicui.playback.CubeGeometry.Projected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Layout metrics for the slab.
 *
 * Exported so the host can size its drag bounds from the same numbers the slab draws
 * with. The previous version hardcoded 100x140dp in MainActivity while the composable
 * actually measured 130x232dp, so the tile could be dragged most of the way off screen.
 */
object NowPlayingSlabMetrics {
    val FaceSize = 100.dp

    /** Depth of the solid — the "chunky physical object" proportion. */
    val Thickness = 14.dp

    val CornerRadius = 10.dp

    /** Room for perspective overflow and the extruded side faces. */
    val Margin = 18.dp

    val ReflectionGap = 3.dp
    const val REFLECTION_COMPRESS = 0.42f

    val Width: Dp = FaceSize + Margin * 2
    val CubeHeight: Dp = FaceSize + Margin * 2

    /**
     * How much larger the cube is drawn in the expanded player.
     *
     * Every dimension here is scaled by one factor, and the camera distance is
     * derived from the face size (`cameraDistanceFor`), so the projection —
     * and therefore the look — is identical at any scale. Only bigger.
     */
    const val ExpandedScale = 1.5f

    fun width(sizeScale: Float = 1f): Dp = Width * sizeScale

    fun height(showReflection: Boolean, sizeScale: Float = 1f): Dp {
        val base =
            if (showReflection) CubeHeight + ReflectionGap + FaceSize * REFLECTION_COMPRESS + Margin
            else CubeHeight
        return base * sizeScale
    }
}

/**
 * A chunky 3D album cube with bass-reactive motion.
 *
 * The whole solid — side faces, album art, gloss and visualiser — is projected through
 * one shared transform in a single Canvas. The previous implementation ran two
 * independent pipelines (graphicsLayer for the front face, a hand-rolled projection for
 * the sides) which could not stay aligned; see CubeGeometryTest for the specifics.
 */
@Composable
fun NowPlayingSlab(
    state: PlaybackState,
    fftData: ByteArray,
    visualizerColors: List<Color>,
    albumGlowColor: Color = AgPalette.VividBlue,
    showReflection: Boolean = true,
    material: CubeMaterial = ProceduralCubeMaterial,
    /**
     * Lets the cube be turned by dragging it, springing back when released.
     *
     * Off by default, and only switched on by the expanded player. The collapsed
     * tile is wrapped in its own drag gesture for repositioning, and the two
     * would fight over the same finger.
     */
    interactive: Boolean = false,
    /**
     * Uniform size multiplier. Every dimension and the camera distance scale
     * together, so the cube looks the same at any value — just larger.
     * See [NowPlayingSlabMetrics.ExpandedScale].
     */
    sizeScale: Float = 1f,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val track = state.currentTrack
    val artModel = track?.artModel
    val isPlaying = state.isPlaying

    // ── Album art: decoded once per track, off the main thread, downsampled ──
    var art by remember { mutableStateOf<Bitmap?>(null) }
    var palette by remember { mutableStateOf(PlaybackColors.defaultCubePalette) }
    val targetArtPx = with(density) {
        (NowPlayingSlabMetrics.FaceSize * sizeScale).roundToPx()
    } * 2

    LaunchedEffect(artModel, targetArtPx) {
        val bitmap = loadArtBitmap(context, artModel, targetArtPx)
        val extracted = withContext(Dispatchers.Default) {
            PlaybackColors.generateCubePalette(bitmap)
        }
        art = bitmap
        palette = extracted
    }

    val effectiveVizColors = remember(palette, visualizerColors) {
        if (palette != PlaybackColors.defaultCubePalette) palette.visualizerColors
        else visualizerColors.ifEmpty { palette.visualizerColors }
    }

    // ── Bass energy ──
    // PeakTracker is a plain object, not snapshot state. The old version wrote Compose
    // state from inside the draw lambda, which invalidated the draw scope every frame.
    val bassTracker = remember { CubeGeometry.PeakTracker() }
    val bassEnergy = remember(fftData, isPlaying) {
        if (!isPlaying) 0f else bassTracker.normalize(CubeGeometry.bassMagnitude(fftData))
    }

    // ── Visualiser levels, log-spaced across the whole spectrum ──
    val barTrackers = remember { List(VISUALIZER_BARS) { CubeGeometry.PeakTracker() } }
    val barLevels = remember(fftData, isPlaying) {
        if (!isPlaying || fftData.size < 8) {
            FloatArray(VISUALIZER_BARS)
        } else {
            val binCount = fftData.size / 2
            val edges = CubeGeometry.logBinEdges(binCount, VISUALIZER_BARS)
            FloatArray(VISUALIZER_BARS) { i ->
                barTrackers[i].normalize(
                    CubeGeometry.bandMagnitude(fftData, edges[i], edges[i + 1])
                )
            }
        }
    }

    // ── Motion ──
    val idle = rememberInfiniteTransition(label = "idleDrift")
    val idleTiltY by idle.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleTiltY"
    )

    val animatedScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f + bassEnergy * 0.08f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 400f),
        label = "cubeScale"
    )
    val animatedTiltX by animateFloatAsState(
        targetValue = CubeGeometry.BASE_TILT_X + if (isPlaying) bassEnergy * 5f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 250f),
        label = "cubeTiltX"
    )
    // The idle drift used to switch off the instant playback started, which left
    // the cube perfectly dead whenever the visualiser had nothing to give it. It
    // now always runs, just quieter while playing — where the music sway below is
    // doing the interesting work.
    val driftScale = if (isPlaying) 0.45f else 1f

    val animatedTiltY by animateFloatAsState(
        targetValue = CubeGeometry.BASE_TILT_Y + idleTiltY * driftScale -
            if (isPlaying) bassEnergy * 4f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 200f),
        label = "cubeTiltY"
    )

    // ── Music sway ──
    // Two axes from two different spectral measurements, so the cube wanders
    // rather than pumping along one diagonal. Sprung slowly and loosely on
    // purpose: this should read as the cube being carried by the music, not as a
    // meter tracking it.
    val musicYaw by animateFloatAsState(
        targetValue = if (isPlaying) SlabMusicSway.yawDegrees(barLevels) else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 110f),
        label = "cubeMusicYaw"
    )
    val musicPitch by animateFloatAsState(
        targetValue = if (isPlaying) SlabMusicSway.pitchDegrees(barLevels) else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 90f),
        label = "cubeMusicPitch"
    )
    val reflectionAlpha = if (isPlaying) 0.18f + bassEnergy * 0.07f else 0.18f

    // ── Touch rotation ──
    // Held as plain state and faded out by [dragSettle] rather than animated
    // directly, so the cube tracks the finger 1:1 while held (snap) and springs
    // home when let go — with a little overshoot, which is what makes it read as
    // a toy rather than a slider.
    var dragYawDeg by remember { mutableFloatStateOf(0f) }
    var dragPitchDeg by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val dragSettle by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = if (isDragging) snap() else spring(dampingRatio = 0.45f, stiffness = 220f),
        label = "cubeDragSettle"
    )

    val interaction = remember { MutableInteractionSource() }

    Canvas(
        modifier = Modifier
            .size(
                width = NowPlayingSlabMetrics.width(sizeScale),
                height = NowPlayingSlabMetrics.height(showReflection, sizeScale)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .then(
                if (interactive) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                // Start each grab from the resting pose so the
                                // cube follows the finger predictably even if the
                                // previous spring-back is still running.
                                dragYawDeg = 0f
                                dragPitchDeg = 0f
                                isDragging = true
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                dragYawDeg = SlabDragRotation.accumulate(dragYawDeg, delta.x)
                                // Screen-down should tip the top of the cube
                                // toward the viewer, hence the negated Y.
                                dragPitchDeg = SlabDragRotation.accumulate(dragPitchDeg, -delta.y)
                            },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false }
                        )
                    }
                } else Modifier
            )
    ) {
        val facePx = NowPlayingSlabMetrics.FaceSize.toPx() * sizeScale
        val marginPx = NowPlayingSlabMetrics.Margin.toPx() * sizeScale
        val pose = CubePose(
            halfWidth = facePx / 2f,
            halfHeight = facePx / 2f,
            halfDepth = NowPlayingSlabMetrics.Thickness.toPx() * sizeScale / 2f,
            cornerRadius = NowPlayingSlabMetrics.CornerRadius.toPx() * sizeScale,
            // Drag and music sway are both offsets on top of the animated pose
            // rather than replacements, so they simply sum: the music keeps
            // moving the cube while you hold it turned.
            rotXDeg = animatedTiltX + dragPitchDeg * dragSettle + musicPitch,
            rotYDeg = animatedTiltY + dragYawDeg * dragSettle + musicYaw,
            rollDeg = CubeGeometry.BASE_ROLL,
            cameraZ = CubeGeometry.cameraDistanceFor(facePx),
            centerX = size.width / 2f,
            centerY = marginPx + facePx / 2f,
            scale = animatedScale
        )

        drawCubeBody(
            pose = pose,
            material = material,
            palette = palette,
            art = art,
            fallbackTint = albumGlowColor,
            barLevels = barLevels,
            barColors = effectiveVizColors,
            ambient = true
        )

        if (showReflection) {
            // Anchor the mirror line to the cube's actual lowest projected point, so the
            // reflection stays welded to the object at every tilt and scale.
            val mirrorLine = pose.bottom + NowPlayingSlabMetrics.ReflectionGap.toPx() * sizeScale
            val compress = NowPlayingSlabMetrics.REFLECTION_COMPRESS
            val bounds = Rect(0f, mirrorLine, size.width, size.height)

            if (bounds.height > 1f) {
                drawIntoCanvas { it.saveLayer(bounds, Paint()) }

                drawCubeBody(
                    pose = pose.mirroredBelow(mirrorLine, compress),
                    material = material,
                    palette = palette,
                    art = art,
                    fallbackTint = albumGlowColor,
                    barLevels = null,
                    barColors = effectiveVizColors,
                    ambient = false
                )

                // Fade away from the cube. The mask is applied in screen space after the
                // mirror, so it darkens with distance instead of the old inverted fade.
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = reflectionAlpha),
                        0.55f to Color.Black.copy(alpha = reflectionAlpha * 0.35f),
                        1f to Color.Transparent,
                        startY = bounds.top,
                        endY = bounds.bottom
                    ),
                    topLeft = bounds.topLeft,
                    size = bounds.size,
                    blendMode = BlendMode.DstIn
                )

                drawIntoCanvas { it.restore() }
            }
        }
    }
}


// ── Compositing ───────────────────────────────────────────────────────────────

/**
 * The layer stack, back to front.
 *
 * Order is the whole point: the visualiser goes on before the glass, so it reads as
 * etched into the material rather than painted on top of it. Ambient layers (contact
 * shadow, bloom) are suppressed for the reflection, which inherits them from the
 * original.
 */
private fun DrawScope.drawCubeBody(
    pose: CubePose,
    material: CubeMaterial,
    palette: CubePalette,
    art: Bitmap?,
    fallbackTint: Color,
    barLevels: FloatArray?,
    barColors: List<Color>,
    ambient: Boolean
) {
    if (ambient) {
        material.drawContactShadow(this, pose, palette, alpha = 0.30f)
        material.drawBloom(this, pose, palette, intensity = 1f)
    }

    material.drawRim(this, pose, palette)

    val facePath = pose.outline.toPath()
    clipPath(facePath) {
        // Interior body first, so the gap between the art plane and the face outline is
        // never a hole.
        drawPath(facePath, palette.sideFaceShadow)

        if (art != null) {
            drawArtMesh(art, pose)
        } else {
            drawPath(facePath, fallbackTint.copy(alpha = 0.35f))
            drawPath(facePath, palette.sideFacePrimary.copy(alpha = 0.30f))
        }

        material.drawInnerWall(this, pose, palette)

        if (barLevels != null && barLevels.any { it > 0.01f }) {
            drawEtchedVisualizer(pose, barLevels, barColors)
        }

        material.drawGlass(this, pose, palette)
    }

    if (ambient) material.drawEdgeHighlight(this, pose, palette)
}

/**
 * Maps the album art onto the projected front face as a subdivided mesh.
 *
 * Every vertex goes through the pose's projection, so the art carries true perspective
 * without ever building a non-affine matrix. setPolyToPoly would give the same result
 * in one call, but a perspective matrix passed to drawBitmap is not renderable in
 * software and crashed SwiftShader outright.
 *
 * [art] must already be square — loadArtBitmap centre-crops it.
 */
private fun DrawScope.drawArtMesh(art: Bitmap, pose: CubePose) {
    if (art.width <= 0 || art.height <= 0) return

    val steps = ART_MESH_STEPS
    val verts = FloatArray((steps + 1) * (steps + 1) * 2)
    var i = 0
    for (row in 0..steps) {
        val v = row / steps.toFloat()
        for (col in 0..steps) {
            val p = pose.projectArtUV(col / steps.toFloat(), v)
            verts[i++] = p.x
            verts[i++] = p.y
        }
    }

    val paint = android.graphics.Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
    }
    drawIntoCanvas {
        it.nativeCanvas.drawBitmapMesh(art, steps, steps, verts, 0, null, 0, paint)
    }
}

/**
 * Bars live in the front face's own plane and are projected through the pose, so they
 * sit in the material rather than floating in screen space.
 */
private fun DrawScope.drawEtchedVisualizer(
    pose: CubePose,
    levels: FloatArray,
    colors: List<Color>
) {
    if (colors.isEmpty()) return
    val pad = 0.08f
    val usable = 1f - pad * 2f
    val slot = usable / levels.size
    val gap = slot * 0.22f
    val baseline = 1f - pad

    for (i in levels.indices) {
        val level = levels[i]
        if (level <= 0.01f) continue
        val height = (level * 0.62f).coerceAtLeast(0.006f)
        val u0 = pad + i * slot
        val u1 = u0 + slot - gap
        val top = baseline - height

        val path = Path().apply {
            val a = pose.projectArtUV(u0, top)
            val b = pose.projectArtUV(u1, top)
            val c = pose.projectArtUV(u1, baseline)
            val d = pose.projectArtUV(u0, baseline)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        drawPath(path, colors[i % colors.size].copy(alpha = 0.48f))
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private suspend fun loadArtBitmap(context: Context, model: Any?, targetPx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        if (model == null) return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openArtStream(context, model)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = openArtStream(context, model)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            decoded?.let(::centreCropSquare)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

/** drawBitmapMesh stretches the whole bitmap across the face, so square it up first. */
private fun centreCropSquare(source: Bitmap): Bitmap {
    val edge = min(source.width, source.height)
    if (source.width == edge && source.height == edge) return source
    val left = (source.width - edge) / 2
    val top = (source.height - edge) / 2
    return Bitmap.createBitmap(source, left, top, edge, edge).also {
        if (it !== source) source.recycle()
    }
}

private fun openArtStream(context: Context, model: Any) = when (model) {
    is File -> if (model.exists()) model.inputStream() else null
    is String -> context.contentResolver.openInputStream(Uri.parse(model))
    is Uri -> context.contentResolver.openInputStream(model)
    else -> null
}

private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= targetPx && h / 2 >= targetPx) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}

private const val VISUALIZER_BARS = 16

/** Mesh subdivision for the album art. 8x8 is visually exact at these tilt angles. */
private const val ART_MESH_STEPS = 8

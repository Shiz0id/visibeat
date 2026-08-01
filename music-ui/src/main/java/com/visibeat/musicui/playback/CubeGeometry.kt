package com.visibeat.musicui.playback

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry and signal math for the album cube.
 *
 * Coordinate convention matches Compose's `graphicsLayer` so the angles stay intuitive:
 * +X right, +Y down, +Z toward the viewer. rotationX is applied before rotationY.
 * The camera sits on the +Z axis at `cameraZ` looking toward -Z.
 *
 * Consequences of that convention, which the face-visibility tests pin down:
 *  - positive rotationY swings the right edge away, exposing the LEFT side face
 *  - positive rotationX swings the bottom edge forward, exposing the BOTTOM face
 * The reference art shows the thick edge on the left, so the default tilt uses a
 * positive rotationY.
 *
 * Roll is a rotation in the picture plane. It is applied after the depth rotations,
 * where it cannot affect z — so it reduces exactly to rotating the projected offsets
 * about the centre, and leaves face visibility untouched.
 *
 * Nothing in this file touches Android or the Compose runtime, so it runs under plain
 * JVM unit tests. The drawing code in NowPlayingSlab is the only consumer.
 */
object CubeGeometry {

    /**
     * Camera distance as a multiple of the cube's width.
     *
     * This is the constant that matters. A camera closer than the object puts corners
     * behind the view plane, where the perspective divide explodes. At 5x the cube
     * width the worst-case divisor across the full animated tilt range stays around
     * 1200px, so the projection can never blow up.
     */
    const val CAMERA_DISTANCE_FACTOR = 5f

    /** Base tilt that exposes the left and bottom side faces, as the reference art does. */
    const val BASE_TILT_X = 18f
    const val BASE_TILT_Y = 22f

    /**
     * Base roll, negative meaning the top tips toward the left. Purely a taste knob —
     * tune it on device, and re-bake the glass assets at whatever value you settle on.
     */
    const val BASE_ROLL = -4f

    fun cameraDistanceFor(cubeWidthPx: Float): Float = cubeWidthPx * CAMERA_DISTANCE_FACTOR

    /** A model-space point after projection to screen space. */
    data class Projected(
        val x: Float,
        val y: Float,
        /** Depth after rotation, in model units. Larger is nearer the camera. */
        val depth: Float,
        /** The perspective divisor. Must stay well above zero or the projection blows up. */
        val denominator: Float
    )

    enum class CubeFace { FRONT, RIGHT, LEFT, TOP, BOTTOM }

    data class CubeCorners(
        val frontTL: Projected, val frontTR: Projected,
        val frontBR: Projected, val frontBL: Projected,
        val backTL: Projected, val backTR: Projected,
        val backBR: Projected, val backBL: Projected
    ) {
        val frontQuad: List<Projected> get() = listOf(frontTL, frontTR, frontBR, frontBL)
        val all: List<Projected>
            get() = listOf(frontTL, frontTR, frontBR, frontBL, backTL, backTR, backBR, backBL)
    }

    /**
     * Project a single model-space point.
     *
     * @param cameraZ distance from the camera to the z=0 plane, in the same units as x/y/z
     */
    fun project(
        x: Float, y: Float, z: Float,
        rotXDeg: Float, rotYDeg: Float,
        cameraZ: Float,
        centerX: Float, centerY: Float,
        rollDeg: Float = 0f
    ): Projected {
        val rx = rotXDeg * DEG_TO_RAD
        val ry = rotYDeg * DEG_TO_RAD
        val cosX = cos(rx); val sinX = sin(rx)
        val cosY = cos(ry); val sinY = sin(ry)

        // Rotate about X: bottom (+Y) swings toward the viewer for positive angles.
        val y1 = y * cosX - z * sinX
        val z1 = y * sinX + z * cosX
        // Rotate about Y: right (+X) swings away from the viewer for positive angles.
        val x2 = x * cosY + z1 * sinY
        val z2 = -x * sinY + z1 * cosY

        val denominator = cameraZ - z2
        val scale = cameraZ / denominator
        val offsetX = x2 * scale
        val offsetY = y1 * scale

        // Roll leaves z alone, so it commutes with the perspective divide and is exactly
        // a rotation of the projected offset about the centre.
        val roll = rollDeg * DEG_TO_RAD
        val cosR = cos(roll); val sinR = sin(roll)
        return Projected(
            x = centerX + offsetX * cosR - offsetY * sinR,
            y = centerY + offsetX * sinR + offsetY * cosR,
            depth = z2,
            denominator = denominator
        )
    }

    /**
     * Project all eight cube corners.
     *
     * [scale] multiplies the whole solid, so the front face and the side faces grow
     * together — the bass bounce cannot pull the art off its own extrusion.
     */
    fun projectCube(
        halfWidth: Float, halfHeight: Float, halfDepth: Float,
        rotXDeg: Float, rotYDeg: Float,
        cameraZ: Float,
        centerX: Float, centerY: Float,
        scale: Float = 1f,
        rollDeg: Float = 0f
    ): CubeCorners {
        val hw = halfWidth * scale
        val hh = halfHeight * scale
        val hd = halfDepth * scale
        fun p(x: Float, y: Float, z: Float) =
            project(x, y, z, rotXDeg, rotYDeg, cameraZ, centerX, centerY, rollDeg)
        return CubeCorners(
            frontTL = p(-hw, -hh, hd), frontTR = p(hw, -hh, hd),
            frontBR = p(hw, hh, hd), frontBL = p(-hw, hh, hd),
            backTL = p(-hw, -hh, -hd), backTR = p(hw, -hh, -hd),
            backBR = p(hw, hh, -hd), backBL = p(-hw, hh, -hd)
        )
    }

    /**
     * The four corners of a side face, wound counter-clockwise as seen from outside
     * the cube. With screen-space Y pointing down, a face that is turned toward the
     * viewer projects to a negative signed area.
     */
    fun faceQuad(c: CubeCorners, face: CubeFace): List<Projected> = when (face) {
        CubeFace.FRONT -> c.frontQuad
        CubeFace.RIGHT -> listOf(c.frontTR, c.frontBR, c.backBR, c.backTR)
        CubeFace.LEFT -> listOf(c.frontBL, c.frontTL, c.backTL, c.backBL)
        CubeFace.TOP -> listOf(c.frontTL, c.frontTR, c.backTR, c.backTL)
        CubeFace.BOTTOM -> listOf(c.frontBR, c.frontBL, c.backBL, c.backBR)
    }

    /** Shoelace area in screen space (Y down). Positive is clockwise on screen. */
    fun signedArea(quad: List<Projected>): Float {
        var sum = 0f
        for (i in quad.indices) {
            val a = quad[i]
            val b = quad[(i + 1) % quad.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }

    fun isFacingViewer(c: CubeCorners, face: CubeFace): Boolean =
        signedArea(faceQuad(c, face)) < 0f

    /**
     * Side faces currently turned toward the viewer, farthest first so a painter's
     * algorithm draws them in the right order before the front face goes on top.
     */
    fun visibleSideFaces(c: CubeCorners): List<CubeFace> =
        SIDE_FACES.filter { isFacingViewer(c, it) }
            .sortedBy { face -> faceQuad(c, face).map { it.depth }.average().toFloat() }

    /**
     * The front face outline as a rounded rectangle, projected. Projecting the outline
     * rather than clipping to a screen-space rounded rect keeps the corners consistent
     * with the perspective the side faces were drawn in.
     */
    fun projectRoundedFrontOutline(
        halfWidth: Float, halfHeight: Float, halfDepth: Float,
        cornerRadius: Float,
        rotXDeg: Float, rotYDeg: Float,
        cameraZ: Float,
        centerX: Float, centerY: Float,
        scale: Float = 1f,
        segmentsPerCorner: Int = 6,
        rollDeg: Float = 0f
    ): List<Projected> {
        val hw = halfWidth * scale
        val hh = halfHeight * scale
        val hd = halfDepth * scale
        val r = cornerRadius.coerceIn(0f, minOf(hw, hh)) * scale
        val points = ArrayList<Projected>(segmentsPerCorner * 4 + 4)

        // Corner centers, clockwise from top-left.
        val corners = listOf(
            Triple(-hw + r, -hh + r, 180f), // top-left arc starts pointing left
            Triple(hw - r, -hh + r, 270f),
            Triple(hw - r, hh - r, 0f),
            Triple(-hw + r, hh - r, 90f)
        )
        for ((ccx, ccy, startDeg) in corners) {
            for (s in 0..segmentsPerCorner) {
                val a = (startDeg + 90f * s / segmentsPerCorner) * DEG_TO_RAD
                val px = ccx + r * cos(a)
                val py = ccy + r * sin(a)
                points += project(
                    px, py, hd, rotXDeg, rotYDeg, cameraZ, centerX, centerY, rollDeg
                )
            }
        }
        return points
    }

    // ── FFT helpers ────────────────────────────────────────────────────────────

    /**
     * Bin edges spaced logarithmically across the usable spectrum.
     *
     * Returns `bars + 1` strictly increasing edges from bin 1 (bin 0 is DC) through
     * [binCount]. Linear bins put every bar in the bass, which is why the old
     * visualizer moved as one lump.
     */
    fun logBinEdges(binCount: Int, barCount: Int): IntArray {
        require(binCount >= 2) { "need at least 2 bins, got $binCount" }
        val bars = barCount.coerceIn(1, binCount - 1)
        val edges = IntArray(bars + 1)
        edges[0] = 1
        val ratio = binCount.toDouble() / 1.0
        for (i in 1..bars) {
            val t = i.toDouble() / bars
            val v = ratio.pow(t).roundToInt()
            edges[i] = maxOf(v, edges[i - 1] + 1)
        }
        // Geometric spacing plus the monotonic bump can overshoot; pull the tail back in.
        for (i in bars downTo 1) {
            val ceiling = binCount - (bars - i)
            if (edges[i] > ceiling) edges[i] = ceiling
        }
        return edges
    }

    /** Magnitude of a single FFT bin. Bin 0 is DC, bin i occupies bytes 2i and 2i+1. */
    fun magnitudeAt(fft: ByteArray, bin: Int): Float {
        val re = bin * 2
        val im = bin * 2 + 1
        if (bin < 0 || im >= fft.size) return 0f
        val r = fft[re].toFloat()
        val i = fft[im].toFloat()
        return sqrt(r * r + i * i)
    }

    /** Mean magnitude over the half-open bin range [fromBin, toBin). */
    fun bandMagnitude(fft: ByteArray, fromBin: Int, toBin: Int): Float {
        if (toBin <= fromBin) return 0f
        var sum = 0f
        var counted = 0
        for (bin in fromBin until toBin) {
            if (bin * 2 + 1 >= fft.size) break
            sum += magnitudeAt(fft, bin)
            counted++
        }
        return if (counted == 0) 0f else sum / counted
    }

    /** Raw bass magnitude, roughly 40-200Hz at a 1024-byte capture and 44.1kHz. */
    fun bassMagnitude(fft: ByteArray, fromBin: Int = 1, toBin: Int = 5): Float =
        if (fft.size < 10) 0f else bandMagnitude(fft, fromBin, toBin)

    /**
     * Adaptive normaliser. Held in a plain object rather than snapshot state — the old
     * version wrote Compose state from inside the draw lambda, which invalidated the
     * draw scope and span a redraw every frame.
     */
    class PeakTracker(
        private val decay: Float = 0.998f,
        private val floor: Float = 1f
    ) {
        var peak: Float = floor
            private set

        fun normalize(raw: Float): Float {
            if (raw > peak) {
                peak = raw
            } else {
                peak = (peak * decay).coerceAtLeast(floor)
            }
            return if (peak <= 0f) 0f else (raw / peak).coerceIn(0f, 1f)
        }

        fun reset() {
            peak = floor
        }
    }

    private val SIDE_FACES =
        listOf(CubeFace.RIGHT, CubeFace.LEFT, CubeFace.TOP, CubeFace.BOTTOM)

    private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

    internal fun maxAbsCoordinate(c: CubeCorners, centerX: Float, centerY: Float): Float =
        c.all.maxOf { maxOf(abs(it.x - centerX), abs(it.y - centerY)) }
}

/**
 * A fully resolved cube pose.
 *
 * Bundles the projection parameters so the material layer can map textures through the
 * exact transform the geometry was drawn with. Baked assets only line up if the art,
 * the glass overlay and the side strips all go through this one object.
 *
 * A mirrored pose reports the same visible faces as its source — a reflection shows the
 * same sides of the solid, even though mirroring reverses the projected winding.
 */
/**
 * A fully resolved cube pose.
 *
 * Bundles the projection parameters so the material layer can map textures through the
 * exact transform the geometry was drawn with. Baked assets only line up if the art,
 * the glass overlay and the side strips all go through this one object.
 *
 * A mirrored pose reports the same visible faces as its source - a reflection shows the
 * same sides of the solid, even though mirroring reverses the projected winding.
 */
class CubePose(
    val halfWidth: Float,
    val halfHeight: Float,
    val halfDepth: Float,
    val cornerRadius: Float,
    val rotXDeg: Float,
    val rotYDeg: Float,
    val rollDeg: Float,
    val cameraZ: Float,
    val centerX: Float,
    val centerY: Float,
    val scale: Float = 1f,
    /**
     * Where the album art sits inside the solid: 0 is flush with the front surface,
     * 1 is against the back face. Anything above 0 is what makes the art read as
     * embedded under glass rather than printed on it.
     */
    val artDepthFraction: Float = 0.85f,
    private val mirrorLine: Float? = null,
    private val mirrorCompress: Float = 1f
) {
    private val hw get() = halfWidth * scale
    private val hh get() = halfHeight * scale
    private val hd get() = halfDepth * scale

    val isMirrored: Boolean get() = mirrorLine != null

    fun project(x: Float, y: Float, z: Float): CubeGeometry.Projected {
        val p = projectRaw(x, y, z)
        val line = mirrorLine ?: return p
        return p.copy(y = line + (line - p.y) * mirrorCompress)
    }

    private fun projectRaw(x: Float, y: Float, z: Float): CubeGeometry.Projected =
        CubeGeometry.project(x, y, z, rotXDeg, rotYDeg, cameraZ, centerX, centerY, rollDeg)

    /** Depth of the art plane. */
    private val artZ get() = hd - artDepthFraction * 2f * hd

    /** Front *surface* texture coordinates, (0,0) top-left to (1,1) bottom-right. */
    fun projectFrontUV(u: Float, v: Float): CubeGeometry.Projected =
        project(-hw + u * 2f * hw, -hh + v * 2f * hh, hd)

    /** Art-plane texture coordinates. Sits behind the front surface by [artDepthFraction]. */
    fun projectArtUV(u: Float, v: Float): CubeGeometry.Projected =
        project(-hw + u * 2f * hw, -hh + v * 2f * hh, artZ)

    /**
     * Side-face texture coordinates. [along] runs the length of the face, [depth] runs
     * from the front edge (0) to the back edge (1).
     */
    fun projectSideUV(
        face: CubeGeometry.CubeFace,
        along: Float,
        depth: Float
    ): CubeGeometry.Projected {
        val z = hd - depth * 2f * hd
        return when (face) {
            CubeGeometry.CubeFace.LEFT -> project(-hw, -hh + along * 2f * hh, z)
            CubeGeometry.CubeFace.RIGHT -> project(hw, -hh + along * 2f * hh, z)
            CubeGeometry.CubeFace.TOP -> project(-hw + along * 2f * hw, -hh, z)
            CubeGeometry.CubeFace.BOTTOM -> project(-hw + along * 2f * hw, hh, z)
            CubeGeometry.CubeFace.FRONT -> projectFrontUV(along, depth)
        }
    }

    private fun cornersVia(p: (Float, Float, Float) -> CubeGeometry.Projected) =
        CubeGeometry.CubeCorners(
            frontTL = p(-hw, -hh, hd), frontTR = p(hw, -hh, hd),
            frontBR = p(hw, hh, hd), frontBL = p(-hw, hh, hd),
            backTL = p(-hw, -hh, -hd), backTR = p(hw, -hh, -hd),
            backBR = p(hw, hh, -hd), backBL = p(-hw, hh, -hd)
        )

    val corners: CubeGeometry.CubeCorners by lazy { cornersVia(::project) }

    /** Rounded-rect outline in model space, shared by the front and back rims. */
    private val outlineModel: List<Pair<Float, Float>> by lazy {
        val r = cornerRadius.coerceIn(0f, minOf(hw, hh))
        val pts = ArrayList<Pair<Float, Float>>(OUTLINE_SEGMENTS * 4 + 4)
        val arcs = listOf(
            Triple(-hw + r, -hh + r, 180f), Triple(hw - r, -hh + r, 270f),
            Triple(hw - r, hh - r, 0f), Triple(-hw + r, hh - r, 90f)
        )
        for ((acx, acy, start) in arcs) {
            for (s in 0..OUTLINE_SEGMENTS) {
                val a = (start + 90f * s / OUTLINE_SEGMENTS) * (Math.PI / 180.0).toFloat()
                pts += (acx + r * kotlin.math.cos(a)) to (acy + r * kotlin.math.sin(a))
            }
        }
        pts
    }

    private fun outlineAt(
        z: Float,
        projector: (Float, Float, Float) -> CubeGeometry.Projected
    ): List<CubeGeometry.Projected> = outlineModel.map { (x, y) -> projector(x, y, z) }

    /** The front face outline. Projected rather than clipped, so it carries perspective. */
    val outline: List<CubeGeometry.Projected> by lazy { outlineAt(hd, ::project) }

    /** The same outline swept to the back of the solid. */
    val backOutline: List<CubeGeometry.Projected> by lazy { outlineAt(-hd, ::project) }

    /**
     * The visible side wall, as quads swept along the rounded outline.
     *
     * Extruding the outline rather than the sharp corner quads is what keeps the
     * thickness from poking out past the rounded corners - the front face is a rounded
     * rect, so its extrusion has to be one too.
     */
    val visibleRim: List<List<CubeGeometry.Projected>> by lazy {
        val front = outline
        val back = backOutline
        val rawFront = outlineAt(hd, ::projectRaw)
        val rawBack = outlineAt(-hd, ::projectRaw)
        front.indices.mapNotNull { i ->
            val j = (i + 1) % front.size
            val facing = CubeGeometry.signedArea(
                listOf(rawFront[i], rawFront[j], rawBack[j], rawBack[i])
            ) < 0f
            if (facing) listOf(front[i], front[j], back[j], back[i]) else null
        }
    }

    /** The outline swept back to the art plane. */
    val artOutline: List<CubeGeometry.Projected> by lazy { outlineAt(artZ, ::project) }

    /**
     * The interior of the side wall, seen through the front face.
     *
     * These are the segments turned *away* from the viewer - looking through the glass
     * you see their inside. They appear on the opposite edges from [visibleRim], which
     * is the whole depth cue: exterior thickness on one side, interior on the other.
     */
    val visibleInnerWall: List<List<CubeGeometry.Projected>> by lazy {
        val front = outline
        val art = artOutline
        val rawFront = outlineAt(hd, ::projectRaw)
        val rawArt = outlineAt(artZ, ::projectRaw)
        front.indices.mapNotNull { i ->
            val j = (i + 1) % front.size
            val awayFacing = CubeGeometry.signedArea(
                listOf(rawFront[i], rawFront[j], rawArt[j], rawArt[i])
            ) > 0f
            if (awayFacing) listOf(front[i], front[j], art[j], art[i]) else null
        }
    }

    /** Mean front-to-back offset of the visible rim, for orienting its gradient. */
    val rimSweep: Pair<Float, Float>
        get() {
            val quads = visibleRim
            if (quads.isEmpty()) return 0f to 0f
            var dx = 0f
            var dy = 0f
            for (q in quads) {
                dx += (q[2].x + q[3].x - q[0].x - q[1].x) / 2f
                dy += (q[2].y + q[3].y - q[0].y - q[1].y) / 2f
            }
            return (dx / quads.size) to (dy / quads.size)
        }

    /** Derived from the unmirrored projection, so a reflection shows the same faces. */
    val visibleSideFaces: List<CubeGeometry.CubeFace> by lazy {
        CubeGeometry.visibleSideFaces(cornersVia(::projectRaw))
    }

    fun faceQuad(face: CubeGeometry.CubeFace): List<CubeGeometry.Projected> =
        CubeGeometry.faceQuad(corners, face)

    /** Lowest projected point of the whole solid. */
    val bottom: Float get() = corners.all.maxOf { it.y }

    val boundsLeft: Float get() = corners.all.minOf { it.x }
    val boundsRight: Float get() = corners.all.maxOf { it.x }
    val boundsTop: Float get() = corners.all.minOf { it.y }

    private companion object {
        const val OUTLINE_SEGMENTS = 8
    }

    /** Mean offset from the front surface to the art plane. */
    val artSweep: Pair<Float, Float>
        get() {
            val f = corners.frontQuad
            val a = listOf(
                projectArtUV(0f, 0f), projectArtUV(1f, 0f),
                projectArtUV(1f, 1f), projectArtUV(0f, 1f)
            )
            var dx = 0f
            var dy = 0f
            for (i in 0..3) {
                dx += a[i].x - f[i].x
                dy += a[i].y - f[i].y
            }
            return (dx / 4f) to (dy / 4f)
        }

    fun mirroredBelow(line: Float, compress: Float): CubePose = CubePose(
        halfWidth, halfHeight, halfDepth, cornerRadius,
        rotXDeg, rotYDeg, rollDeg, cameraZ, centerX, centerY, scale, artDepthFraction,
        mirrorLine = line, mirrorCompress = compress
    )
}

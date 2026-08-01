package com.visibeat.musicui.playback

import com.visibeat.musicui.playback.CubeGeometry.CubeFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Validation for the album cube.
 *
 * The bugs these guard against were all live in the previous implementation:
 * a camera closer than the object (corners projected 42,000px off-screen), a
 * left-handed projection that drew the extrusion on the hidden edges, and a linear
 * FFT mapping that squeezed every bar into the bass.
 */
class CubeGeometryTest {

    // 100dp face, 14dp thick, at 2.75x density.
    private val halfW = 137.5f
    private val halfH = 137.5f
    private val halfD = 19.25f
    private val cubeWidth = halfW * 2f
    private val cameraZ = CubeGeometry.cameraDistanceFor(cubeWidth)

    private fun corners(
        rotX: Float = CubeGeometry.BASE_TILT_X,
        rotY: Float = CubeGeometry.BASE_TILT_Y,
        scale: Float = 1f,
        roll: Float = 0f
    ) = CubeGeometry.projectCube(
        halfWidth = halfW, halfHeight = halfH, halfDepth = halfD,
        rotXDeg = rotX, rotYDeg = rotY,
        cameraZ = cameraZ, centerX = 0f, centerY = 0f, scale = scale, rollDeg = roll
    )

    /** The animated tilt range: base +/- bass bonus +/- idle drift, with headroom. */
    private val tiltXRange = -45..45
    private val tiltYRange = -45..45

    // ── The regression that started this ──────────────────────────────────────

    @Test
    fun `no corner ever projects behind the camera`() {
        var worst = Float.MAX_VALUE
        var worstAngles = 0f to 0f
        for (rx in tiltXRange step 3) {
            for (ry in tiltYRange step 3) {
                for (scale in listOf(1f, 1.08f)) {
                    val c = corners(rx.toFloat(), ry.toFloat(), scale)
                    for (p in c.all) {
                        if (p.denominator < worst) {
                            worst = p.denominator
                            worstAngles = rx.toFloat() to ry.toFloat()
                        }
                    }
                }
            }
        }
        assertTrue(
            "perspective divisor collapsed to $worst at tilt $worstAngles — " +
                "camera is too close to the cube",
            worst > cameraZ * 0.5f
        )
    }

    @Test
    fun `projected corners stay near the cube instead of flying off screen`() {
        // The old camera distance sent one corner to roughly 42,000px. Nothing may
        // land further than a couple of cube widths from centre.
        val limit = cubeWidth * 2f
        for (rx in tiltXRange step 3) {
            for (ry in tiltYRange step 3) {
                val c = corners(rx.toFloat(), ry.toFloat(), 1.08f)
                val furthest = CubeGeometry.maxAbsCoordinate(c, 0f, 0f)
                assertTrue(
                    "corner reached ${furthest}px from centre at tilt $rx/$ry (limit $limit)",
                    furthest < limit
                )
            }
        }
    }

    @Test
    fun `old camera distance would have been rejected`() {
        // 12f * density was the value the previous implementation used: ~33px against
        // a 275px cube. Prove it actually breaks, so nobody reintroduces it.
        val badCamera = 12f * 2.75f
        val c = CubeGeometry.projectCube(
            halfWidth = halfW, halfHeight = halfH, halfDepth = halfD,
            rotXDeg = 18f, rotYDeg = 22f,
            cameraZ = badCamera, centerX = 0f, centerY = 0f
        )
        assertTrue(
            "expected the old camera distance to put corners behind the view plane",
            c.all.any { it.denominator <= 0f }
        )
        assertTrue(
            "recommended camera distance should be far larger than the old one",
            cameraZ > badCamera * 10f
        )
    }

    // ── Handedness ────────────────────────────────────────────────────────────

    @Test
    fun `positive rotationY swings the right edge away like Compose does`() {
        val c = corners(rotX = 0f, rotY = 22f)
        assertTrue(
            "right edge should sit further from the camera",
            c.frontTR.depth < c.frontTL.depth
        )
        // A receding edge foreshortens: the right edge must project shorter than the left.
        val rightEdge = abs(c.frontTR.y - c.frontBR.y)
        val leftEdge = abs(c.frontTL.y - c.frontBL.y)
        assertTrue(
            "right edge ($rightEdge) should foreshorten against the left ($leftEdge)",
            rightEdge < leftEdge
        )
    }

    @Test
    fun `positive rotationX swings the bottom edge forward like Compose does`() {
        val c = corners(rotX = 18f, rotY = 0f)
        assertTrue(
            "bottom edge should come toward the viewer for positive rotationX",
            c.frontBL.depth > c.frontTL.depth
        )
    }

    @Test
    fun `positive rotationY exposes the left face, not the right`() {
        val c = corners(rotX = 0f, rotY = 22f)
        val visible = CubeGeometry.visibleSideFaces(c)
        assertTrue("expected LEFT visible, got $visible", visible.contains(CubeFace.LEFT))
        assertFalse("RIGHT must be hidden when it recedes", visible.contains(CubeFace.RIGHT))
    }

    @Test
    fun `default tilt exposes exactly the left and bottom faces`() {
        val visible = CubeGeometry.visibleSideFaces(corners()).toSet()
        // Both reference renders show the thick edge on the left of the artwork.
        assertEquals(
            "the Longhorn presentation needs the left and bottom slivers",
            setOf(CubeFace.LEFT, CubeFace.BOTTOM),
            visible
        )
    }

    // ── Roll ──────────────────────────────────────────────────────────────────

    @Test
    fun `roll rotates the silhouette in the picture plane`() {
        val upright = corners(roll = 0f)
        val rolled = corners(roll = -12f)
        val radians = -12.0 * Math.PI / 180.0
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()

        for ((a, b) in upright.all.zip(rolled.all)) {
            val expectedX = a.x * cos - a.y * sin
            val expectedY = a.x * sin + a.y * cos
            assertEquals(expectedX.toDouble(), b.x.toDouble(), 0.01)
            assertEquals(expectedY.toDouble(), b.y.toDouble(), 0.01)
        }
    }

    @Test
    fun `roll does not change which faces are visible`() {
        val upright = CubeGeometry.visibleSideFaces(corners(roll = 0f))
        for (roll in -45..45 step 5) {
            assertEquals(
                "roll $roll changed face visibility — it must only rotate the picture plane",
                upright,
                CubeGeometry.visibleSideFaces(corners(roll = roll.toFloat()))
            )
        }
    }

    @Test
    fun `roll preserves depth and the perspective divisor`() {
        val upright = corners(roll = 0f)
        val rolled = corners(roll = CubeGeometry.BASE_ROLL)
        for ((a, b) in upright.all.zip(rolled.all)) {
            assertEquals(a.depth.toDouble(), b.depth.toDouble(), 0.001)
            assertEquals(a.denominator.toDouble(), b.denominator.toDouble(), 0.001)
        }
    }

    @Test
    fun `roll preserves the front face area`() {
        val upright = abs(CubeGeometry.signedArea(corners(roll = 0f).frontQuad))
        val rolled = abs(CubeGeometry.signedArea(corners(roll = -30f).frontQuad))
        assertEquals(upright.toDouble(), rolled.toDouble(), upright * 0.001)
    }

    @Test
    fun `opposing faces are never visible at the same time`() {
        for (rx in tiltXRange step 5) {
            for (ry in tiltYRange step 5) {
                if (rx == 0 || ry == 0) continue
                val visible = CubeGeometry.visibleSideFaces(corners(rx.toFloat(), ry.toFloat()))
                assertFalse(
                    "left and right both visible at $rx/$ry",
                    visible.containsAll(listOf(CubeFace.LEFT, CubeFace.RIGHT))
                )
                assertFalse(
                    "top and bottom both visible at $rx/$ry",
                    visible.containsAll(listOf(CubeFace.TOP, CubeFace.BOTTOM))
                )
            }
        }
    }

    @Test
    fun `side faces are drawn farthest first`() {
        val c = corners()
        val visible = CubeGeometry.visibleSideFaces(c)
        val depths = visible.map { face ->
            CubeGeometry.faceQuad(c, face).map { it.depth }.average()
        }
        assertEquals(depths.sortedBy { it }, depths)
    }

    // ── Front face integrity ──────────────────────────────────────────────────

    @Test
    fun `front face quad stays convex and consistently wound`() {
        for (rx in tiltXRange step 3) {
            for (ry in tiltYRange step 3) {
                val area = CubeGeometry.signedArea(corners(rx.toFloat(), ry.toFloat()).frontQuad)
                assertTrue(
                    "front face degenerated or flipped winding at tilt $rx/$ry (area $area)",
                    area > 0f
                )
            }
        }
    }

    @Test
    fun `scale grows the front face and the side faces together`() {
        val plain = corners(scale = 1f)
        val scaled = corners(scale = 1.08f)
        fun growth(face: CubeFace) =
            abs(CubeGeometry.signedArea(CubeGeometry.faceQuad(scaled, face))) /
                abs(CubeGeometry.signedArea(CubeGeometry.faceQuad(plain, face)))

        val frontGrowth = growth(CubeFace.FRONT)
        // The old code applied scale only to the front face's graphicsLayer, so the art
        // pulsed off its own extrusion on every bass hit. Every face must move together.
        for (face in listOf(CubeFace.RIGHT, CubeFace.BOTTOM)) {
            val sideGrowth = growth(face)
            assertTrue(
                "$face did not grow with the front face (front $frontGrowth, side $sideGrowth)",
                sideGrowth > 1.10f
            )
            // Faces at different depths foreshorten differently, so they track the front
            // face closely rather than exactly.
            assertEquals(
                "$face growth drifted from the front face",
                frontGrowth.toDouble(), sideGrowth.toDouble(), 0.06
            )
        }
    }

    @Test
    fun `rounded outline traces the front face corners`() {
        val outline = CubeGeometry.projectRoundedFrontOutline(
            halfWidth = halfW, halfHeight = halfH, halfDepth = halfD,
            cornerRadius = 27.5f,
            rotXDeg = CubeGeometry.BASE_TILT_X, rotYDeg = CubeGeometry.BASE_TILT_Y,
            cameraZ = cameraZ, centerX = 0f, centerY = 0f
        )
        assertEquals(28, outline.size)
        val c = corners()
        val quadXs = c.frontQuad.map { it.x }
        val quadYs = c.frontQuad.map { it.y }
        // Rounded corners inset the outline, so it must sit inside the sharp quad.
        assertTrue(outline.all { it.x >= quadXs.min() - 0.5f && it.x <= quadXs.max() + 0.5f })
        assertTrue(outline.all { it.y >= quadYs.min() - 0.5f && it.y <= quadYs.max() + 0.5f })
        assertTrue(outline.all { it.denominator > 0f })
    }

    // ── FFT mapping ───────────────────────────────────────────────────────────

    @Test
    fun `log bin edges are strictly increasing and cover the spectrum`() {
        val edges = CubeGeometry.logBinEdges(binCount = 512, barCount = 16)
        assertEquals(17, edges.size)
        assertEquals(1, edges.first())
        assertEquals(512, edges.last())
        for (i in 1 until edges.size) {
            assertTrue("edges not increasing at $i: ${edges.toList()}", edges[i] > edges[i - 1])
        }
    }

    @Test
    fun `log bins reach beyond the bass unlike linear bins`() {
        val edges = CubeGeometry.logBinEdges(binCount = 512, barCount = 16)
        // The old code read bins 1..16 — under 700Hz at 44.1kHz. The midpoint bar
        // should now be well up the spectrum instead.
        assertTrue("bar 8 still stuck in the bass: ${edges[8]}", edges[8] > 16)
        assertTrue("top bar should reach the last bin", edges[16] >= 400)
    }

    @Test
    fun `log bin edges stay in bounds when bars outnumber bins`() {
        val edges = CubeGeometry.logBinEdges(binCount = 8, barCount = 32)
        for (i in 1 until edges.size) assertTrue(edges[i] > edges[i - 1])
        assertTrue(edges.last() <= 8)
        assertTrue(edges.first() >= 1)
    }

    @Test
    fun `magnitude reads never run past the end of the fft buffer`() {
        val fft = ByteArray(16) { 100 }
        // Bin 8 needs bytes 16 and 17, which do not exist.
        assertEquals(0f, CubeGeometry.magnitudeAt(fft, 8), 0f)
        assertEquals(0f, CubeGeometry.magnitudeAt(fft, 999), 0f)
        assertEquals(0f, CubeGeometry.magnitudeAt(fft, -1), 0f)
        assertEquals(0f, CubeGeometry.bandMagnitude(fft, 8, 40), 0f)
    }

    @Test
    fun `every log bar reads within bounds for a real capture size`() {
        val fft = ByteArray(1024) { (it % 90).toByte() }
        val binCount = fft.size / 2
        val edges = CubeGeometry.logBinEdges(binCount, 16)
        for (i in 0 until edges.size - 1) {
            val magnitude = CubeGeometry.bandMagnitude(fft, edges[i], edges[i + 1])
            assertTrue("bar $i produced $magnitude", magnitude.isFinite() && magnitude >= 0f)
        }
    }

    @Test
    fun `silence produces no bass energy`() {
        assertEquals(0f, CubeGeometry.bassMagnitude(ByteArray(1024)), 0f)
        assertEquals(0f, CubeGeometry.bassMagnitude(ByteArray(0)), 0f)
        assertEquals(0f, CubeGeometry.bassMagnitude(ByteArray(4)), 0f)
    }

    @Test
    fun `peak tracker normalises loud and quiet material to the same range`() {
        val loud = CubeGeometry.PeakTracker()
        val quiet = CubeGeometry.PeakTracker()
        var loudOut = 0f
        var quietOut = 0f
        repeat(200) {
            loudOut = loud.normalize(120f)
            quietOut = quiet.normalize(6f)
        }
        assertEquals(
            "a quiet track should drive the cube as hard as a loud one",
            loudOut.toDouble(), quietOut.toDouble(), 0.01
        )
        assertTrue(loudOut > 0.9f)
    }

    @Test
    fun `peak tracker output is always bounded`() {
        val tracker = CubeGeometry.PeakTracker()
        val samples = listOf(0f, 181f, 3f, 90f, 0f, 181f, 0.001f)
        repeat(50) {
            for (s in samples) {
                val v = tracker.normalize(s)
                assertTrue("normalised value out of range: $v", v in 0f..1f)
            }
        }
    }

    @Test
    fun `peak decays so the visualiser recovers after a loud passage`() {
        val tracker = CubeGeometry.PeakTracker()
        tracker.normalize(180f)
        val immediately = tracker.normalize(20f)
        repeat(3000) { tracker.normalize(20f) }
        val later = tracker.normalize(20f)
        assertTrue("expected recovery after decay: $immediately -> $later", later > immediately)
    }

    // ── CubePose ──────────────────────────────────────────────────────────────

    private fun pose(scale: Float = 1f) = CubePose(
        halfWidth = halfW, halfHeight = halfH, halfDepth = halfD,
        cornerRadius = 27.5f,
        rotXDeg = CubeGeometry.BASE_TILT_X,
        rotYDeg = CubeGeometry.BASE_TILT_Y,
        rollDeg = CubeGeometry.BASE_ROLL,
        cameraZ = cameraZ, centerX = 0f, centerY = 0f, scale = scale
    )

    @Test
    fun `pose corners match the standalone projection`() {
        val fromPose = pose().corners
        val direct = corners(roll = CubeGeometry.BASE_ROLL)
        for ((a, b) in fromPose.all.zip(direct.all)) {
            assertEquals(a.x.toDouble(), b.x.toDouble(), 0.001)
            assertEquals(a.y.toDouble(), b.y.toDouble(), 0.001)
        }
    }

    @Test
    fun `front UV corners land on the front quad`() {
        val p = pose()
        val corners = p.corners
        val pairs = listOf(
            p.projectFrontUV(0f, 0f) to corners.frontTL,
            p.projectFrontUV(1f, 0f) to corners.frontTR,
            p.projectFrontUV(1f, 1f) to corners.frontBR,
            p.projectFrontUV(0f, 1f) to corners.frontBL
        )
        for ((uv, corner) in pairs) {
            assertEquals(uv.x.toDouble(), corner.x.toDouble(), 0.001)
            assertEquals(uv.y.toDouble(), corner.y.toDouble(), 0.001)
        }
    }

    @Test
    fun `side UV spans the face from its front edge to its back edge`() {
        val p = pose()
        val quad = p.faceQuad(CubeFace.LEFT)
        // LEFT quad is [frontBL, frontTL, backTL, backBL].
        val frontTop = p.projectSideUV(CubeFace.LEFT, along = 0f, depth = 0f)
        val backTop = p.projectSideUV(CubeFace.LEFT, along = 0f, depth = 1f)
        assertEquals(quad[1].x.toDouble(), frontTop.x.toDouble(), 0.001)
        assertEquals(quad[1].y.toDouble(), frontTop.y.toDouble(), 0.001)
        assertEquals(quad[2].x.toDouble(), backTop.x.toDouble(), 0.001)
        assertEquals(quad[2].y.toDouble(), backTop.y.toDouble(), 0.001)
    }

    @Test
    fun `mirrored pose reports the same visible faces as its source`() {
        val source = pose()
        val mirrored = source.mirroredBelow(source.bottom + 8f, 0.42f)
        // Mirroring reverses the projected winding, so a naive signed-area test would
        // report the opposite faces and the reflection would show the wrong sides.
        assertEquals(source.visibleSideFaces, mirrored.visibleSideFaces)
        assertTrue(mirrored.isMirrored)
    }

    @Test
    fun `mirrored pose reflects and compresses below the mirror line`() {
        val source = pose()
        val line = source.bottom + 8f
        val mirrored = source.mirroredBelow(line, 0.42f)

        assertTrue("reflection must sit below the mirror line", mirrored.boundsTop >= line - 0.01f)
        for ((a, b) in source.corners.all.zip(mirrored.corners.all)) {
            assertEquals(a.x.toDouble(), b.x.toDouble(), 0.001)
            assertEquals((line + (line - a.y) * 0.42f).toDouble(), b.y.toDouble(), 0.001)
        }
    }

    @Test
    fun `pose scale grows the whole solid`() {
        val small = pose(scale = 1f)
        val large = pose(scale = 1.08f)
        assertTrue(large.boundsRight - large.boundsLeft > small.boundsRight - small.boundsLeft)
        assertTrue(
            abs(CubeGeometry.signedArea(large.faceQuad(CubeFace.LEFT))) >
                abs(CubeGeometry.signedArea(small.faceQuad(CubeFace.LEFT)))
        )
    }

    // ── Rim extrusion ─────────────────────────────────────────────────────────

    @Test
    fun `rim is extruded from the rounded outline, not the sharp corners`() {
        // The regression: extruding the sharp corner quads made the thickness poke out
        // past the rounded corners in little triangles.
        val p = pose()
        val outline = p.outline
        assertTrue("expected a visible rim at the default tilt", p.visibleRim.isNotEmpty())
        for (quad in p.visibleRim) {
            val startOnOutline = outline.any {
                abs(it.x - quad[0].x) < 0.01f && abs(it.y - quad[0].y) < 0.01f
            }
            val endOnOutline = outline.any {
                abs(it.x - quad[1].x) < 0.01f && abs(it.y - quad[1].y) < 0.01f
            }
            assertTrue("rim front edge left the front outline", startOnOutline && endOnOutline)
        }
    }

    @Test
    fun `rim stays within the silhouette of the two outlines`() {
        val p = pose()
        val hull = p.outline + p.backOutline
        val left = hull.minOf { it.x }
        val right = hull.maxOf { it.x }
        val top = hull.minOf { it.y }
        val bottom = hull.maxOf { it.y }
        for (quad in p.visibleRim) {
            for (v in quad) {
                assertTrue(
                    "rim vertex (${v.x}, ${v.y}) escaped the outline silhouette",
                    v.x >= left - 0.01f && v.x <= right + 0.01f &&
                        v.y >= top - 0.01f && v.y <= bottom + 0.01f
                )
            }
        }
    }

    @Test
    fun `rim sits on the same side as the visible faces`() {
        val p = pose()
        assertEquals(setOf(CubeFace.LEFT, CubeFace.BOTTOM), p.visibleSideFaces.toSet())
        // The front-to-back offset must run left and down: leftward exposes the left
        // wall, downward exposes the bottom wall. Either sign flipping would put the
        // thickness on an edge the viewer cannot see.
        val (sweepX, sweepY) = p.rimSweep
        assertTrue("rim should sweep leftward, got $sweepX", sweepX < 0f)
        assertTrue("rim should sweep downward, got $sweepY", sweepY > 0f)
    }

    @Test
    fun `head-on pose shows no rim at all`() {
        val flat = CubePose(
            halfWidth = halfW, halfHeight = halfH, halfDepth = halfD,
            cornerRadius = 27.5f,
            rotXDeg = 0f, rotYDeg = 0f, rollDeg = 0f,
            cameraZ = cameraZ, centerX = 0f, centerY = 0f
        )
        assertTrue(
            "a face-on cube has no visible thickness, got ${flat.visibleRim.size} segments",
            flat.visibleRim.isEmpty()
        )
    }

    // ── Art plane / under-glass depth ─────────────────────────────────────────

    private fun glassPose(artDepth: Float = 0.85f) = CubePose(
        halfWidth = halfW, halfHeight = halfH, halfDepth = halfD,
        cornerRadius = 27.5f,
        rotXDeg = CubeGeometry.BASE_TILT_X,
        rotYDeg = CubeGeometry.BASE_TILT_Y,
        rollDeg = CubeGeometry.BASE_ROLL,
        cameraZ = cameraZ, centerX = 0f, centerY = 0f,
        artDepthFraction = artDepth
    )

    @Test
    fun `art plane sits behind the front surface`() {
        val p = glassPose()
        val front = p.projectFrontUV(0.5f, 0.5f)
        val art = p.projectArtUV(0.5f, 0.5f)
        assertTrue(
            "art must be further from the camera than the glass surface",
            art.depth < front.depth
        )
        assertTrue("art should project smaller", art.denominator > front.denominator)
    }

    @Test
    fun `art flush with the front surface leaves no inner wall`() {
        // This is the bug the reference comparison caught: art drawn at z = +halfDepth is
        // coplanar with the glass, so nothing sits above it and it reads as printed on.
        val flush = glassPose(artDepth = 0f)
        assertTrue(
            "a flush art plane cannot produce an inner wall",
            flush.visibleInnerWall.isEmpty()
        )
        val front = flush.projectFrontUV(0.3f, 0.7f)
        val art = flush.projectArtUV(0.3f, 0.7f)
        assertEquals(front.x.toDouble(), art.x.toDouble(), 0.001)
        assertEquals(front.y.toDouble(), art.y.toDouble(), 0.001)
    }

    @Test
    fun `sinking the art produces an inner wall`() {
        assertTrue(glassPose(artDepth = 0.85f).visibleInnerWall.isNotEmpty())
    }

    @Test
    fun `inner wall appears on the edges the exterior rim does not`() {
        val p = glassPose()
        fun meanFrontEdge(quads: List<List<CubeGeometry.Projected>>): Pair<Float, Float> {
            val xs = quads.map { (it[0].x + it[1].x) / 2f }
            val ys = quads.map { (it[0].y + it[1].y) / 2f }
            return xs.average().toFloat() to ys.average().toFloat()
        }
        val (rimX, rimY) = meanFrontEdge(p.visibleRim)
        val (wallX, wallY) = meanFrontEdge(p.visibleInnerWall)

        // Exterior thickness on the left and bottom, interior on the right and top.
        // If these ever land on the same edges the solid stops reading as glass.
        assertTrue("rim should sit left of centre, got $rimX", rimX < 0f)
        assertTrue("inner wall should sit right of centre, got $wallX", wallX > 0f)
        assertTrue("rim should sit below centre, got $rimY", rimY > 0f)
        assertTrue("inner wall should sit above centre, got $wallY", wallY < 0f)
    }

    @Test
    fun `art sweep follows the same front-to-back direction as the rim`() {
        val p = glassPose()
        val (rimX, rimY) = p.rimSweep
        val (artX, artY) = p.artSweep
        assertTrue("art and rim must sweep the same way in x", rimX * artX > 0f)
        assertTrue("art and rim must sweep the same way in y", rimY * artY > 0f)
    }

    @Test
    fun `deeper art plane shifts further from the front surface`() {
        val shallow = glassPose(artDepth = 0.3f).artSweep
        val deep = glassPose(artDepth = 0.9f).artSweep
        assertTrue(
            "a deeper art plane must displace further",
            abs(deep.first) > abs(shallow.first)
        )
    }
}

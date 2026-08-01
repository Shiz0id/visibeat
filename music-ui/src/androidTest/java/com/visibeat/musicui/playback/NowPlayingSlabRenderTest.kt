package com.visibeat.musicui.playback

import android.graphics.Bitmap
import android.graphics.Paint
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.visibeat.viewengine.TimelineItemRow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Renders the slab on a device and inspects the pixels.
 *
 * CubeGeometryTest proves the projection maths; this proves the maths reaches the
 * screen — that the extrusion lands on the visible edge, and that the reflection fades
 * away from the cube instead of toward it.
 *
 * This drives a ComposeView directly rather than using compose-ui-test, whose Espresso
 * dependency reflects into InputManager.getInstance and cannot run on Android 16.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingSlabRenderTest {

    private val background = Color(0xFF0A1A2A)
    private val backgroundArgb = android.graphics.Color.rgb(10, 26, 42)

    private fun artFile(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "test_art.png")
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(200, 40, 90))
            drawCircle(150f, 150f, 90f, Paint().apply {
                color = android.graphics.Color.rgb(250, 210, 60)
                isAntiAlias = true
            })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun track(art: File) = TimelineItemRow(
        trackId = 1L,
        effectiveReleaseDateEpochMs = 0L,
        effectiveTitle = "Test Track",
        effectiveAlbumTitle = "Test Album",
        effectiveArtistDisplay = "Test Artist",
        releaseId = 1L,
        primaryArtistId = 1L,
        artPath = art.absolutePath
    )

    /** Renders the slab at the top-left of the window and returns the pixels. */
    private fun render(showReflection: Boolean, name: String): Shot {
        val art = artFile()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        var captured: Bitmap? = null
        var densityScale = 1f

        scenario.onActivity { activity ->
            val view = ComposeView(activity)
            view.setContent {
                Box(Modifier.fillMaxSize().background(background)) {
                    NowPlayingSlab(
                        state = PlaybackState(currentTrack = track(art), isPlaying = false),
                        fftData = ByteArray(0),
                        visualizerColors = emptyList(),
                        showReflection = showReflection,
                        onClick = {}
                    )
                }
            }
            activity.setContentView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            densityScale = activity.resources.displayMetrics.density
        }

        // Real frame clock: give the art decode, palette extraction and springs time.
        Thread.sleep(2500)

        // PixelCopy reads the hardware-rendered surface. A software Canvas cannot be used
        // here: the art is drawn through a perspective (non-affine) matrix from
        // setPolyToPoly, which software Skia does not render.
        var viewTop = 0
        val latch = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { activity ->
            val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val location = IntArray(2)
            view.getLocationInWindow(location)
            viewTop = location[1]
            val bitmap = Bitmap.createBitmap(
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            android.view.PixelCopy.request(
                activity.window,
                android.graphics.Rect(
                    location[0], location[1],
                    location[0] + view.width, location[1] + view.height
                ),
                bitmap,
                { result ->
                    if (result == android.view.PixelCopy.SUCCESS) captured = bitmap
                    latch.countDown()
                },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        scenario.close()
        check(viewTop >= 0)

        val bitmap = requireNotNull(captured) { "capture failed" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null) ?: context.cacheDir
        File(dir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return Shot(bitmap, densityScale)
    }

    private inner class Shot(val bitmap: Bitmap, val density: Float) {
        fun dp(value: Float) = value * density

        /** Distance of a pixel from the flat background, 0..255. */
        fun deviation(x: Int, y: Int): Float {
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return 0f
            val p = bitmap.getPixel(x, y)
            return (abs(android.graphics.Color.red(p) - android.graphics.Color.red(backgroundArgb)) +
                abs(android.graphics.Color.green(p) - android.graphics.Color.green(backgroundArgb)) +
                abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(backgroundArgb))) / 3f
        }

        fun isDrawn(x: Int, y: Int) = deviation(x, y) > 10f

        /** Front-face x extents across the whole idle drift band, in pixels. */
        fun frontFaceXRange(): ClosedFloatingPointRange<Float> {
            val face = dp(NowPlayingSlabMetrics.FaceSize.value)
            val halfD = dp(NowPlayingSlabMetrics.Thickness.value) / 2f
            val centerX = dp(NowPlayingSlabMetrics.Width.value) / 2f
            val centerY = dp(NowPlayingSlabMetrics.Margin.value) + face / 2f
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (driftTenths in -30..30) {
                val corners = CubeGeometry.projectCube(
                    halfWidth = face / 2f, halfHeight = face / 2f, halfDepth = halfD,
                    rotXDeg = CubeGeometry.BASE_TILT_X,
                    rotYDeg = CubeGeometry.BASE_TILT_Y + driftTenths / 10f,
                    cameraZ = CubeGeometry.cameraDistanceFor(face),
                    centerX = centerX, centerY = centerY,
                    rollDeg = CubeGeometry.BASE_ROLL
                )
                lo = minOf(lo, corners.frontQuad.minOf { it.x })
                hi = maxOf(hi, corners.frontQuad.maxOf { it.x })
            }
            return lo..hi
        }
    }

    @Test
    fun cubeExtrudesOnTheVisibleLeftEdgeAndNotTheRight() {
        val shot = render(showReflection = true, name = "slab_cube.png")
        val faceX = shot.frontFaceXRange()
        val midY = (shot.dp(NowPlayingSlabMetrics.Margin.value) +
            shot.dp(NowPlayingSlabMetrics.FaceSize.value) / 2f).toInt()

        var leftMost = shot.bitmap.width
        var rightMost = -1
        val scanLimit = shot.dp(NowPlayingSlabMetrics.Width.value).toInt() + 8
        for (x in 0 until minOf(scanLimit, shot.bitmap.width)) {
            if (shot.isDrawn(x, midY)) {
                if (x < leftMost) leftMost = x
                if (x > rightMost) rightMost = x
            }
        }

        assertTrue("nothing rendered on the cube's mid row", rightMost > leftMost)
        assertTrue(
            "expected the extrusion past the front face's left edge " +
                "(silhouette left $leftMost, front face left ${faceX.start})",
            leftMost < faceX.start - 1f
        )
        assertTrue(
            "the right edge must stay flush with the front face — a right sliver means " +
                "the extrusion is on the hidden side (silhouette right $rightMost, " +
                "front face right ${faceX.endInclusive})",
            rightMost <= faceX.endInclusive + 2f
        )
    }

    @Test
    fun reflectionFadesAwayFromTheCube() {
        val shot = render(showReflection = true, name = "slab_reflection.png")
        val bandTop = shot.dp(NowPlayingSlabMetrics.CubeHeight.value).toInt() + 2
        val bandBottom = shot.dp(NowPlayingSlabMetrics.height(true).value).toInt() - 2
        val width = shot.dp(NowPlayingSlabMetrics.Width.value).toInt()
        assertTrue("no reflection band to measure", bandBottom - bandTop > 12)

        fun meanDeviation(fromY: Int, toY: Int): Float {
            var sum = 0f
            var count = 0
            for (y in fromY until toY) {
                for (x in 0 until width) {
                    sum += shot.deviation(x, y)
                    count++
                }
            }
            return if (count == 0) 0f else sum / count
        }

        val third = (bandBottom - bandTop) / 3
        val near = meanDeviation(bandTop, bandTop + third)
        val far = meanDeviation(bandBottom - third, bandBottom)

        assertTrue("no reflection rendered at all (near=$near)", near > 1f)
        assertTrue(
            "reflection must be strongest next to the cube and fade with distance — " +
                "got near=$near far=$far, which is the inverted mask bug",
            near > far * 1.5f
        )
    }

    @Test
    fun cubeStaysInsideItsDeclaredBounds() {
        val shot = render(showReflection = true, name = "slab_bounds.png")
        val width = shot.dp(NowPlayingSlabMetrics.Width.value).toInt()
        val height = shot.dp(NowPlayingSlabMetrics.height(true).value).toInt()

        var outside = 0
        // A column just past the slab's right edge, and a row just past its bottom.
        for (y in 0 until height + 4) {
            for (x in width + 1 until minOf(width + 6, shot.bitmap.width)) {
                if (shot.isDrawn(x, y)) outside++
            }
        }
        for (y in height + 1 until minOf(height + 6, shot.bitmap.height)) {
            for (x in 0 until width) {
                if (shot.isDrawn(x, y)) outside++
            }
        }
        assertTrue(
            "geometry painted $outside px outside the slab's declared ${width}x$height bounds",
            outside == 0
        )
    }
}

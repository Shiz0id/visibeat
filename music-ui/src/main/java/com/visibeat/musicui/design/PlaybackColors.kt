package com.visibeat.musicui.design

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette

/**
 * Palette for the 3D album cube: side faces, specular edge, and visualizer colors.
 */
data class CubePalette(
    val sideFacePrimary: Color,
    val sideFaceShadow: Color,
    val specularHighlight: Color,
    val visualizerColors: List<Color>
)

object PlaybackColors {

    /**
     * Extracts dominant colors from a bitmap and generates 5 complimentary colors.
     * Used for wallpaper-derived palette (backward compat).
     */
    fun generateVisualizerPalette(bitmap: Bitmap?): List<Color> {
        if (bitmap == null) return defaultPalette

        val palette = Palette.from(bitmap).generate()
        val dominantColor = Color(palette.getDominantColor(0xFF000000.toInt()))

        return generateHarmonicColors(dominantColor, 5)
    }

    /**
     * Extract a palette from album art for the 3D cube.
     * Derives side-face color from the album's dominant palette.
     */
    fun generateCubePalette(bitmap: Bitmap?): CubePalette {
        if (bitmap == null) return defaultCubePalette

        val palette = Palette.from(bitmap).generate()

        val baseColor = palette.getVibrantColor(
            palette.getDominantColor(0xFF8B7355.toInt())
        ).let { Color(it) }

        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(baseColor.toArgb(), hsv)

        // Low floors on purpose: a dark album should get dark rich sides. The previous
        // floors (0.3 on value) forced every cube to roughly the same mid brightness.
        val sidePrimary = Color.hsv(
            hsv[0],
            (hsv[1] * 0.9f).coerceIn(0.15f, 0.9f),
            (hsv[2] * 0.8f).coerceIn(0.10f, 0.85f)
        )
        val sideShadow = Color.hsv(
            hsv[0],
            (hsv[1] * 0.75f).coerceIn(0.10f, 0.75f),
            (hsv[2] * 0.45f).coerceIn(0.05f, 0.55f)
        )
        val specular = Color.hsv(
            hsv[0],
            (hsv[1] * 0.4f).coerceIn(0.05f, 0.45f),
            (hsv[2] * 1.4f).coerceIn(0.55f, 1f)
        )

        val vizColors = generateAnalogousColors(
            Color(palette.getVibrantColor(baseColor.toArgb())), 5
        )

        return CubePalette(sidePrimary, sideShadow, specular, vizColors)
    }

    /**
     * A tight analogous family around the seed hue.
     *
     * [generateHarmonicColors] spaces hues at 360/count, which is a rainbow — the album
     * only contributed the starting hue. This keeps every colour recognisably from the
     * same artwork by staying within +/-30 degrees and varying brightness instead.
     */
    fun generateAnalogousColors(seed: Color, count: Int): List<Color> {
        if (count <= 0) return emptyList()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(seed.toArgb(), hsv)

        val spread = 30f
        return List(count) { i ->
            val t = if (count == 1) 0f else i / (count - 1f)
            val hue = (hsv[0] + (t - 0.5f) * 2f * spread + 360f) % 360f
            val sat = (hsv[1] * (0.75f + 0.35f * t)).coerceIn(0.35f, 0.95f)
            val value = (hsv[2] * (0.80f + 0.45f * (1f - t))).coerceIn(0.55f, 1f)
            Color.hsv(hue, sat, value)
        }
    }

    /**
     * Generates a harmonic palette of N colors based on a seed color.
     * Uses HSL shifts to find pleasing compliments.
     */
    fun generateHarmonicColors(seed: Color, count: Int): List<Color> {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(seed.toArgb(), hsv)

        val colors = mutableListOf<Color>()
        val hueStep = 360f / count

        for (i in 0 until count) {
            val newHue = (hsv[0] + (i * hueStep)) % 360f
            colors.add(Color.hsv(newHue, hsv[1].coerceIn(0.4f, 0.8f), hsv[2].coerceIn(0.6f, 1f)))
        }

        return colors
    }

    val defaultCubePalette = CubePalette(
        sideFacePrimary = Color(0xFF8B7355),
        sideFaceShadow = Color(0xFF5C4D3C),
        specularHighlight = Color(0xFFD4C5A9),
        visualizerColors = listOf(
            Color(0xFF6200EE), Color(0xFF03DAC6), Color(0xFFBB86FC),
            Color(0xFF3700B3), Color(0xFF018786)
        )
    )

    private val defaultPalette = listOf(
        Color(0xFF6200EE),
        Color(0xFF3700B3),
        Color(0xFF03DAC6),
        Color(0xFF018786),
        Color(0xFFBB86FC)
    )
}

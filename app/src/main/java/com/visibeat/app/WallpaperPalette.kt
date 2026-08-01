package com.visibeat.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import com.visibeat.musicui.design.PlaybackColors

/**
 * The three colours the UI takes from the wallpaper, read in one pass.
 *
 * Every value here used to come from its own `remember` block in MainActivity,
 * each of which opened the wallpaper and ran `BitmapFactory.decodeStream` with
 * no `inSampleSize`, during composition, on the main thread — three
 * full-resolution decodes of the same file, two of them followed by a
 * `Palette.generate()`, none of them recycled. Colour extraction does not need
 * pixels the screen will never show: [SAMPLE_EDGE_PX] is plenty for both
 * Palette and the visualiser ramp, and the bitmap is released the moment the
 * colours are out of it.
 */
@Immutable
data class WallpaperPalette(
    val dominant: Color,
    val accent: Color,
    val visualizerColors: List<Color>
) {
    companion object {
        /** Shown before the first decode finishes, and whenever there is no wallpaper. */
        val Default = WallpaperPalette(
            dominant = Color.White,
            accent = Color(0xFF1D4ED8),
            visualizerColors = listOf(Color.Cyan, Color.Blue)
        )

        /**
         * Longest edge to decode down to. Palette buckets colours, so more
         * resolution buys nothing; it only costs memory and time.
         */
        private const val SAMPLE_EDGE_PX = 256

        /** Blocking. Call from a background dispatcher. */
        fun from(resolver: ContentResolver, uri: Uri?): WallpaperPalette {
            if (uri == null) return Default
            val bitmap = decodeDownsampled(resolver, uri) ?: return Default
            return try {
                val palette = Palette.from(bitmap).generate()
                WallpaperPalette(
                    dominant = Color(palette.getDominantColor(Default.dominant.value.toInt())),
                    accent = Color(
                        palette.getVibrantColor(
                            palette.getDominantColor(Default.accent.value.toInt())
                        )
                    ),
                    visualizerColors = PlaybackColors.generateVisualizerPalette(bitmap)
                        .ifEmpty { Default.visualizerColors }
                )
            } catch (_: Exception) {
                Default
            } finally {
                bitmap.recycle()
            }
        }

        private fun decodeDownsampled(resolver: ContentResolver, uri: Uri): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                }
                resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }

        private fun sampleSizeFor(width: Int, height: Int): Int {
            var sample = 1
            var w = width
            var h = height
            while (w / 2 >= SAMPLE_EDGE_PX && h / 2 >= SAMPLE_EDGE_PX) {
                w /= 2
                h /= 2
                sample *= 2
            }
            return sample
        }
    }
}

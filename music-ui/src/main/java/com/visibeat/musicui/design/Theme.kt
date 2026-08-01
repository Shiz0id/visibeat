package com.visibeat.musicui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.visibeat.musicui.R

/**
 * Nunito — warm humanist typeface (Segoe UI feel for Longhorn/Aero aesthetic)
 */
val NunitoFamily = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)

/**
 * Wallpaper-reactive palette — provided from MainActivity wallpaper extraction
 */
val LocalWallpaperDominant = staticCompositionLocalOf { Color.White }
val LocalWallpaperAccent = staticCompositionLocalOf { Color(0xFF1D4ED8) }

/**
 * Antigravity Atmospheric Palette
 * "Cool-neutral base, gentle accents"
 */
object AgPalette {
    // Base planes
    val DeepSpace = Color(0xFF0A0C0E) // background plane
    val Graphite = Color(0xFF1C1F22)  // feed plane

    // Glass accents (6-14% alpha)
    val GlassWhite = Color(0xFFFFFFFF).copy(alpha = 0.08f)
    val GlassEdge = Color(0xFFFFFFFF).copy(alpha = 0.04f)

    // Low-saturation Accents
    val MistBlue = Color(0xFF7B96B2)
    val SoftViolet = Color(0xFF8E8DBE)
    val NeutralGrey = Color(0xFF5C6268)
    val VividBlue = Color(0xFF1D4ED8)

    // Semantic — three real steps of emphasis. These were all pure white, which
    // flattened every card into a single wall of text with no reading order.
    val TextPrimary = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.62f)
    val TextMetadata = Color.White.copy(alpha = 0.42f)
}

private val AgTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp,
        color = AgPalette.TextPrimary
    ),
    displayMedium = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        color = AgPalette.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        color = AgPalette.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        color = AgPalette.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = AgPalette.TextPrimary
    ),
    bodySmall = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = AgPalette.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = AgPalette.TextMetadata
    )
)

@Composable
fun AntigravityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = AgPalette.MistBlue,
        secondary = AgPalette.SoftViolet,
        background = AgPalette.DeepSpace,
        surface = AgPalette.Graphite,
        onBackground = AgPalette.TextPrimary,
        onSurface = AgPalette.TextPrimary,
        onSurfaceVariant = AgPalette.TextSecondary
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AgTypography,
        content = content
    )
}

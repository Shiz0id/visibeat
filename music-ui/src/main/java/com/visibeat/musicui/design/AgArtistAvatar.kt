package com.visibeat.musicui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * An artist's face, with somewhere to fall back to.
 *
 * Three tiers, because no single source covers a real library:
 *
 *  1. a looked-up portrait, which only exists for artists Wikidata knows
 *  2. the artist's own album art, which exists for nearly everyone
 *  3. their initials, which always exist
 *
 * The tiers also cover each other at runtime — if a portrait URL fails to load
 * (Commons file deleted, no network, cache miss offline) the composable drops to
 * the next tier rather than leaving a hole, which matters because tier one
 * points at the open internet.
 */
@Composable
fun AgArtistAvatar(
    artistName: String,
    modifier: Modifier = Modifier,
    /** Portrait URL, or null. */
    imageModel: Any? = null,
    /** Album art to use when there is no portrait. */
    fallbackModel: Any? = null,
    size: Dp = 52.dp,
    initialsCount: Int = 1
) {
    // Load failures are sticky for this composable's lifetime; retrying a broken
    // URL on every recomposition would just re-fail.
    var portraitFailed by remember(imageModel) { mutableStateOf(false) }
    var fallbackFailed by remember(fallbackModel) { mutableStateOf(false) }

    val portrait = imageModel?.takeIf { !portraitFailed }
    val fallback = fallbackModel?.takeIf { !fallbackFailed }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .agGlass(shape = RoundedCornerShape(50), opacity = 0.14f),
        contentAlignment = Alignment.Center
    ) {
        when {
            portrait != null -> AsyncImage(
                model = portrait,
                contentDescription = artistName,
                contentScale = ContentScale.Crop,
                onError = { portraitFailed = true },
                modifier = Modifier.size(size).clip(CircleShape)
            )

            fallback != null -> AsyncImage(
                model = fallback,
                contentDescription = artistName,
                contentScale = ContentScale.Crop,
                onError = { fallbackFailed = true },
                modifier = Modifier.size(size).clip(CircleShape)
            )

            else -> Text(
                text = artistName.initials(initialsCount),
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.Bold,
                // Scaled off the avatar so one composable serves the 32dp rows
                // and the 100dp home circles without a size parameter each.
                fontSize = (size.value * 0.38f).sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Initials for a fallback avatar.
 *
 * Takes the first letter of separate words where it can — "Zac Brown Band"
 * reads better as "ZB" than "ZA" — and falls back to leading characters for
 * single-word names.
 */
internal fun String.initials(count: Int): String {
    val words = trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    return if (words.size >= count) {
        words.take(count).joinToString("") { it.first().uppercase() }
    } else {
        words.first().take(count).uppercase()
    }
}

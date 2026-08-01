package com.visibeat.musicui.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Cards are views into the object graph, not content blobs."
 */
@Composable
fun AgCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingTitleContent: @Composable (() -> Unit)? = null,
    /**
     * A control pinned to the end of the title row — in practice the card's play
     * button. Kept out of [trailingTitleContent] so it stays clickable in its
     * own right rather than being swallowed by the card's tap target.
     */
    titleAction: @Composable (() -> Unit)? = null,
    previewContent: @Composable (BoxScope.() -> Unit)? = null,
    metadataContent: @Composable (ColumnScope.() -> Unit)? = null
) {
    AgSurface(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading content (icon/art) - moved before title
                if (trailingTitleContent != null) {
                    Box {
                        trailingTitleContent()
                    }
                }

                // Title and subtitle grouped together. Only this region opens the
                // card, so the play button beside it can take its own taps.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .agPressable(onClick = onClick, onLongClick = onLongClick, pressScale = 0.99f)
                ) {
                    AutoSizeTitle(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AgPalette.TextSecondary,
                        // One line. In a narrow timeline card "Miles Caton ·
                        // 21 tracks" wrapped to two, spending a track row on
                        // saying the same thing.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                titleAction?.invoke()
            }

            if (previewContent != null) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().heightIn(max = 120.dp), content = previewContent)
            }

            if (metadataContent != null) {
                Spacer(Modifier.height(10.dp))
                metadataContent()
            }
        }
    }
}

/**
 * Auto-sizing text that shrinks ONLY single-word titles to fit.
 * Multi-word titles wrap normally to multiple lines.
 */
@Composable
private fun AutoSizeTitle(
    text: String,
    style: TextStyle,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    minFontSize: TextUnit = 10.sp
) {
    val isSingleWord = !text.trim().contains(' ')

    if (isSingleWord) {
        // Single word - use auto-sizing
        var fontSize by remember(text) { mutableStateOf(style.fontSize) }

        Text(
            text = text,
            style = style.copy(fontSize = fontSize, fontWeight = fontWeight),
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = modifier,
            onTextLayout = { result ->
                if (result.hasVisualOverflow && fontSize > minFontSize) {
                    fontSize = (fontSize.value - 1f).sp
                }
            }
        )
    } else {
        // Multi-word — wraps, but only so far. Unbounded, a long album title in a
        // narrow timeline card ran to three lines and then got cut mid-word by
        // the fixed-height pager around it, which reads as a rendering fault
        // rather than as a long title.
        Text(
            text = text,
            style = style.copy(fontWeight = fontWeight),
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    }
}

package com.visibeat.musicui.playlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.visibeat.musicui.design.*

/**
 * Glass dialog for naming a playlist — used for both create and rename, since
 * the two differ only in their title and starting text.
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    val accent = LocalWallpaperAccent.current
    val canConfirm = name.isNotBlank()

    // Opening a naming dialog and then having to tap the field is a wasted step.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        AgAcrylicSurface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = AgPalette.TextPrimary
                )

                Spacer(Modifier.height(16.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .agGlass(RoundedCornerShape(12.dp), opacity = 0.08f)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = NunitoFamily,
                            fontSize = 16.sp,
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (canConfirm) onConfirm(name.trim())
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            Box {
                                if (name.isEmpty()) {
                                    Text(
                                        "Playlist name",
                                        fontFamily = NunitoFamily,
                                        fontSize = 16.sp,
                                        color = AgPalette.TextMetadata
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DialogButton(
                        label = "Cancel",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    DialogButton(
                        label = confirmLabel,
                        onClick = { if (canConfirm) onConfirm(name.trim()) },
                        emphasised = true,
                        enabled = canConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Confirmation for a destructive action. Deleting a playlist cannot be undone —
 * there is no trash — so it asks first.
 */
@Composable
fun PlaylistDeleteDialog(
    playlistName: String,
    trackCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        AgAcrylicSurface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "Delete \"$playlistName\"?",
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = AgPalette.TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (trackCount > 0) {
                        "The playlist and its $trackCount ${if (trackCount == 1) "track" else "tracks"} " +
                            "will be removed. Your music files are not touched."
                    } else {
                        "Your music files are not touched."
                    },
                    fontFamily = NunitoFamily,
                    fontSize = 14.sp,
                    color = AgPalette.TextSecondary
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DialogButton(
                        label = "Keep",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    DialogButton(
                        label = "Delete",
                        onClick = onConfirm,
                        destructive = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val accent = LocalWallpaperAccent.current
    val shape = RoundedCornerShape(12.dp)
    val tint = when {
        destructive -> Color(0xFFEF4444)
        emphasised -> accent
        else -> null
    }

    Box(
        modifier = modifier
            .then(
                if (tint != null && enabled) {
                    Modifier.agGlassTinted(shape, tint = tint, opacity = 0.28f)
                } else {
                    Modifier.agGlass(shape, opacity = 0.06f)
                }
            )
            .agPressable(onClick = onClick, enabled = enabled, pressScale = 0.96f)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = if (enabled) AgPalette.TextPrimary else AgPalette.TextMetadata
        )
    }
}

package com.visibeat.musicui.track

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.visibeat.coredb.MetadataField
import com.visibeat.musicui.design.*
import kotlinx.coroutines.launch

/**
 * The metadata editor.
 *
 * This sheet was fully built in the alpha and completely unreachable — nothing
 * in the app ever set the state that shows it. It is now what a long-press on
 * any track opens, so it has a header telling you which track you are editing
 * and a way to queue it without leaving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailBottomSheet(
    trackId: Long,
    repo: TrackEditRepository,
    onDismiss: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    /**
     * Jump to the timeline day this track was released.
     *
     * Used to be a full-width button on the Now Playing panel. It sits here
     * instead, beside the release date it acts on — and it now works from any
     * track, not only the one that happens to be playing.
     */
    onViewReleaseDay: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = LocalWallpaperAccent.current

    var refreshTrigger by remember { mutableIntStateOf(0) }
    val modelState = produceState<TrackDetailModel?>(initialValue = null, trackId, refreshTrigger) {
        value = repo.loadTrackDetail(trackId)
    }

    val model = modelState.value

    val glassFieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
        focusedBorderColor = accent,
        unfocusedLabelColor = AgPalette.TextSecondary,
        focusedLabelColor = accent,
        cursorColor = accent,
        unfocusedTextColor = AgPalette.TextPrimary,
        focusedTextColor = AgPalette.TextPrimary
    )

    if (model == null) {
        AgModalSheet(onDismissRequest = onDismiss, sheetState = state) {
            Box(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Loading…", fontFamily = NunitoFamily, color = AgPalette.TextSecondary)
            }
        }
        return
    }

    var title by remember(model.trackId) { mutableStateOf(model.title ?: "") }
    var artist by remember(model.trackId) { mutableStateOf(model.artist ?: "") }
    var album by remember(model.trackId) { mutableStateOf(model.album ?: "") }
    var releaseDate by remember(model.trackId) { mutableStateOf(model.releaseDateRaw ?: "") }
    var genre by remember(model.trackId) { mutableStateOf(model.genre ?: "") }

    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Only offer to save when something actually differs from what is stored.
    val hasChanges = title.trim() != (model.title ?: "").trim() ||
        artist.trim() != (model.artist ?: "").trim() ||
        album.trim() != (model.album ?: "").trim() ||
        releaseDate.trim() != (model.releaseDateRaw ?: "").trim() ||
        genre.trim() != (model.genre ?: "").trim()

    AgModalSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            // ── Header: which track is this? ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = model.title ?: "Untitled track",
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = AgPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(model.artist, model.album)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .ifEmpty { "Edit this track's metadata" },
                        fontFamily = NunitoFamily,
                        fontSize = 13.sp,
                        color = AgPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onAddToPlaylist != null) {
                    AgIconButton(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Add to playlist",
                        onClick = onAddToPlaylist,
                        size = 36.dp,
                        iconSize = 20.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (onAddToQueue != null) {
                    AgIconButton(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Add to queue",
                        onClick = onAddToQueue,
                        size = 36.dp,
                        iconSize = 20.dp
                    )
                }
            }

            if (onViewReleaseDay != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .agGlass(RoundedCornerShape(12.dp), opacity = 0.10f)
                        .agPressable(onClick = onViewReleaseDay, pressScale = 0.98f)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "View release day",
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            ReadonlyRow(label = "Resolved", value = model.resolvedSummary)

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = title, onValueChange = { title = it; saved = false },
                label = { Text("Title") }, singleLine = true,
                colors = glassFieldColors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = artist, onValueChange = { artist = it; saved = false },
                label = { Text("Artist") }, singleLine = true,
                colors = glassFieldColors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = album, onValueChange = { album = it; saved = false },
                label = { Text("Album") }, singleLine = true,
                colors = glassFieldColors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = releaseDate, onValueChange = { releaseDate = it; saved = false },
                label = { Text("Release date") },
                supportingText = {
                    Text(
                        "1999, 1999-06, or 1999-06-01 — this is what places the track on the timeline.",
                        fontFamily = NunitoFamily,
                        fontSize = 11.sp,
                        color = AgPalette.TextMetadata
                    )
                },
                singleLine = true, colors = glassFieldColors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = genre, onValueChange = { genre = it; saved = false },
                label = { Text("Genre") }, singleLine = true,
                colors = glassFieldColors, modifier = Modifier.fillMaxWidth()
            )

            if (model.musicBrainzId != null || model.musicBrainzDate != null) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(12.dp))
                Text(
                    "MusicBrainz Enrichment",
                    fontFamily = NunitoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = accent
                )

                if (model.musicBrainzDate != null) {
                    ReadonlyRow(label = "MusicBrainz Date", value = model.musicBrainzDate)
                }
                if (model.musicBrainzId != null) {
                    ReadonlyRow(label = "Enriched ID", value = model.musicBrainzId)
                }

                if (model.resolvedSummary.contains("MUSICBRAINZ", ignoreCase = true)) {
                    Text(
                        "Currently using MusicBrainz date",
                        fontFamily = NunitoFamily,
                        fontSize = 12.sp,
                        color = AgPalette.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, fontFamily = NunitoFamily, color = Color(0xFFEF4444))
            }

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .agGlass(RoundedCornerShape(12.dp), opacity = 0.06f)
                        .agPressable(onClick = onDismiss, pressScale = 0.97f)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Close", fontFamily = NunitoFamily, color = AgPalette.TextSecondary)
                }

                // Save button — glass styled, and inert until there is something
                // to save so a stray tap can't rewrite a track with its own values.
                val canSave = hasChanges && !saving
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (canSave) {
                                Modifier.agGlassTinted(RoundedCornerShape(12.dp), tint = accent, opacity = 0.28f)
                            } else {
                                Modifier.agGlass(RoundedCornerShape(12.dp), opacity = 0.06f)
                            }
                        )
                        .agPressable(
                            enabled = canSave,
                            onClick = {
                                saving = true
                                error = null
                                scope.launch {
                                    val edits = buildList {
                                        if (title.trim().isNotEmpty() && title.trim() != (model.title ?: "")) add(MetadataEdit(MetadataField.TRACK_TITLE, title.trim()))
                                        if (artist.trim().isNotEmpty() && artist.trim() != (model.artist ?: "")) add(MetadataEdit(MetadataField.TRACK_ARTIST, artist.trim()))
                                        if (album.trim().isNotEmpty() && album.trim() != (model.album ?: "")) add(MetadataEdit(MetadataField.RELEASE_TITLE, album.trim()))
                                        if (releaseDate.trim().isNotEmpty() && releaseDate.trim() != (model.releaseDateRaw ?: "")) add(MetadataEdit(MetadataField.RELEASE_DATE, releaseDate.trim()))
                                        if (genre.trim().isNotEmpty() && genre.trim() != (model.genre ?: "")) add(MetadataEdit(MetadataField.GENRE, genre.trim()))
                                    }
                                    val result = repo.applyUserEdits(trackId, edits)
                                    saving = false
                                    result.exceptionOrNull()?.let { ex -> error = ex.message ?: "Save failed" }
                                    if (result.isSuccess) {
                                        saved = true
                                        refreshTrigger++
                                    }
                                }
                            },
                            pressScale = 0.97f
                        )
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            saving -> "Saving…"
                            saved && !hasChanges -> "Saved"
                            else -> "Save"
                        },
                        fontFamily = NunitoFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canSave) AgPalette.TextPrimary else AgPalette.TextMetadata
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ReadonlyRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontFamily = NunitoFamily, fontSize = 12.sp, color = AgPalette.TextMetadata)
        Text(value, fontFamily = NunitoFamily, fontSize = 14.sp, color = AgPalette.TextPrimary)
    }
}

package com.visibeat.musicui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Water
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.VisualizerDiagnostics
import com.visibeat.viewengine.LibraryFeeds
import com.visibeat.viewengine.ArtistImageDao
import com.visibeat.viewengine.LibraryDao
import com.visibeat.viewengine.PlayHistoryDao

/**
 * Settings.
 *
 * Everything here used to live in a slide-out drawer reachable from a hamburger
 * on every screen — a maintenance menu permanently occupying the corner of the
 * UI, with destructive actions ("Regenerate DB") sitting one tap away from
 * "Select Wallpaper" and no indication which was which. As a real screen the
 * actions can be grouped, explained, and the dangerous one can ask first.
 */
@Composable
fun SettingsScreen(
    feeds: LibraryFeeds,
    diagnostics: VisualizerDiagnostics,
    isIngesting: Boolean,
    hasWallpaper: Boolean,
    reflectionEnabled: Boolean,
    versionLabel: String,
    /**
     * What the radio can currently see, gathered by the host.
     *
     * A snapshot rather than a Flow: it needs a 21 MB model's load state and a
     * count from a table the indexer writes in bursts of hundreds, and observing
     * either would mean re-reading them on every write. Refreshed when the
     * screen opens and when asked.
     */
    enrichmentStatus: EnrichmentStatus,
    radioStatus: RadioStatus,
    onRefreshRadioStatus: () -> Unit,
    onIndexLibrary: () -> Unit,
    onForceIndexLibrary: () -> Unit,
    onRetryUnreadable: () -> Unit,
    onStopAnalysis: () -> Unit,
    onClearAnalysis: () -> Unit,
    onBack: () -> Unit,
    onIngestMediaStore: () -> Unit,
    onIngestFolder: () -> Unit,
    onEnrichDates: () -> Unit,
    artistImageDao: ArtistImageDao,
    onFetchArtistImages: () -> Unit,
    onFetchArtistBios: () -> Unit,
    artistCleanupStatus: String?,
    onTidyArtists: () -> Unit,
    trackCleanupStatus: String?,
    onMergeDuplicateTracks: () -> Unit,
    onClearArtistImages: () -> Unit,
    onSelectWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    onToggleReflection: (Boolean) -> Unit,
    onResetSlabPosition: () -> Unit,
    onClearPlayHistory: () -> Unit,
    onRebuildDatabase: () -> Unit
) {
    val trackCount by feeds.trackCount.collectAsState()
    val albumCount by feeds.albumCount.collectAsState()
    val artistCount by feeds.artistCount.collectAsState()
    val playedCount by feeds.playedTrackCount.collectAsState()
    val portraitCount by artistImageDao.observeResolvedCount().collectAsState(initial = 0)
    val bioCount by artistImageDao.observeBioCount().collectAsState(initial = 0)
    val portraitAttempted by artistImageDao.observeAttemptedCount().collectAsState(initial = 0)

    var confirmRebuild by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearPortraits by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AgIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = 36.dp
            )
            Text(
                text = "Settings",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                color = AgPalette.TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp)) {

            // ── Library ───────────────────────────────────
            item { AgSectionHeader(title = "Library") }
            item {
                LibraryStats(
                    trackCount = trackCount,
                    albumCount = albumCount,
                    artistCount = artistCount
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.MusicNote,
                    title = "Scan device music",
                    subtitle = "Import everything MediaStore knows about",
                    enabled = !isIngesting,
                    onClick = onIngestMediaStore,
                    trailing = if (isIngesting) ({ IngestSpinner() }) else null
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Folder,
                    title = "Add a folder",
                    subtitle = "Pick a folder to scan, for music MediaStore misses",
                    enabled = !isIngesting,
                    onClick = onIngestFolder,
                    trailing = if (isIngesting) ({ IngestSpinner() }) else null
                )
            }

            // ── Metadata ──────────────────────────────────
            item { AgSectionHeader(title = "Metadata") }
            item {
                AgSettingRow(
                    icon = Icons.Default.CloudDownload,
                    title = "Enrich release dates",
                    subtitle = "Look up missing dates on MusicBrainz. Runs daily on its own; " +
                        "this starts a pass now.",
                    onClick = onEnrichDates
                )
            }

            item {
                AgSettingRow(
                    icon = Icons.Default.Face,
                    title = "Artist portraits",
                    subtitle = when {
                        artistCount == 0 -> "Scan some music first"
                        portraitAttempted == 0 ->
                            "Look up artist photos from Wikidata. Album art stands in " +
                                "for anyone without one."
                        else ->
                            "$portraitCount of $portraitAttempted artists checked have a photo. " +
                                "The rest fall back to album art."
                    },
                    enabled = artistCount > 0,
                    onClick = onFetchArtistImages,
                    trailing = if (portraitAttempted > 0) ({
                        Text(
                            text = "Reset",
                            fontFamily = NunitoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = AgPalette.TextSecondary,
                            modifier = Modifier
                                .agPressable(
                                    onClick = { confirmClearPortraits = true },
                                    pressScale = 0.94f
                                )
                                .padding(8.dp)
                        )
                    }) else null
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Person,
                    title = "Artist info",
                    subtitle = when {
                        artistCount == 0 -> "Scan some music first"
                        bioCount == 0 ->
                            "Fetch the Wikipedia blurb shown on every artist page. " +
                                "Without it each page fills in a second after it opens."
                        bioCount >= artistCount -> "$bioCount of $artistCount artists have a bio."
                        else -> "$bioCount of $artistCount artists have a bio. Tap to fetch more."
                    },
                    enabled = artistCount > 0,
                    onClick = onFetchArtistBios
                )
            }

            item {
                AgSettingRow(
                    icon = Icons.Default.CleaningServices,
                    title = "Tidy up artists",
                    subtitle = artistCleanupStatus
                        ?: "Merge duplicate artists and split collaboration credits " +
                            "like \"A feat. B\" into real artists. Reads what is already " +
                            "stored — no rescan.",
                    enabled = artistCount > 0,
                    onClick = onTidyArtists
                )
            }

            item {
                AgSettingRow(
                    icon = Icons.Default.ContentCopy,
                    title = "Merge duplicate tracks",
                    subtitle = trackCleanupStatus
                        ?: "Finds files ingested twice — the same track picked up by " +
                            "both a folder scan and a media scan — and folds them into " +
                            "one. Play counts are added together, likes and playlists " +
                            "are kept.",
                    enabled = trackCount > 0,
                    onClick = onMergeDuplicateTracks
                )
            }

            // ── Appearance ────────────────────────────────
            item { AgSectionHeader(title = "Appearance") }
            item {
                AgSettingRow(
                    icon = Icons.Default.Image,
                    title = if (hasWallpaper) "Change wallpaper" else "Choose a wallpaper",
                    subtitle = "Every glass surface takes its colour from this image",
                    onClick = onSelectWallpaper
                )
            }
            if (hasWallpaper) {
                item {
                    AgSettingRow(
                        icon = Icons.Default.Water,
                        title = "Remove wallpaper",
                        subtitle = "Fall back to the built-in gradient",
                        onClick = onClearWallpaper
                    )
                }
            }
            item {
                AgToggleRow(
                    icon = Icons.Default.Album,
                    title = "Cube reflection",
                    subtitle = "Mirror the now-playing cube on its surface",
                    checked = reflectionEnabled,
                    onCheckedChange = onToggleReflection
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.MyLocation,
                    title = "Recentre the cube",
                    subtitle = "Move the floating now-playing cube back to the bottom centre",
                    onClick = onResetSlabPosition
                )
            }

            // ── Listening ─────────────────────────────────
            item { AgSectionHeader(title = "Listening") }
            item {
                AgSettingRow(
                    icon = Icons.Default.History,
                    title = "Play history",
                    subtitle = if (playedCount == 0) {
                        "Nothing played yet — this is what fills Recent activity"
                    } else {
                        "$playedCount ${if (playedCount == 1) "track" else "tracks"} played"
                    },
                    onClick = if (playedCount > 0) ({ confirmClearHistory = true }) else null,
                    trailing = if (playedCount > 0) ({
                        Text(
                            text = "Clear",
                            fontFamily = NunitoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = AgPalette.TextSecondary
                        )
                    }) else null
                )
            }

            // ── Diagnostics ───────────────────────────────
            item {
                AgSectionHeader(
                    title = "Diagnostics",
                    subtitle = "For working out why the cube is not moving"
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Search,
                    title = "Visualiser",
                    subtitle = diagnostics.summary
                )
            }
            if (diagnostics.attempts.isNotEmpty()) {
                item {
                    Text(
                        text = diagnostics.attempts.joinToString("\n"),
                        fontFamily = NunitoFamily,
                        fontSize = 11.sp,
                        color = AgPalette.TextMetadata,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)
                    )
                }
            }
            item { AgSectionHeader(title = "MusicBrainz") }
            item {
                AgSettingRow(
                    icon = Icons.Default.Storage,
                    title = "Matched releases",
                    subtitle = enrichmentStatus.releasesLine,
                    onClick = onEnrichDates
                )
            }
            if (enrichmentStatus.genresLine.isNotEmpty()) {
                item {
                    AgSettingRow(
                        icon = Icons.Default.Storage,
                        title = "Genres",
                        subtitle = enrichmentStatus.genresLine,
                        onClick = onEnrichDates
                    )
                }
            }
            if (enrichmentStatus.detail.isNotEmpty()) {
                item {
                    Text(
                        text = enrichmentStatus.detail.joinToString("\n"),
                        fontFamily = NunitoFamily,
                        fontSize = 11.sp,
                        color = AgPalette.TextMetadata,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)
                    )
                }
            }

            // -- Radio ------------------------------------
            item {
                AgSectionHeader(title = "Radio")
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Radio,
                    title = "Model",
                    subtitle = radioStatus.modelLine,
                    onClick = onRefreshRadioStatus
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Storage,
                    title = "Analysed library",
                    subtitle = radioStatus.indexLine,
                    onClick = onIndexLibrary
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Radio,
                    title = "Analyse now, on battery",
                    subtitle = "Ignores the charging requirement. Expect heat and battery drain.",
                    onClick = onForceIndexLibrary
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Radio,
                    title = "Retry unreadable",
                    subtitle = "Tries the skipped tracks again. Keeps everything else.",
                    onClick = onRetryUnreadable
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Radio,
                    title = "Stop analysis",
                    subtitle = "Ends the current run. Keeps what has been done.",
                    onClick = onStopAnalysis
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.Radio,
                    title = "Clear analysis",
                    subtitle = "Discards every vector so the next run starts over.",
                    onClick = onClearAnalysis
                )
            }
            if (radioStatus.detail.isNotEmpty()) {
                item {
                    Text(
                        text = radioStatus.detail.joinToString("\n"),
                        fontFamily = NunitoFamily,
                        fontSize = 11.sp,
                        color = AgPalette.TextMetadata,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)
                    )
                }
            }

            item {
                AgSettingRow(
                    icon = Icons.Default.Storage,
                    title = "Version",
                    subtitle = versionLabel
                )
            }

            // ── Destructive ───────────────────────────────
            item {
                AgSectionHeader(
                    title = "Danger zone",
                    subtitle = "These cannot be undone"
                )
            }
            item {
                AgSettingRow(
                    icon = Icons.Default.DeleteForever,
                    title = "Rebuild database",
                    subtitle = "Erases every scanned track, playlist and play count, " +
                        "then starts empty. Your music files are untouched.",
                    destructive = true,
                    onClick = { confirmRebuild = true }
                )
            }
        }
    }

    if (confirmRebuild) {
        AgConfirmDialog(
            title = "Rebuild the database?",
            message = "Every scanned track, every playlist you have made and all play " +
                "history will be deleted, and you will need to scan again. " +
                "Your music files are not touched.",
            confirmLabel = "Erase",
            dismissLabel = "Keep",
            destructive = true,
            onDismiss = { confirmRebuild = false },
            onConfirm = {
                confirmRebuild = false
                onRebuildDatabase()
            }
        )
    }

    if (confirmClearPortraits) {
        AgConfirmDialog(
            title = "Look for artist photos again?",
            message = "Clears what has been found so far, including the record of which " +
                "artists have no photo, and starts the search over. Nothing else is affected.",
            confirmLabel = "Reset",
            dismissLabel = "Cancel",
            onDismiss = { confirmClearPortraits = false },
            onConfirm = {
                confirmClearPortraits = false
                onClearArtistImages()
            }
        )
    }

    if (confirmClearHistory) {
        AgConfirmDialog(
            title = "Clear play history?",
            message = "Recent activity will be empty until you play something again. " +
                "Playlists and scanned music are not affected.",
            confirmLabel = "Clear",
            dismissLabel = "Keep",
            destructive = true,
            onDismiss = { confirmClearHistory = false },
            onConfirm = {
                confirmClearHistory = false
                onClearPlayHistory()
            }
        )
    }
}

@Composable
private fun IngestSpinner() {
    CircularProgressIndicator(
        color = LocalWallpaperAccent.current,
        strokeWidth = 2.dp,
        modifier = Modifier.size(18.dp)
    )
}

/** What is actually in the library, so the scan buttons below have context. */
@Composable
private fun LibraryStats(trackCount: Int, albumCount: Int, artistCount: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .agGlass(RoundedCornerShape(14.dp), opacity = 0.05f)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Stat(Icons.Default.MusicNote, trackCount, if (trackCount == 1) "track" else "tracks")
        Stat(Icons.Default.Album, albumCount, if (albumCount == 1) "album" else "albums")
        Stat(Icons.Default.Groups, artistCount, if (artistCount == 1) "artist" else "artists")
    }
}

@Composable
private fun Stat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Int,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LocalWallpaperAccent.current,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "$value",
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 20.sp,
            color = AgPalette.TextPrimary
        )
        Text(
            text = label,
            fontFamily = NunitoFamily,
            fontSize = 11.sp,
            color = AgPalette.TextMetadata
        )
    }
}

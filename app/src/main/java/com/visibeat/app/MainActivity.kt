package com.visibeat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.visibeat.ingest.*
import com.visibeat.musicdb.LibraryRootEntity
import com.visibeat.musicui.album.AlbumScreen
import com.visibeat.musicui.artist.ArtistScreen
import com.visibeat.musicui.design.*
import com.visibeat.musicui.feed.BucketFeedScreen
import com.visibeat.musicui.home.HomeScreen
import com.visibeat.musicui.library.LibraryAlbumsScreen
import com.visibeat.musicui.library.LibraryArtistsScreen
import com.visibeat.musicui.library.LibraryScreen
import com.visibeat.musicui.library.LibrarySection
import com.visibeat.musicui.library.LibraryTracksScreen
import com.visibeat.musicui.library.LikedAlbumsScreen
import com.visibeat.musicui.library.LikedArtistsScreen
import com.visibeat.musicui.library.LikedSongsScreen
import com.visibeat.musicui.playlist.AddToPlaylistSheet
import com.visibeat.musicui.playlist.PlaylistDetailScreen
import com.visibeat.musicui.playlist.PlaylistsScreen
import com.visibeat.musicui.search.SearchScreen
import com.visibeat.musicui.settings.EnrichmentStatus
import com.visibeat.musicui.settings.RadioStatus
import com.visibeat.musicui.settings.SettingsScreen
import com.visibeat.musicui.timeline.TimelineBucketsScreen
import com.visibeat.musicui.track.TrackDetailBottomSheet
import com.visibeat.radio.RadioConfig
import com.visibeat.radio.RadioIndexing
import com.visibeat.radio.RadioOrigin
import com.visibeat.radio.RadioQueueManager
import com.visibeat.viewengine.*
import com.visibeat.musicbrainz.ArtistImageWorker
import com.visibeat.musicbrainz.ReleaseDateEnrichmentWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.rememberLazyListState
import com.visibeat.musicui.playback.DragToQueueController
import com.visibeat.musicui.playback.LocalDragToQueue
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.musicui.playback.NowPlayingExpanded
import com.visibeat.musicui.playback.NowPlayingSlab
import com.visibeat.musicui.playback.NowPlayingSlabMetrics
import com.visibeat.musicui.playback.PlaybackBinding
import com.visibeat.musicui.playback.PlaybackManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    /**
     * Held as a field so the media controller and its position ticker can be torn
     * down in [onDestroy]. The alpha created it as a local and never released it.
     */
    private lateinit var playbackManager: PlaybackManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Owned by the Application, not by this activity: a rotation must not
        // build a second Room instance against the same file. See AppGraph.
        val graph = appGraph
        val db = graph.db
        val timelineEngine = graph.timelineEngine
        val feedEngine = graph.feedEngine
        val timelineFeed = graph.timelineFeed
        val albumDao = graph.albumDao
        val likesDao = graph.likesDao
        val artistPageDao = graph.artistPageDao
        val libraryDao = graph.libraryDao
        val playlistDao = graph.playlistDao
        val playHistoryDao = graph.playHistoryDao
        val artistImageDao = graph.artistImageDao
        val feeds = graph.feeds
        val artistMaintenance = graph.artistMaintenance
        val trackMaintenance = graph.trackMaintenance
        val mediaStoreScanner = graph.mediaStoreScanner
        val safScanner = graph.safScanner
        val trackRepo = graph.trackRepo

        val versionLabel = buildString {
            append("VisiBeat ")
            append(
                runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "unknown"
            )
            append(" · database schema v")
            append(MUSIC_DB_VERSION)
        }

        val prefs = getSharedPreferences("visibeat_prefs", MODE_PRIVATE)
        val initialWallpaperUri = prefs.getString("wallpaper_uri", null)?.let { Uri.parse(it) }

        playbackManager = PlaybackManager(this)
        // Nothing recorded playback before, so every "recent" shelf in the app was
        // really showing ingest order. This is the only writer of play history.
        playbackManager.onTrackStarted = { trackId ->
            withContext(Dispatchers.IO) { playHistoryDao.recordPlay(trackId) }
        }
        // Rotating the device or returning to a still-playing app builds a fresh
        // manager against a session that already has a queue. This lets it adopt
        // that queue instead of showing an empty player over audible music.
        playbackManager.queueRehydrator = { trackIds ->
            withContext(Dispatchers.IO) {
                trackIds.mapNotNull { db.timelineDao().getTrackById(it) }
            }
        }

        // Bottom nav items
        val navItems = listOf(
            NavItem(NavDestination.HOME, "Home", Icons.Default.Home),
            NavItem(NavDestination.TIMELINE, "Timeline", Icons.Outlined.DateRange),
            NavItem(NavDestination.SEARCH, "Search", Icons.Default.Search),
            NavItem(NavDestination.LIBRARY, "Library", Icons.AutoMirrored.Outlined.List)
        )

        setContent {
            val scope = rememberCoroutineScope()
            var screen by remember { mutableStateOf<Screen>(Screen.Home) }
            var currentNav by remember { mutableStateOf(NavDestination.HOME) }
            var selectedTrackId by remember { mutableStateOf<Long?>(null) }
            // Track ids waiting to be filed into a playlist, or null when the
            // picker is closed.
            var addingToPlaylist by remember { mutableStateOf<List<Long>?>(null) }
            // Result of the last artist tidy-up, shown in Settings.
            var artistCleanupStatus by remember { mutableStateOf<String?>(null) }
            var trackCleanupStatus by remember { mutableStateOf<String?>(null) }
            // Snapshot, not a Flow: gathering it loads a 21 MB model and counts a
            // table the indexer writes in bursts of hundreds. Refreshed when
            // Settings opens and when the user asks.
            var radioStatus by remember { mutableStateOf(RadioStatus()) }
            var enrichmentStatus by remember { mutableStateOf(EnrichmentStatus()) }
            var isIngesting by remember { mutableStateOf(false) }
            var dbGeneration by remember { mutableIntStateOf(0) }
            var wallpaperUri by remember { mutableStateOf<Uri?>(initialWallpaperUri) }
            val timelineListState = rememberLazyListState()

            // Playback state
            val playbackState by playbackManager.state.collectAsState()
            val fftData by playbackManager.fftData.collectAsState()
            var isNowPlayingExpanded by remember { mutableStateOf(false) }
            var reflectionEnabled by remember { mutableStateOf(prefs.getBoolean("slab_reflection_enabled", true)) }

            // Sort choice belongs to the user, not to a screen instance — it has
            // to survive leaving the Playlists screen and restarting the app.
            var playlistSort by remember {
                mutableStateOf(
                    runCatching { PlaylistSort.valueOf(prefs.getString("playlist_sort", null) ?: "") }
                        .getOrDefault(PlaylistSort.RECENTS)
                )
            }
            val playlists by feeds.playlists.collectAsState()
            val likedSongCount by likesDao.observeLikedTrackCount().collectAsState(initial = 0)
            val likedAlbumCount by likesDao.observeLikedReleaseCount().collectAsState(initial = 0)
            val likedArtistCount by likesDao.observeLikedArtistCount().collectAsState(initial = 0)

            // Draggable slab position (persisted)
            val savedSlabX = prefs.getFloat("slab_x", -1f)
            val savedSlabY = prefs.getFloat("slab_y", -1f)
            var slabOffsetX by remember { mutableFloatStateOf(savedSlabX) }
            var slabOffsetY by remember { mutableFloatStateOf(savedSlabY) }
            var slabInitialized by remember { mutableStateOf(savedSlabX >= 0 && savedSlabY >= 0) }

            // Wallpaper palette: one downsampled decode, off the main thread.
            //
            // This was three separate `remember` blocks, each opening the stream
            // and decoding the wallpaper at full resolution *during composition*,
            // two of them then running Palette over it. A 4000x3000 photo is
            // ~48MB a go, so picking a wallpaper meant three of those on the UI
            // thread with nothing recycled. Everything below comes off one
            // 256px bitmap that is released as soon as the colours are read.
            var wallpaperPalette by remember {
                mutableStateOf(WallpaperPalette.Default)
            }
            LaunchedEffect(wallpaperUri) {
                wallpaperPalette = withContext(Dispatchers.IO) {
                    WallpaperPalette.from(contentResolver, wallpaperUri)
                }
            }
            val visualizerColors = wallpaperPalette.visualizerColors
            val wallpaperDominant = wallpaperPalette.dominant
            val wallpaperAccent = wallpaperPalette.accent


            val audioPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    playbackManager.onRecordAudioGranted()
                }
            }

            val mediaPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    scope.launch {
                        isIngesting = true
                        withContext(Dispatchers.IO) {
                            mediaStoreScanner.scan()
                        }
                        isIngesting = false
                        // Newly extracted covers are invisible until the cached
                        // "no art for this release" answers are thrown away.
                        TimelineItemRow.invalidateArtCache()
                        dbGeneration++
                    }
                }
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* Playback works either way; only the notification depends on it. */ }

            /**
             * Asked for once, on the first play, and never blocking.
             *
             * RECORD_AUDIO feeds the visualiser; the alpha re-launched it on every
             * single play, putting a system dialog in front of the music.
             * POST_NOTIFICATIONS was declared in the manifest but never requested,
             * so on Android 13+ the media notification — and with it the
             * lockscreen and headset controls — never appeared.
             */
            var permissionsAsked by remember { mutableStateOf(false) }
            fun ensurePlaybackPermissions() {
                val recordGranted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (recordGranted) playbackManager.onRecordAudioGranted()
                if (permissionsAsked) return
                permissionsAsked = true

                if (!recordGranted) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            fun requestMediaScan() {
                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                mediaPermissionLauncher.launch(perm)
            }

            val safLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                uri?.let {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    scope.launch {
                        isIngesting = true
                        withContext(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            val rootDao = graph.libraryRootDao
                            val inserted = rootDao.insertRoot(
                                LibraryRootEntity(
                                    rootUriString = it.toString(),
                                    displayName = it.lastPathSegment,
                                    createdAt = now,
                                    lastScanAt = now
                                )
                            )
                            // rootUriString is unique and insertRoot IGNOREs the
                            // conflict, so re-picking a folder already in the
                            // library returns -1. Passing that straight through
                            // stamped rootId = -1 — a root that does not exist —
                            // onto every track in the folder.
                            val rootId = if (inserted != -1L) {
                                inserted
                            } else {
                                rootDao.getByUri(it.toString())?.rootId
                                    ?.also { existing -> rootDao.touchScan(existing, now) }
                            }
                            if (rootId != null) safScanner.scan(it, rootId)
                        }
                        isIngesting = false
                        TimelineItemRow.invalidateArtCache()
                        dbGeneration++
                    }
                }
            }

            val wallpaperLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    wallpaperUri = uri
                    prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
                }
            }

            val backStack = remember { mutableStateListOf<Screen>() }
            fun navTo(s: Screen) {
                backStack.add(screen)
                screen = s
            }
            fun goBack() {
                if (backStack.isNotEmpty()) {
                    screen = backStack.removeAt(backStack.size - 1)
                    // Sync nav destination with screen after going back
                    when (screen) {
                        is Screen.Home -> currentNav = NavDestination.HOME
                        is Screen.Timeline -> currentNav = NavDestination.TIMELINE
                        is Screen.Search -> currentNav = NavDestination.SEARCH
                        is Screen.Library -> currentNav = NavDestination.LIBRARY
                        else -> {} // Feed/Artist are sub-screens, keep current nav
                    }
                }
            }

            // An album is not a date bucket. This used to open the timeline
            // feed filtered to one release, which is where "January 1970" came
            // from — the epoch, formatted as a month. The release-filtered feed
            // still exists for the timeline's own view-release-day path.
            fun openAlbum(releaseId: Long) {
                navTo(Screen.Album(releaseId))
            }

            /** Radio's states are all ordinary, so they get a toast, not a dialog. */
            fun notify(message: String) {
                android.widget.Toast.makeText(
                    this@MainActivity, message, android.widget.Toast.LENGTH_LONG
                ).show()
            }

            /**
             * Builds a station seeded on one track and plays it.
             *
             * Seeded on a single track, never an average of an album or an
             * artist: the index holds one vector per track, and averaging a
             * collection blurs exactly the character the station is meant to
             * follow. Callers with a collection pick a representative first.
             *
             * Every failure here is an ordinary state, not an error — no model
             * installed, library not analysed yet, or nothing close enough. Each
             * says which, because "nothing happened" is the worst answer.
             */
            fun startRadio(seedTrackId: Long, origin: RadioOrigin) {
                scope.launch {
                    if (graph.embeddingEngine == null) {
                        notify("Radio model is not installed.")
                        return@launch
                    }

                    val index = withContext(Dispatchers.IO) { graph.embeddingIndex() }
                    if (!index.contains(seedTrackId)) {
                        // The indexer only runs on a charger, so this is simply
                        // what it looks like for a while after installing.
                        RadioIndexing.enqueue(this@MainActivity)
                        notify(
                            if (index.size == 0) {
                                "Analysing your library — plug in and leave it a while."
                            } else {
                                "Not analysed yet. ${index.size} tracks done so far."
                            }
                        )
                        return@launch
                    }

                    val genres = withContext(Dispatchers.IO) { graph.genreLookup() }
                    val station = withContext(Dispatchers.Default) {
                        RadioQueueManager(
                            index = index,
                            config = RadioConfig.forOrigin(origin),
                            genres = genres
                        ).generate(seedTrackId)
                    }
                    if (station.isEmpty()) {
                        notify("Nothing in your library sounds close enough to this.")
                        return@launch
                    }

                    val queue = withContext(Dispatchers.IO) {
                        buildList {
                            db.timelineDao().getTrackById(seedTrackId)?.let(::add)
                            station.forEach { pick ->
                                db.timelineDao().getTrackById(pick.trackId)?.let(::add)
                            }
                        }
                    }
                    if (queue.isEmpty()) {
                        notify("Could not load the station's tracks.")
                        return@launch
                    }
                    playbackManager.setQueue(queue, 0)
                    notify("Station started — ${queue.size - 1} tracks queued.")
                }
            }

            // ── The one place playback is started from ──────────────
            //
            // Every screen used to call a "play this one track" helper, which
            // built a queue of exactly one item. These build a real queue and
            // hand it to the player intact.
            // Drag-to-queue. The source is a row inside the timeline's
            // AnimatedContent and the target is the slab, a sibling of it in the
            // root Box — neither can see the other, so the state lives here.
            val dragToQueue = remember { DragToQueueController() }

            val playback = remember(playbackState.currentTrack?.trackId, playbackState.isPlaying) {
                PlaybackBinding(
                    nowPlayingTrackId = playbackState.currentTrack?.trackId,
                    isPlaying = playbackState.isPlaying,
                    playTracks = { tracks, index ->
                        ensurePlaybackPermissions()
                        playbackManager.setQueue(tracks, index)
                    },
                    shuffleTracks = { tracks ->
                        ensurePlaybackPermissions()
                        playbackManager.shuffleQueue(tracks)
                    },
                    addToQueue = { tracks -> playbackManager.addToQueue(tracks) },
                    playAlbum = { releaseId ->
                        scope.launch {
                            val tracks = withContext(Dispatchers.IO) { libraryDao.getTracksForRelease(releaseId) }
                            if (tracks.isNotEmpty()) {
                                ensurePlaybackPermissions()
                                playbackManager.setQueue(tracks, 0)
                            }
                        }
                    },
                    shuffleAlbum = { releaseId ->
                        scope.launch {
                            val tracks = withContext(Dispatchers.IO) { libraryDao.getTracksForRelease(releaseId) }
                            if (tracks.isNotEmpty()) {
                                ensurePlaybackPermissions()
                                playbackManager.shuffleQueue(tracks)
                            }
                        }
                    },
                    playArtist = { artistId ->
                        scope.launch {
                            val tracks = withContext(Dispatchers.IO) { libraryDao.getTracksForArtist(artistId) }
                            if (tracks.isNotEmpty()) {
                                ensurePlaybackPermissions()
                                playbackManager.setQueue(tracks, 0)
                            }
                        }
                    },
                    addAlbumToQueue = { releaseId ->
                        scope.launch {
                            val tracks = withContext(Dispatchers.IO) { libraryDao.getTracksForRelease(releaseId) }
                            if (tracks.isNotEmpty()) playbackManager.addToQueue(tracks)
                        }
                    },
                    playPlaylist = { playlistId ->
                        scope.launch {
                            val tracks = withContext(Dispatchers.IO) { playlistDao.getTracks(playlistId) }
                            if (tracks.isNotEmpty()) {
                                ensurePlaybackPermissions()
                                playbackManager.setQueue(tracks, 0)
                            }
                        }
                    },
                    shuffleArtist = { artistId ->
                        scope.launch {
                            val tracks = withContext(Dispatchers.IO) { libraryDao.getTracksForArtist(artistId) }
                            if (tracks.isNotEmpty()) {
                                ensurePlaybackPermissions()
                                playbackManager.shuffleQueue(tracks)
                            }
                        }
                    },
                    shuffleLibrary = {
                        scope.launch {
                            val tracks = withContext(Dispatchers.IO) { libraryDao.getAllPlayableTracks() }
                            if (tracks.isNotEmpty()) {
                                ensurePlaybackPermissions()
                                playbackManager.shuffleQueue(tracks)
                            }
                        }
                    },
                    // Long-pressing any track anywhere reaches the metadata
                    // editor. Nothing set this before, so the sheet — and every
                    // edit it can make — was unreachable.
                    openTrackDetail = { trackId -> selectedTrackId = trackId },
                    startRadio = { trackId, origin -> startRadio(trackId, origin) },
                    togglePlayPause = { playbackManager.togglePlayPause() }
                )
            }

            // Handle bottom nav tab switching
            fun switchTab(dest: NavDestination) {
                if (dest == currentNav && backStack.isEmpty()) return
                currentNav = dest
                // Clear backstack when switching tabs
                backStack.clear()
                screen = when (dest) {
                    NavDestination.HOME -> Screen.Home
                    NavDestination.TIMELINE -> Screen.Timeline
                    NavDestination.SEARCH -> Screen.Search
                    NavDestination.LIBRARY -> Screen.Library
                }
            }

            BackHandler(
                enabled = selectedTrackId != null || addingToPlaylist != null ||
                    isNowPlayingExpanded || backStack.isNotEmpty()
            ) {
                scope.launch {
                    when {
                        addingToPlaylist != null -> addingToPlaylist = null
                        isNowPlayingExpanded -> isNowPlayingExpanded = false
                        selectedTrackId != null -> selectedTrackId = null
                        backStack.isNotEmpty() -> goBack()
                    }
                }
            }

            // The drawer is gone, and with it the RTL layout-direction flip that
            // existed only to move it to the right-hand edge. Everything it held
            // is on the Settings screen now.
            CompositionLocalProvider(
                LocalWallpaperDominant provides wallpaperDominant,
                LocalWallpaperAccent provides wallpaperAccent,
                LocalPlayback provides playback,
                LocalDragToQueue provides dragToQueue
            ) {
                        Box(Modifier.fillMaxSize()) {
                            // Luminous base plane. Drawn unconditionally so the
                            // app is legible before a wallpaper is picked and if
                            // the chosen one fails to decode — the window theme
                            // underneath is light, and white-on-white made the
                            // whole UI vanish on first run.
                            AgAmbientBackdrop()

                            // Wallpaper background
                            if (wallpaperUri != null) {
                                AsyncImage(
                                    model = wallpaperUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Main content area (with bottom padding for nav bar)
                            AnimatedContent(
                                targetState = screen,
                                transitionSpec = {
                                    when {
                                        // Tab switches: crossfade
                                        targetState is Screen.Home || targetState is Screen.Timeline ||
                                        targetState is Screen.Search || targetState is Screen.Library -> {
                                            if (initialState is Screen.Home || initialState is Screen.Timeline ||
                                                initialState is Screen.Search || initialState is Screen.Library) {
                                                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                                            } else {
                                                // Coming back from sub-screen
                                                slideInVertically(
                                                    initialOffsetY = { -it / 4 },
                                                    animationSpec = tween(220, easing = AgMotion.EnteringEasing)
                                                ) + fadeIn(tween(220)) togetherWith
                                                slideOutVertically(
                                                    targetOffsetY = { it },
                                                    animationSpec = tween(220, easing = AgMotion.ExitingEasing)
                                                ) + fadeOut(tween(220))
                                            }
                                        }
                                        targetState is Screen.Feed -> {
                                            slideInVertically(
                                                initialOffsetY = { it },
                                                animationSpec = tween(300, easing = AgMotion.EnteringEasing)
                                            ) + fadeIn(tween(300)) togetherWith
                                            slideOutVertically(
                                                targetOffsetY = { -it / 4 },
                                                animationSpec = tween(220, easing = AgMotion.ExitingEasing)
                                            ) + fadeOut(tween(220))
                                        }
                                        targetState is Screen.Artist ||
                                        targetState is Screen.Settings ||
                                        targetState is Screen.Playlists ||
                                        targetState is Screen.LibraryAlbums ||
                                        targetState is Screen.LibraryTracks ||
                                        targetState is Screen.LibraryArtists ||
                                        targetState is Screen.Playlist ||
                                        targetState is Screen.LikedSongs ||
                                        targetState is Screen.LikedAlbums ||
                                        targetState is Screen.LikedArtists ||
                                        targetState is Screen.Album -> {
                                            slideInHorizontally(
                                                initialOffsetX = { it },
                                                animationSpec = tween(300, easing = AgMotion.EnteringEasing)
                                            ) + fadeIn(tween(300)) togetherWith
                                            slideOutHorizontally(
                                                targetOffsetX = { -it / 4 },
                                                animationSpec = tween(220, easing = AgMotion.ExitingEasing)
                                            ) + fadeOut(tween(220))
                                        }
                                        initialState is Screen.Feed -> {
                                            slideInVertically(
                                                initialOffsetY = { -it / 4 },
                                                animationSpec = tween(220, easing = AgMotion.EnteringEasing)
                                            ) + fadeIn(tween(220)) togetherWith
                                            slideOutVertically(
                                                targetOffsetY = { it },
                                                animationSpec = tween(220, easing = AgMotion.ExitingEasing)
                                            ) + fadeOut(tween(220))
                                        }
                                        initialState is Screen.Artist ||
                                        initialState is Screen.Settings ||
                                        initialState is Screen.Playlists ||
                                        initialState is Screen.LibraryAlbums ||
                                        initialState is Screen.LibraryTracks ||
                                        initialState is Screen.LibraryArtists ||
                                        initialState is Screen.Playlist ||
                                        initialState is Screen.LikedSongs ||
                                        initialState is Screen.LikedAlbums ||
                                        initialState is Screen.LikedArtists ||
                                        initialState is Screen.Album -> {
                                            slideInHorizontally(
                                                initialOffsetX = { -it / 4 },
                                                animationSpec = tween(220, easing = AgMotion.EnteringEasing)
                                            ) + fadeIn(tween(220)) togetherWith
                                            slideOutHorizontally(
                                                targetOffsetX = { it },
                                                animationSpec = tween(220, easing = AgMotion.ExitingEasing)
                                            ) + fadeOut(tween(220))
                                        }
                                        else -> fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                                    }
                                },
                                label = "screenTransition",
                                modifier = Modifier.fillMaxSize()
                            ) { currentScreen ->
                                when (val s = currentScreen) {
                                    Screen.Home -> {
                                        HomeScreen(
                                            feeds = feeds,
                                            onOpenArtist = { id -> navTo(Screen.Artist(id)) },
                                            onOpenPlaylist = { id -> navTo(Screen.Playlist(id)) },
                                            onOpenAlbum = { releaseId -> openAlbum(releaseId) },
                                            onIngest = { requestMediaScan() },
                                            modifier = Modifier.padding(bottom = 72.dp)
                                        )
                                    }
                                    Screen.Timeline -> {
                                        // Collected here rather than at the root of
                                        // setContent: the bucket group-by used to run
                                        // on every screen, and its results invalidated
                                        // the whole tree. See TimelineFeed.
                                        val timelineQuery by timelineFeed.query.collectAsState()
                                        val timelineBuckets by timelineFeed.buckets.collectAsState()
                                        val undatedCount by timelineFeed.undatedCount.collectAsState()
                                        TimelineBucketsScreen(
                                            queryEngine = timelineEngine,
                                            query = timelineQuery,
                                            bucketData = timelineBuckets,
                                            onQueryChange = { timelineFeed.setQuery(it) },
                                            onOpenFeedForBucket = { start, end, q ->
                                                navTo(Screen.Feed(start, end, q))
                                            },
                                            onOpenArtist = { id -> navTo(Screen.Artist(id)) },
                                            onOpenAlbum = { releaseId -> openAlbum(releaseId) },
                                            onIngest = { requestMediaScan() },
                                            undatedCount = undatedCount,
                                            // Cached bucket previews survive a scan
                                            // otherwise, showing pre-scan tracks
                                            // under post-scan buckets.
                                            refreshKey = dbGeneration,
                                            listState = timelineListState
                                        )
                                    }
                                    Screen.Search -> {
                                        SearchScreen(
                                            libraryDao = libraryDao,
                                            onOpenArtist = { id -> navTo(Screen.Artist(id)) },
                                            onOpenAlbum = { releaseId -> openAlbum(releaseId) },
                                            modifier = Modifier.padding(bottom = 72.dp)
                                        )
                                    }
                                    Screen.Library -> {
                                        LibraryScreen(
                                            feeds = feeds,
                                            likedSongCount = likedSongCount,
                                            likedAlbumCount = likedAlbumCount,
                                            likedArtistCount = likedArtistCount,
                                            playlistCount = playlists.size,
                                            onOpenSection = { section ->
                                                navTo(
                                                    when (section) {
                                                        LibrarySection.LIKED_SONGS -> Screen.LikedSongs
                                                        LibrarySection.LIKED_ALBUMS -> Screen.LikedAlbums
                                                        LibrarySection.LIKED_ARTISTS -> Screen.LikedArtists
                                                        LibrarySection.PLAYLISTS -> Screen.Playlists
                                                        LibrarySection.ALBUMS -> Screen.LibraryAlbums
                                                        LibrarySection.TRACKS -> Screen.LibraryTracks
                                                        LibrarySection.ARTISTS -> Screen.LibraryArtists
                                                    }
                                                )
                                            },
                                            onOpenAlbum = { releaseId -> openAlbum(releaseId) },
                                            onOpenSettings = { navTo(Screen.Settings) },
                                            onIngest = { requestMediaScan() },
                                            modifier = Modifier.padding(bottom = 72.dp)
                                        )
                                    }
                                    Screen.Settings -> {
                                        // Follows the work state rather than
                                        // sampling it. Refreshing right after
                                        // enqueue reads the *previous*
                                        // request's state — which is why
                                        // force-starting still reported
                                        // "waiting for a charger".
                                        LaunchedEffect(Unit) {
                                            RadioIndexing.observeStatus(this@MainActivity)
                                                .collect { radioStatus = graph.radioStatus() }
                                        }
                                        // Polled while Settings is open, not
                                        // keyed on dbGeneration — that only
                                        // changes on a scan, so the numbers sat
                                        // frozen through an entire enrichment
                                        // run and made a working job look dead.
                                        LaunchedEffect(Unit) {
                                            while (true) {
                                                enrichmentStatus = graph.enrichmentStatus()
                                                delay(2_000)
                                            }
                                        }
                                        val vizDiagnostics by playbackManager.diagnostics.collectAsState()
                                        SettingsScreen(
                                            feeds = feeds,
                                            diagnostics = vizDiagnostics,
                                            isIngesting = isIngesting,
                                            hasWallpaper = wallpaperUri != null,
                                            reflectionEnabled = reflectionEnabled,
                                            versionLabel = versionLabel,
                                            enrichmentStatus = enrichmentStatus,
                                            radioStatus = radioStatus,
                                            onRefreshRadioStatus = {
                                                scope.launch { radioStatus = graph.radioStatus() }
                                            },
                                            onIndexLibrary = {
                                                // No manual refresh: the flow
                                                // above reports the new state
                                                // once the scheduler has it.
                                                RadioIndexing.enqueue(this@MainActivity)
                                                graph.invalidateEmbeddingIndex()
                                                notify("Analysis queued. It runs while charging.")
                                            },
                                            onRetryUnreadable = {
                                                scope.launch {
                                                    val n = graph.retryFailedEmbeddings()
                                                    RadioIndexing.enqueue(this@MainActivity, force = true)
                                                    radioStatus = graph.radioStatus()
                                                    notify("Retrying $n skipped tracks.")
                                                }
                                            },
                                            onStopAnalysis = {
                                                scope.launch {
                                                    RadioIndexing.cancelAndAwait(this@MainActivity)
                                                    graph.invalidateEmbeddingIndex()
                                                    notify("Analysis stopped.")
                                                }
                                            },
                                            onClearAnalysis = {
                                                scope.launch {
                                                    val n = graph.clearEmbeddings()
                                                    radioStatus = graph.radioStatus()
                                                    notify("Stopped analysis and cleared $n tracks.")
                                                }
                                            },
                                            onForceIndexLibrary = {
                                                RadioIndexing.enqueue(this@MainActivity, force = true)
                                                graph.invalidateEmbeddingIndex()
                                                notify("Analysing now, charger or not.")
                                            },
                                            onBack = { goBack() },
                                            onIngestMediaStore = { requestMediaScan() },
                                            onIngestFolder = { safLauncher.launch(null) },
                                            onEnrichDates = {
                                                ReleaseDateEnrichmentWorker.runNow(applicationContext)
                                            },
                                            artistImageDao = artistImageDao,
                                            onFetchArtistImages = {
                                                ArtistImageWorker.runNow(applicationContext)
                                            },
                                            onFetchArtistBios = {
                                                // Same worker: portraits and bios
                                                // are one pass over one list.
                                                ArtistImageWorker.runNow(applicationContext)
                                                notify("Fetching artist info in the background.")
                                            },
                                            artistCleanupStatus = artistCleanupStatus,
                                            trackCleanupStatus = trackCleanupStatus,
                                            onMergeDuplicateTracks = {
                                                trackCleanupStatus = "Merging…"
                                                scope.launch {
                                                    // Same guard as the artist tidy: this
                                                    // rewrites eleven tables in one
                                                    // transaction, and an uncaught throw
                                                    // here takes the app down.
                                                    val report = withContext(Dispatchers.IO) {
                                                        runCatching { trackMaintenance.mergeDuplicates() }
                                                    }.getOrElse { error ->
                                                        trackCleanupStatus =
                                                            "Could not merge: ${error.message ?: "unknown error"}"
                                                        return@launch
                                                    }
                                                    trackCleanupStatus = if (report.changed) {
                                                        "Merged ${report.tracksRemoved} duplicate " +
                                                            "${if (report.tracksRemoved == 1) "track" else "tracks"} · " +
                                                            "${report.tracksBefore} → ${report.tracksAfter} tracks · " +
                                                            "${report.playsPreserved} plays kept"
                                                    } else {
                                                        "No duplicates — ${report.tracksAfter} tracks look clean"
                                                    }
                                                    TimelineItemRow.invalidateArtCache()
                                                    dbGeneration++
                                                }
                                            },
                                            onTidyArtists = {
                                                artistCleanupStatus = "Tidying…"
                                                scope.launch {
                                                    // cleanUp rewrites the artist table under a
                                                    // unique index inside one transaction. It
                                                    // used to throw on exactly the duplicates it
                                                    // exists to merge, and an uncaught throw here
                                                    // takes the app down rather than the button.
                                                    val report = withContext(Dispatchers.IO) {
                                                        runCatching { artistMaintenance.cleanUp() }
                                                    }.getOrElse { error ->
                                                        artistCleanupStatus =
                                                            "Could not tidy artists: ${error.message ?: "unknown error"}"
                                                        return@launch
                                                    }
                                                    artistCleanupStatus = if (report.changed) {
                                                        "Merged ${report.duplicatesMerged} duplicates and " +
                                                            "split ${report.creditsSplit} credits · " +
                                                            "${report.artistsBefore} → ${report.artistsAfter} artists"
                                                    } else {
                                                        "Nothing to fix — ${report.artistsAfter} artists look clean"
                                                    }
                                                }
                                            },
                                            onClearArtistImages = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        artistImageDao.clearAll()
                                                    }
                                                }
                                            },
                                            onSelectWallpaper = { wallpaperLauncher.launch(arrayOf("image/*")) },
                                            onClearWallpaper = {
                                                wallpaperUri = null
                                                prefs.edit().remove("wallpaper_uri").apply()
                                            },
                                            onToggleReflection = { enabled ->
                                                reflectionEnabled = enabled
                                                prefs.edit()
                                                    .putBoolean("slab_reflection_enabled", enabled)
                                                    .apply()
                                            },
                                            onResetSlabPosition = {
                                                // The cube's position is persisted, so a drag
                                                // that parks it somewhere awkward had no way out.
                                                prefs.edit()
                                                    .remove("slab_x")
                                                    .remove("slab_y")
                                                    .apply()
                                                slabInitialized = false
                                            },
                                            onClearPlayHistory = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        playHistoryDao.clearHistory()
                                                    }
                                                }
                                            },
                                            onRebuildDatabase = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { db.clearAllTables() }
                                                    dbGeneration++
                                                }
                                            }
                                        )
                                    }
                                    Screen.Playlists -> {
                                        PlaylistsScreen(
                                            playlistDao = playlistDao,
                                            feeds = feeds,
                                            sort = playlistSort,
                                            onSortChange = { next ->
                                                playlistSort = next
                                                prefs.edit().putString("playlist_sort", next.name).apply()
                                            },
                                            onOpenPlaylist = { id -> navTo(Screen.Playlist(id)) },
                                            onBack = { goBack() },
                                            modifier = Modifier.padding(bottom = 72.dp)
                                        )
                                    }
                                    Screen.LibraryAlbums -> {
                                        LibraryAlbumsScreen(
                                            feeds = feeds,
                                            onBack = { goBack() },
                                            onOpenAlbum = { releaseId -> openAlbum(releaseId) }
                                        )
                                    }
                                    Screen.LibraryTracks -> {
                                        LibraryTracksScreen(
                                            feeds = feeds,
                                            onBack = { goBack() }
                                        )
                                    }
                                    Screen.LibraryArtists -> {
                                        LibraryArtistsScreen(
                                            feeds = feeds,
                                            onBack = { goBack() },
                                            onOpenArtist = { id -> navTo(Screen.Artist(id)) }
                                        )
                                    }
                                    is Screen.Playlist -> {
                                        PlaylistDetailScreen(
                                            playlistId = s.playlistId,
                                            playlistDao = playlistDao,
                                            onBack = { goBack() },
                                            onOpenArtist = { id -> navTo(Screen.Artist(id)) }
                                        )
                                    }
                                    Screen.LikedSongs -> {
                                        LikedSongsScreen(
                                            likesDao = likesDao,
                                            onBack = { goBack() },
                                            onOpenTrackDetail = { id -> selectedTrackId = id }
                                        )
                                    }
                                    Screen.LikedArtists -> {
                                        LikedArtistsScreen(
                                            likesDao = likesDao,
                                            onBack = { goBack() },
                                            onOpenArtist = { id -> navTo(Screen.Artist(id)) }
                                        )
                                    }
                                    Screen.LikedAlbums -> {
                                        LikedAlbumsScreen(
                                            likesDao = likesDao,
                                            onBack = { goBack() },
                                            onOpenAlbum = { releaseId -> openAlbum(releaseId) }
                                        )
                                    }
                                    is Screen.Album -> {
                                        AlbumScreen(
                                            releaseId = s.releaseId,
                                            albumDao = albumDao,
                                            likesDao = likesDao,
                                            onBack = { goBack() },
                                            onOpenArtist = { id -> navTo(Screen.Artist(id)) },
                                            onOpenTrackDetail = { id -> selectedTrackId = id }
                                        )
                                    }
                                    is Screen.Feed -> {
                                        BucketFeedScreen(
                                            queryEngine = feedEngine,
                                            bucketStartEpochMs = s.start,
                                            bucketEndEpochMs = s.end,
                                            baseQuery = s.query,
                                            onBack = { goBack() },
                                            onOpenArtist = { id -> navTo(Screen.Artist(id)) }
                                        )
                                    }
                                    is Screen.Artist -> {
                                        ArtistScreen(
                                            artistId = s.artistId,
                                            artistDao = artistPageDao,
                                            likesDao = likesDao,
                                            artistImageDao = artistImageDao,
                                            onRequestBio = { id, name ->
                                                graph.ensureArtistBio(id, name)
                                            },
                                            onBack = { goBack() },
                                            onOpenAlbum = { releaseId -> openAlbum(releaseId) },
                                            onOpenTrackDetail = { id -> selectedTrackId = id }
                                        )
                                    }
                                }
                            }

                            // Bottom Navigation Bar (always visible)
                            BottomNavBar(
                                items = navItems,
                                selected = currentNav,
                                onSelect = { switchTab(it) },
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )

                            if (selectedTrackId != null) {
                                val detailTrackId = selectedTrackId!!
                                TrackDetailBottomSheet(
                                    trackId = detailTrackId,
                                    repo = trackRepo,
                                    onDismiss = { selectedTrackId = null },
                                    onAddToQueue = {
                                        scope.launch {
                                            val track = withContext(Dispatchers.IO) {
                                                db.timelineDao().getTrackById(detailTrackId)
                                            }
                                            if (track != null) {
                                                ensurePlaybackPermissions()
                                                playbackManager.addToQueue(listOf(track))
                                            }
                                            selectedTrackId = null
                                        }
                                    },
                                    onAddToPlaylist = {
                                        selectedTrackId = null
                                        addingToPlaylist = listOf(detailTrackId)
                                    },
                                    onViewReleaseDay = {
                                        scope.launch {
                                            val track = withContext(Dispatchers.IO) {
                                                db.timelineDao().getTrackById(detailTrackId)
                                            }
                                            track?.effectiveReleaseDateEpochMs?.let { dateMs ->
                                                val dayMs = 24 * 60 * 60 * 1000L
                                                val start = (dateMs / dayMs) * dayMs
                                                navTo(
                                                    Screen.Feed(
                                                        start, start + dayMs,
                                                        ViewQuery(bucket = TimelineBucket.DAY)
                                                    )
                                                )
                                            }
                                            selectedTrackId = null
                                            isNowPlayingExpanded = false
                                        }
                                    }
                                )
                            }

                            addingToPlaylist?.let { ids ->
                                AddToPlaylistSheet(
                                    trackIds = ids,
                                    playlistDao = playlistDao,
                                    feeds = feeds,
                                    onDismiss = { addingToPlaylist = null }
                                )
                            }

                            // Now Playing Slab (free-floating, draggable)
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val density = LocalDensity.current
                                // Sized from the slab's own metrics so the drag bounds can
                                // never drift away from what it actually draws.
                                val slabWidthPx = with(density) { NowPlayingSlabMetrics.Width.toPx() }
                                val slabHeightPx = with(density) {
                                    NowPlayingSlabMetrics.height(reflectionEnabled).toPx()
                                }
                                val maxW = constraints.maxWidth.toFloat()
                                val maxH = constraints.maxHeight.toFloat()
                                val maxX = (maxW - slabWidthPx).coerceAtLeast(0f)
                                val maxY = (maxH - slabHeightPx).coerceAtLeast(0f)

                                // Where the slab sits when it has not been moved,
                                // and where it is pinned on the timeline.
                                val homeX = (maxW - slabWidthPx) / 2f
                                val homeY = maxH - slabHeightPx - with(density) { 96.dp.toPx() }

                                // The timeline pins it.
                                //
                                // It is meant to be a drop target there — you drag
                                // music onto it to queue that music — and a target
                                // cannot also be the thing you are dragging. Pinning
                                // resolves the conflict on the one screen where it
                                // matters, and means the target is always in the
                                // same place, which is what makes it aimable.
                                //
                                // The user's own position is left untouched in state
                                // and comes straight back on any other screen.
                                val slabPinned = screen is Screen.Timeline
                                val drawX = if (slabPinned) homeX else slabOffsetX
                                val drawY = if (slabPinned) homeY else slabOffsetY

                                // Place on first layout, then keep the tile on screen when the
                                // window resizes. Done in an effect rather than by writing
                                // state during composition.
                                LaunchedEffect(maxX, maxY, slabInitialized) {
                                    if (!slabInitialized) {
                                        slabOffsetX = homeX
                                        slabOffsetY = homeY
                                        slabInitialized = true
                                    }
                                    slabOffsetX = slabOffsetX.coerceIn(0f, maxX)
                                    slabOffsetY = slabOffsetY.coerceIn(0f, maxY)
                                }

                                // Persist position with debounce
                                LaunchedEffect(slabOffsetX, slabOffsetY) {
                                    if (slabInitialized) {
                                        delay(500)
                                        prefs.edit()
                                            .putFloat("slab_x", slabOffsetX)
                                            .putFloat("slab_y", slabOffsetY)
                                            .apply()
                                    }
                                }

                                // Publish where the slab is so a drag can be
                                // aimed at it. Pinned on the timeline, so this
                                // stays still while you are aiming.
                                LaunchedEffect(drawX, drawY, slabWidthPx, slabHeightPx) {
                                    dragToQueue.targetBounds = Rect(
                                        offset = Offset(drawX, drawY),
                                        size = Size(slabWidthPx, slabHeightPx)
                                    )
                                }

                                if (playbackState.currentTrack != null && !isNowPlayingExpanded) {
                                    Box(
                                        modifier = Modifier
                                            .offset { IntOffset(drawX.roundToInt(), drawY.roundToInt()) }
                                            // No reposition gesture while pinned, so the
                                            // slab stops competing for the drag it is
                                            // supposed to receive.
                                            .then(
                                                if (slabPinned) Modifier
                                                else Modifier.pointerInput(maxX, maxY) {
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        slabOffsetX = (slabOffsetX + dragAmount.x)
                                                            .coerceIn(0f, maxX)
                                                        slabOffsetY = (slabOffsetY + dragAmount.y)
                                                            .coerceIn(0f, maxY)
                                                    }
                                                }
                                            )
                                    ) {
                                        NowPlayingSlab(
                                            state = playbackState,
                                            fftData = fftData,
                                            visualizerColors = visualizerColors,
                                            showReflection = reflectionEnabled,
                                            onClick = { isNowPlayingExpanded = true }
                                        )
                                    }
                                }
                            }

                            // What is being dragged, following the finger.
                            //
                            // Drawn last so it floats over every screen and over
                            // the slab it is being carried to.
                            dragToQueue.payload?.let { payload ->
                                DragToQueuePreview(
                                    payload = payload,
                                    position = dragToQueue.position,
                                    isOverTarget = dragToQueue.isOverTarget
                                )
                            }

                            // Modal scrim, between the live UI and the player.
                            //
                            // Two jobs, and the second is the important one. It
                            // darkens what is behind so the player's labels read,
                            // and it makes the player actually modal: the screens
                            // under it are siblings in this Box, so without a
                            // scrim they stay hit-testable. Home's horizontal
                            // shelves sit directly under the scrubber and were
                            // taking its drag, which is why the thumb moved and
                            // the track never seeked — the gesture ended in
                            // onDragCancel rather than onDragEnd. Swiping the
                            // shelves through the panel was the same bug.
                            AnimatedVisibility(
                                visible = isNowPlayingExpanded && playbackState.currentTrack != null,
                                enter = fadeIn(tween(300)),
                                exit = fadeOut(tween(200))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .pointerInput(Unit) {
                                            // Tap outside the panel to dismiss;
                                            // everything else is absorbed here
                                            // rather than reaching the screen below.
                                            detectTapGestures { isNowPlayingExpanded = false }
                                        }
                                )
                            }

                            // Now Playing Expanded Overlay with slide-up animation
                            // Keyed on the playing track, and collected here rather
                            // than at the root so it follows the player's lifetime.
                            val currentTrackId = playbackState.currentTrack?.trackId
                            val isCurrentTrackLiked by remember(currentTrackId) {
                                if (currentTrackId == null) flowOf(false)
                                else likesDao.observeTrackLiked(currentTrackId)
                            }.collectAsState(initial = false)

                            AnimatedVisibility(
                                visible = isNowPlayingExpanded && playbackState.currentTrack != null,
                                enter = slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = tween(300)
                                ) + fadeIn(animationSpec = tween(300)),
                                exit = slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = tween(200)
                                ) + fadeOut(animationSpec = tween(200)),
                                modifier = Modifier.align(Alignment.BottomCenter)
                            ) {
                                NowPlayingExpanded(
                                    state = playbackState,
                                    progressFlow = playbackManager.progress,
                                    fftData = fftData,
                                    visualizerColors = visualizerColors,
                                    onTogglePlay = { playbackManager.togglePlayPause() },
                                    onNext = { playbackManager.skipToNext() },
                                    onPrevious = { playbackManager.skipToPrevious() },
                                    onSeek = { fraction -> playbackManager.seekToFraction(fraction) },
                                    onToggleShuffle = { playbackManager.toggleShuffle() },
                                    onCycleRepeat = { playbackManager.cycleRepeatMode() },
                                    onPlayQueueIndex = { index -> playbackManager.playQueueIndex(index) },
                                    onClose = { isNowPlayingExpanded = false },
                                    isLiked = isCurrentTrackLiked,
                                    onToggleLike = {
                                        playbackState.currentTrack?.trackId?.let { id ->
                                            scope.launch { likesDao.toggleTrackLiked(id) }
                                        }
                                    },
                                    onOpenInfo = {
                                        // The metadata sheet, for what is playing.
                                        playbackState.currentTrack?.trackId?.let { selectedTrackId = it }
                                    }
                                )
                            }
                        }
            }
        }
    }

    override fun onDestroy() {
        playbackManager.release()
        super.onDestroy()
    }

    private companion object {
        // Mirrors ArtistImageUrls, which lives in a module the app cannot see
        // the internals of. Kept here so the candidate query can apply them.
        const val ARTIST_IMAGE_MAX_ATTEMPTS = 3
        const val ARTIST_IMAGE_RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    }

    sealed class Screen {
        data object Home : Screen()
        data object Timeline : Screen()
        data object Search : Screen()
        data object Library : Screen()
        data class Feed(val start: Long, val end: Long, val query: ViewQuery) : Screen()
        data class Artist(val artistId: Long) : Screen()

        // Library drill-downs. The collection index is a signpost list now, so
        // each category is a destination rather than a tab.
        data object Settings : Screen()
        data object Playlists : Screen()
        data object LibraryAlbums : Screen()
        data object LibraryTracks : Screen()
        data object LibraryArtists : Screen()
        data object LikedSongs : Screen()
        data object LikedAlbums : Screen()
        data object LikedArtists : Screen()
        /** The album page. Was a date feed filtered to one release. */
        data class Album(val releaseId: Long) : Screen()
        data class Playlist(val playlistId: Long) : Screen()
    }
}

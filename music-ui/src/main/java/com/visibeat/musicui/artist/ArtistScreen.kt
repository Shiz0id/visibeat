package com.visibeat.musicui.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.visibeat.musicui.design.*
import com.visibeat.musicui.playback.LocalPlayback
import com.visibeat.radio.RadioOrigin
import com.visibeat.radio.RadioSeed
import com.visibeat.musicui.playback.NowPlayingRowIndicator
import com.visibeat.viewengine.ArtistImageDao
import com.visibeat.viewengine.ArtistPageDao
import com.visibeat.viewengine.ArtistReleaseGrouping
import com.visibeat.viewengine.ArtistReleaseRow
import com.visibeat.viewengine.LikesDao
import com.visibeat.viewengine.TimelineItemRow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * The artist.
 *
 * Replaces the album-timeline screen, which laid an artist's releases out on the
 * app's vertical spine. The spine still owns the Timeline tab, where it reads as
 * the app's identity rather than as an unusual way to browse one artist.
 *
 * Where a streaming service puts a fan count, this puts counts from your own
 * library and a description from Wikidata — the two things that are actually
 * true here. Top Tracks is ranked by your play history for the same reason.
 */
@Composable
fun ArtistScreen(
    artistId: Long,
    artistDao: ArtistPageDao,
    likesDao: LikesDao,
    /** Live view of the artist cache — portrait, description, Wikipedia extract. */
    artistImageDao: ArtistImageDao,
    /**
     * Fetches and caches the artist's Wikipedia lead section if it is not
     * already stored. Owned by the app module, the only thing that can see the
     * networking.
     */
    onRequestBio: suspend (artistId: Long, artistName: String) -> Unit,
    onBack: () -> Unit,
    onOpenAlbum: (releaseId: Long) -> Unit,
    onOpenTrackDetail: (trackId: Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current

    val header by artistDao.observeArtistHeader(artistId).collectAsState(initial = null)
    val topTracks by artistDao.observeTopTracks(artistId).collectAsState(initial = emptyList())
    val allTracks by artistDao.observeArtistTracks(artistId).collectAsState(initial = emptyList())
    val releases by artistDao.observeArtistReleases(artistId).collectAsState(initial = emptyList())
    val isFollowed by likesDao.observeArtistLiked(artistId).collectAsState(initial = false)

    var showInfo by remember { mutableStateOf(false) }

    val cached by artistImageDao.observe(artistId).collectAsState(initial = null)
    /*
     * Fetched when the screen opens now, not when Info is tapped.
     *
     * That reverses the earlier call, and the reason it was right then is the
     * reason it is wrong now: the bio was behind a button most people never
     * pressed, so fetching lazily cost a donated API nothing. Text on the header
     * has to be there before anyone asks for it.
     *
     * The cost is bounded by the cache, not by traffic: `ensureArtistBio` is a
     * no-op once an extract is stored, so this is one request per artist ever,
     * not one per visit.
     */
    var bioLoading by remember(artistId) { mutableStateOf(false) }
    LaunchedEffect(artistId, header?.artistName) {
        val name = header?.artistName ?: return@LaunchedEffect
        if (!cached?.wikipediaExtract.isNullOrBlank()) return@LaunchedEffect
        bioLoading = true
        onRequestBio(artistId, name)
        bioLoading = false
    }

    val bySection = remember(releases) {
        releases.groupBy { ArtistReleaseGrouping.sectionFor(it.releaseType, it.isPrimaryArtist) }
    }
    // A ranking of zeroes is not a ranking, so the heading tells the truth.
    val hasPlays = (header?.playCount ?: 0) > 0

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 180.dp)
        ) {
            item {
                ArtistHero(
                    portraitModel = header?.imageUrl,
                    albumArtModel = header?.albumArtModel,
                    name = header?.artistName ?: "Unknown Artist",
                    stats = header?.let { statsLine(it.releaseCount, it.trackCount, it.playCount) },
                    // The Wikipedia lead when it has arrived, the Wikidata
                    // one-liner until then. Both beat a gap that fills in a
                    // second later and shoves everything below it down.
                    bio = cached?.wikipediaExtract?.takeIf { it.isNotBlank() }
                        ?: header?.description,
                    bioIsWikipedia = !cached?.wikipediaExtract.isNullOrBlank(),
                    onExpandBio = { showInfo = true },
                    onBack = onBack
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ArtistPrimaryAction(
                        icon = Icons.Default.PlayArrow,
                        label = "Play",
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { if (allTracks.isNotEmpty()) playback.playTracks(allTracks, 0) }
                    )
                    ArtistPrimaryAction(
                        icon = Icons.Default.Shuffle,
                        label = "Shuffle",
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = { if (allTracks.isNotEmpty()) playback.shuffleTracks(allTracks) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ArtistAction(
                        icon = Icons.Default.Radio,
                        label = "Radio",
                        // Seeded on the artist's most-played track: the one the
                        // listener actually reaches for is a better statement of
                        // what they want to hear next than the first row is.
                        onClick = {
                            RadioSeed.forArtist(topTracks.map { it.trackId })
                                ?.let { playback.startRadio(it, RadioOrigin.ARTIST) }
                        }
                    )
                    ArtistAction(
                        icon = if (isFollowed) Icons.Default.Check else Icons.Default.PersonAdd,
                        label = if (isFollowed) "Following" else "Follow",
                        tint = if (isFollowed) accent else AgPalette.TextPrimary,
                        onClick = { scope.launch { likesDao.toggleArtistLiked(artistId) } }
                    )
                    ArtistAction(
                        icon = Icons.Outlined.Info,
                        label = "Info",
                        onClick = { showInfo = true }
                    )
                }

            }

            if (topTracks.isNotEmpty()) {
                item { SectionHeading(if (hasPlays) "Top Tracks" else "Tracks") }
                items(topTracks, key = { it.trackId }) { track ->
                    ArtistTrackRow(
                        track = track,
                        onClick = {
                            val index = allTracks.indexOfFirst { it.trackId == track.trackId }
                            playback.playTracks(allTracks, index.coerceAtLeast(0))
                        },
                        onLongClick = { onOpenTrackDetail(track.trackId) }
                    )
                }
            }

            // Order is the point: their own records, then their own short-form
            // releases, then the ones they are a guest on. "Appears On" last
            // because it is the least of what an artist page is for.
            listOf(
                ArtistReleaseGrouping.ALBUMS,
                ArtistReleaseGrouping.SINGLES_AND_EPS,
                ArtistReleaseGrouping.APPEARS_ON
            ).forEach { section ->
                val rows = bySection[section].orEmpty()
                if (rows.isNotEmpty()) {
                    item(key = "head-$section") { SectionHeading(section) }
                    item(key = "row-$section") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(rows, key = { it.releaseId }) { release ->
                                ReleaseCard(release = release, onClick = { onOpenAlbum(release.releaseId) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInfo) {
        ArtistInfoSheet(
            name = header?.artistName ?: "Unknown Artist",
            description = header?.description,
            extract = cached?.wikipediaExtract,
            articleUrl = cached?.wikipediaUrl,
            loading = bioLoading,
            stats = header?.let { statsLine(it.releaseCount, it.trackCount, it.playCount) },
            onDismiss = { showInfo = false }
        )
    }
}

// ── Header ────────────────────────────────────────────────

@Composable
private fun ArtistHero(
    /** Looked-up portrait, or null if none has been found. */
    portraitModel: Any?,
    /** Album art to stand in when there is no portrait, or it will not load. */
    albumArtModel: Any?,
    name: String,
    stats: String?,
    bio: String?,
    bioIsWikipedia: Boolean,
    onExpandBio: () -> Unit,
    onBack: () -> Unit
) {
    val body = bio?.trim().orEmpty()
    // Set by the layout pass: only offer "View more" when there is more.
    var truncated by remember(body) { mutableStateOf(false) }

    // Grows for the blurb rather than letting it crowd the photo. Without this
    // the text block eats the bottom half of a portrait and the artist ends up
    // cropped at the chin.
    val height = if (body.isEmpty()) 320.dp else 400.dp

    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            /*
             * Dissolve the bottom edge into whatever is behind it.
             *
             * The hero was the only opaque thing on a screen made of glass over
             * the user's wallpaper: it ended in near-black at an exact pixel and
             * the wallpaper resumed on the next one, which read as two designs
             * meeting rather than one surface.
             *
             * An alpha fade rather than a gradient toward a colour, because
             * there is no colour to fade to — the background is the wallpaper,
             * and it is different for everyone. DstIn multiplies the hero's
             * alpha by the gradient, so the photo, the scrim and the shadows all
             * thin out together and the wallpaper comes through underneath.
             *
             * Offscreen compositing is what makes that work: without it the
             * blend applies against the window rather than against this layer,
             * and the fade paints as a grey band.
             */
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        // Untouched until well past the text, then out.
                        0f to Color.Black,
                        FADE_START to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        // Same three tiers as AgArtistAvatar, and the same reason for the sticky
        // failure flag: tier one points at Commons, so a deleted file or an
        // offline cache miss must drop to album art rather than leave the hero
        // empty. Retrying the dead URL every recomposition would just re-fail.
        var portraitFailed by remember(portraitModel) { mutableStateOf(false) }
        val heroModel = portraitModel?.takeIf { !portraitFailed } ?: albumArtModel

        if (heroModel != null) {
            AsyncImage(
                model = heroModel,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                onError = { portraitFailed = true },
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(Modifier.matchParentSize().agGlass(RoundedCornerShape(0.dp)))
        }

        /*
         * Two scrims, not one.
         *
         * A single gradient dark enough for three lines of body text over a
         * bright portrait has to be dark so high up that it greys out the face.
         * The gradient below handles the transition and keeps the photo intact;
         * the solid-ish band under the text does the readability work, sized to
         * the text rather than to the image.
         */
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        // Enough for the back button on a white background.
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.26f to Color.Transparent,
                        0.44f to Color.Black.copy(alpha = 0.22f),
                        /*
                         * Dark before the text, not alongside it.
                         *
                         * The text block runs from about 54% to 92% of the
                         * hero, and the previous ramp was still at 0.30 alpha
                         * where the name begins — fine over a portrait that is
                         * already dark at the bottom, useless over a bright
                         * album cover, which is what an artist without a
                         * portrait falls back to. The ramp now finishes its
                         * climb *above* the first line rather than through it.
                         */
                        TEXT_TOP to Color.Black.copy(alpha = 0.74f),
                        0.78f to Color.Black.copy(alpha = 0.90f),
                        // Held flat to the fade, so the dissolve below has an
                        // even band to work on rather than a moving target.
                        FADE_START to Color.Black.copy(alpha = 0.90f),
                        1f to Color.Black.copy(alpha = 0.55f)
                    )
                )
        )

        Box(Modifier.statusBarsPadding().padding(start = 8.dp, top = 4.dp)) {
            AgBareIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                tint = Color.White
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 34.dp)
        ) {
            Text(
                text = name,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 30.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // A drop shadow as well as the scrim: a gradient cannot know
                // that this particular portrait is white exactly where the
                // descenders fall.
                style = LocalTextStyle.current.copy(shadow = TEXT_SHADOW)
            )
            if (stats != null) {
                Text(
                    text = stats,
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.80f),
                    style = LocalTextStyle.current.copy(shadow = TEXT_SHADOW),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (body.isNotEmpty()) {
                Text(
                    text = body,
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = BIO_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { truncated = it.hasVisualOverflow },
                    style = LocalTextStyle.current.copy(shadow = TEXT_SHADOW),
                    modifier = Modifier.padding(top = 10.dp)
                )

                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (truncated) {
                        Text(
                            text = "View more",
                            fontFamily = NunitoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            // White rather than the wallpaper accent: this sits
                            // on a photograph, and an accent that reads well
                            // against the app's own surfaces can vanish here.
                            color = Color.White,
                            style = LocalTextStyle.current.copy(shadow = TEXT_SHADOW),
                            modifier = Modifier.agPressable(onClick = onExpandBio, pressScale = 0.96f)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (bioIsWikipedia) {
                        // Wikipedia text is CC BY-SA. Wherever the words go, the
                        // credit goes with them.
                        Text(
                            text = "From Wikipedia",
                            fontFamily = NunitoFamily,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.65f),
                            style = LocalTextStyle.current.copy(shadow = TEXT_SHADOW)
                        )
                    }
                }
            }
        }
    }
}

/** Enough to say what an artist is without burying the photo. */
private const val BIO_LINES = 3

/**
 * Where the hero starts dissolving, as a fraction of its height.
 *
 * Below every line of text: on a 400dp hero this leaves about 32dp of fade,
 * which is enough to read as a transition and not so much that the attribution
 * line goes translucent.
 */
private const val FADE_START = 0.92f

/**
 * Where the text block begins, as a fraction of the hero's height.
 *
 * Measured, not guessed: name, stats, three lines of body and the attribution
 * row come to roughly 150dp, which on a 400dp hero starts a little over half
 * way down. The scrim uses it to be dark *before* the first line rather than
 * still darkening through it.
 */
private const val TEXT_TOP = 0.56f

/**
 * Carried under every line of hero text.
 *
 * The scrim handles the average case; this handles the one where a portrait is
 * bright in exactly the place a letter lands.
 */
private val TEXT_SHADOW = Shadow(
    // Heavier than it needs to be over a dark photo, because the case that
    // fails is the other one: an album cover with its own large lettering
    // behind the artist name, where the scrim alone leaves white on near-white.
    color = Color.Black.copy(alpha = 0.85f),
    offset = Offset(0f, 1.5f),
    blurRadius = 9f
)

/** Library facts, not invented ones. */
private fun statsLine(releases: Int, tracks: Int, plays: Int): String = buildString {
    append("$releases ${if (releases == 1) "release" else "releases"}")
    append(" · $tracks ${if (tracks == 1) "track" else "tracks"}")
    if (plays > 0) append(" · $plays ${if (plays == 1) "play" else "plays"}")
}

// ── Pieces ────────────────────────────────────────────────

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 19.sp,
        color = AgPalette.TextPrimary,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp)
    )
}

@Composable
private fun ArtistPrimaryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = LocalWallpaperAccent.current
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = modifier
            .height(46.dp)
            .then(
                if (filled) Modifier.agGlassTinted(shape, tint = accent, opacity = 0.55f)
                else Modifier.agGlass(shape, opacity = 0.14f)
            )
            .agPressable(onClick = onClick, pressScale = 0.97f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = NunitoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
    }
}

@Composable
private fun ArtistAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = AgPalette.TextPrimary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .agPressable(onClick = onClick, pressScale = 0.94f)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontFamily = NunitoFamily, fontSize = 11.sp, color = AgPalette.TextSecondary)
    }
}

@Composable
private fun ReleaseCard(release: ArtistReleaseRow, onClick: () -> Unit) {
    Column(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .agPressable(onClick = onClick, pressScale = 0.97f)
    ) {
        if (release.artModel != null) {
            AsyncImage(
                model = release.artModel,
                contentDescription = release.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(150.dp).agAlbumTile(RoundedCornerShape(8.dp))
            )
        } else {
            Box(Modifier.size(150.dp).agGlass(RoundedCornerShape(8.dp)))
        }
        Text(
            text = release.title ?: "Unknown",
            fontFamily = NunitoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = AgPalette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = release.dateEpochMs?.let { yearOf(it).toString() } ?: "—",
            fontFamily = NunitoFamily,
            fontSize = 12.sp,
            color = AgPalette.TextMetadata
        )
    }
}

@Composable
private fun ArtistTrackRow(
    track: TimelineItemRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val playback = LocalPlayback.current
    val accent = LocalWallpaperAccent.current
    val isCurrent = playback.isCurrent(track.trackId)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .agPressable(onClick = onClick, onLongClick = onLongClick, pressScale = 0.99f)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        if (track.artModel != null) {
            AsyncImage(
                model = track.artModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(46.dp).agAlbumTile(RoundedCornerShape(6.dp))
            )
        } else {
            Box(Modifier.size(46.dp).agGlass(RoundedCornerShape(6.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.effectiveTitle ?: "Unknown Title",
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (isCurrent) accent else AgPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.effectiveAlbumTitle ?: "Unknown Album",
                fontFamily = NunitoFamily,
                fontSize = 12.sp,
                color = AgPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        NowPlayingRowIndicator(trackId = track.trackId, color = accent)
    }
}

private fun yearOf(epochMs: Long): Int {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epochMs
    return cal.get(Calendar.YEAR)
}

// ── Info sheet ────────────────────────────────────────────

/**
 * Who the artist is, and what your library holds of theirs.
 *
 * The bio is Wikipedia's, which is CC BY-SA — so the attribution and the link
 * back are part of the feature rather than decoration on it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ArtistInfoSheet(
    name: String,
    description: String?,
    extract: String?,
    articleUrl: String?,
    loading: Boolean,
    stats: String?,
    onDismiss: () -> Unit
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }

    AgModalSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = name,
                fontFamily = NunitoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                color = AgPalette.TextPrimary
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description.replaceFirstChar { it.uppercase() },
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    color = AgPalette.TextMetadata,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (stats != null) {
                Text(
                    text = stats,
                    fontFamily = NunitoFamily,
                    fontSize = 13.sp,
                    color = AgPalette.TextSecondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            when {
                !extract.isNullOrBlank() -> {
                    Text(
                        text = extract,
                        fontFamily = NunitoFamily,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = AgPalette.TextPrimary,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (expanded) "View less" else "View more",
                            fontFamily = NunitoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = LocalWallpaperAccent.current,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .agPressable(onClick = { expanded = !expanded }, pressScale = 0.96f)
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        // Required attribution, not a nicety: the text is CC BY-SA.
                        Text(
                            text = "From Wikipedia",
                            fontFamily = NunitoFamily,
                            fontSize = 12.sp,
                            color = AgPalette.TextMetadata,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .agPressable(
                                    onClick = { articleUrl?.let { uriHandler.openUri(it) } },
                                    enabled = articleUrl != null,
                                    pressScale = 0.96f
                                )
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        )
                    }
                }
                loading -> {
                    Text(
                        text = "Looking this up\u2026",
                        fontFamily = NunitoFamily,
                        fontSize = 13.sp,
                        color = AgPalette.TextMetadata
                    )
                }
                else -> {
                    Text(
                        text = "No Wikipedia article for this artist.",
                        fontFamily = NunitoFamily,
                        fontSize = 13.sp,
                        color = AgPalette.TextMetadata
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

# VisiBeat Task 002: Visual Polish — Longhorn/Frutiger Aero Design System

## Design Philosophy

VisiBeat's aesthetic is rooted in Microsoft's Longhorn/Avalon era (2003–2005) and the broader Frutiger Aero movement: maximalist transparency, luminous glass materials, warm organic gradients, rounded humanist typography, and UI elements that feel like they have physical weight and depth. The wallpaper is the soul of the app — every glass surface should breathe with the colors behind it.

Key references for the implementer:
- Windows Longhorn build 4074 "Plex" theme and Avalon/WPF demo videos
- Frutiger Aero: translucent panels, specular highlights, gloss, warm saturated gradients
- The UI should feel like album art is floating in luminous glass display cases suspended over the wallpaper

**Guiding principle:** The app should feel warm, alive, and tactile. Not flat. Not cold. Not minimal. Generous with light, generous with depth, generous with motion.

---

## Part 1: Design System Foundation (music-ui/design/)

### 1A. Typography — Replace system font with a bundled humanist typeface

**File:** `music-ui/src/main/java/com/visibeat/musicui/design/Theme.kt`

**Problem:** `FontFamily.SansSerif` everywhere makes the app feel generic. Longhorn used Segoe UI — warm, round, humanist.

**Solution:** Bundle **Nunito** (Google Fonts, OFL license). It has the warmth and roundness of Segoe with excellent weight range.

**Steps:**
1. Download Nunito font files (Regular, SemiBold, Bold, ExtraBold) as `.ttf` from Google Fonts
2. Place in `music-ui/src/main/res/font/` (create directory if needed)
3. Define the FontFamily in Theme.kt:

```kotlin
val NunitoFamily = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)
```

4. Replace ALL `FontFamily.SansSerif` references in `AgTypography` with `NunitoFamily`
5. Adjust sizes: the spine year text (`VerticalStackText` in `AgTimeline.kt`) should use `FontWeight.ExtraBold` for presence. Section headers should use `FontWeight.Bold`. Body text stays `Normal`.

**What NOT to change:** Don't change the vertical stacking logic in `VerticalStackText` — just the font family and weight it uses.

### 1B. Glass Material — Multi-layer Aero glass

**File:** `music-ui/src/main/java/com/visibeat/musicui/design/Glass.kt`

**Problem:** `agGlass` is a single 8% white fill with a faint border. Real Aero glass had multiple translucent layers, a bright specular top edge, and enough visual density to feel like a physical material.

**Rewrite `agGlass`:**

```kotlin
fun Modifier.agGlass(
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    opacity: Float = 0.10f
) = this
    // Base translucent fill
    .background(Color.White.copy(alpha = opacity), shape = shape)
    // Inner top highlight (specular edge — the "Aero shine")
    .drawBehind {
        val highlightBrush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.25f),
            0.4f to Color.White.copy(alpha = 0.05f),
            1f to Color.Transparent
        )
        drawRoundRect(
            brush = highlightBrush,
            size = size,
            cornerRadius = CornerRadius(16.dp.toPx())
        )
    }
    // Outer border: bright top fading to subtle bottom
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.35f),
            0.5f to Color.White.copy(alpha = 0.10f),
            1f to Color.White.copy(alpha = 0.03f)
        ),
        shape = shape
    )
    // Subtle inner shadow at bottom for depth
    .drawBehind {
        val shadowBrush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.7f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.08f)
        )
        drawRoundRect(
            brush = shadowBrush,
            size = size,
            cornerRadius = CornerRadius(16.dp.toPx())
        )
    }
```

**Update `AgSurface`:** Increase default blur from 12.dp to 20.dp. The glass should blur the wallpaper beneath it into a soft color wash, not keep it sharp.

**Update `AgAcrylicSurface`:** This is used for the now-playing overlay. Keep it denser but with the same multi-layer treatment. Increase blur to 32.dp. The acrylic noise effect (horizontal stripes) is clever but too mechanical — consider replacing with a very subtle dot pattern or removing it entirely in favor of pure blurred translucency. The 0.3f black tint layer is too dark; reduce to 0.15f so the wallpaper color bleeds through more.

### 1C. Palette — Warm-reactive, not cold-fixed

**File:** `music-ui/src/main/java/com/visibeat/musicui/design/Theme.kt`

**Problem:** `AgPalette` is a cold dark-space theme (DeepSpace, Graphite, MistBlue) but the app runs on warm wallpapers. The glass panels fight the wallpaper instead of harmonizing with it.

**Solution:** The palette should remain as a set of semantic tokens, but the glass border/accent colors should be derived from the wallpaper at runtime. This is already partially possible because `PlaybackColors` extracts a palette from the wallpaper bitmap.

**Steps:**
1. In `MainActivity.kt`, the wallpaper palette is already extracted for the visualizer. Expose the dominant color and its complement as `CompositionLocal` values that can be consumed by the design system:

```kotlin
val LocalWallpaperDominant = staticCompositionLocalOf { Color.White }
val LocalWallpaperAccent = staticCompositionLocalOf { Color(0xFF1D4ED8) }
```

2. Wrap the app content in a provider that sets these from the wallpaper extraction.

3. In `AgPalette`, add computed properties that read from these locals for accent-sensitive usages (chip selection color, spine glow, album tile border on selection).

4. The glass border's top highlight should mix in a hint of the wallpaper dominant color — e.g. `Color.White.copy(alpha = 0.25f).compositeOver(wallpaperDominant.copy(alpha = 0.05f))`.

**What NOT to change:** Keep `TextPrimary`, `TextSecondary`, `TextMetadata` as fixed values — text readability should not shift with the wallpaper.

### 1D. Timeline Spine — Physical presence

**File:** `music-ui/src/main/java/com/visibeat/musicui/design/AgTimeline.kt`

**Problem:** The spine is a barely-visible 2px white line at 18% alpha. It should feel like a luminous rail.

**Upgrade `AgTimelineSpine`:**
- Increase default stroke width to 3f
- Increase alpha to 0.30f
- Add a soft glow behind the line (draw a blurred wider rect behind the main line at ~8% alpha, or use a radial gradient centered on the line)
- Use a dashed pattern instead of solid — short dashes (8dp on, 6dp off) give it a "runway marker" quality that fits the timeline metaphor
- The dash color should be `Color.White.copy(alpha = 0.25f)` with a subtle glow

**Upgrade `VerticalStackText`:**
- Use `FontWeight.ExtraBold` for year labels on the spine
- Increase letter spacing slightly for the stacked characters
- Add a very subtle text shadow (via `drawBehind` or `graphicsLayer { shadowElevation }`) so the year text pops off the wallpaper

---

## Part 2: Timeline Views (music-ui/timeline/)

### 2A. Year Timeline View — Quarter card refinements

**File:** `music-ui/src/main/java/com/visibeat/musicui/timeline/year/YearTimelineView.kt`

**Problems:**
- All quarter cards are fixed 100.dp regardless of content
- Empty months show just the month abbreviation at low contrast
- The 2x2 album grid inside MonthCardInQuarter has hard-coded sizing

**Changes:**
1. **Variable card sizing:** Cards with album art should be slightly larger (110-120dp) while empty quarters can be smaller (80-90dp). Use `animateDpAsState` so cards smoothly resize as data loads.

2. **Empty month treatment:** Instead of low-alpha text on glass, show a subtle frosted placeholder with a very light "∅" or just the month name at higher contrast. The empty cards currently look broken, not intentionally empty.

3. **Corner radius:** Increase from 12.dp to 16.dp on the quarter cards for the Aero-rounded feel.

4. **Card entry animation:** When the year scrolls into view, stagger the quarter cards' entry. Q1 appears first, then Q2 (offset by ~50ms), Q3, Q4. Use `animateItemPlacement` on the LazyColumn plus individual `AnimatedVisibility` with cascading delays per quarter.

### 2B. Month Timeline View — Expandable grid polish

**File:** `music-ui/src/main/java/com/visibeat/musicui/timeline/month/MonthTimelineView.kt`

**Problems:**
- Long-press to expand feels undiscoverable — no visual hint
- Expansion snaps between 80dp and 200dp — could use spring animation
- Tap-outside-to-collapse works but there's no visual feedback

**Changes:**
1. **Discoverable expansion hint:** When a month card has more albums than can fit in the 2x2 grid, show a subtle "+N" badge in the bottom-right corner (e.g. "+4" if there are 6 total albums). Style it as a small frosted pill.

2. **Spring expansion animation:** Replace the `tween(300)` with `spring(dampingRatio = 0.7f, stiffness = 300f)` for the size and corner radius animations. This gives the expansion a physical, bouncy feel that matches the Aero aesthetic.

3. **Collapse feedback:** When tapping outside to collapse, briefly flash the card's border brighter (a 150ms pulse to white at 0.3f alpha) before shrinking, so the user gets tactile feedback.

4. **Album tile spacing:** Inside the expanded 5x3 grid, increase padding from 1.dp to 2.dp between tiles. The tiles are too cramped when expanded.

### 2C. Day Timeline View — Card and arrow improvements

**File:** `music-ui/src/main/java/com/visibeat/musicui/timeline/day/DayTimelineView.kt`

**Problems:**
- The arrow text (" ← " / " → ") connecting cards to the spine is a plain Text composable — it should be a graphical element
- The scrollable track list inside cards (`verticalScroll`) nested inside a `LazyColumn` is a scroll-inside-scroll UX problem
- Day cards with album art (`DayReleaseCard`) don't use the glass material consistently

**Changes:**
1. **Replace arrow text with graphical connector:** Draw a thin line (1-2dp) from the card edge to the spine node, with a small dot at each end. Use Canvas drawing within the Row, matching the spine's style. The connector should be `Color.White.copy(alpha = 0.20f)` and slightly curved if possible (quadratic bezier).

2. **Remove nested scroll:** Instead of making the track list scrollable inside the card, limit to 5 visible tracks and show a "... and N more" text that opens the feed on tap. This eliminates the nested scroll conflict with the main LazyColumn.

3. **Consistent glass on day cards:** `DayReleaseCard` uses `AgCard` which uses `AgSurface`. Verify the glass material is consistently applied — the card should be translucent with the wallpaper showing through, not opaque.

### 2D. Chips Bar — Aero-styled filter chips

**File:** `music-ui/src/main/java/com/visibeat/musicui/timeline/TimelineBucketsScreen.kt` and `music-ui/src/main/java/com/visibeat/musicui/design/AgChips.kt`

**Problem:** The chips ("Bucket: YEAR", "Newest", "Quality: Any") look like generic Material3 chips, not part of the Longhorn aesthetic.

**Changes to `AgChip`:**
1. Apply the glass material: use `agGlass` with `RoundedCornerShape(999.dp)` and a slightly higher opacity (0.12f) so they're readable over the wallpaper.
2. Selected state should glow — add a subtle colored border (using the wallpaper accent color from `LocalWallpaperAccent`) instead of the current `MistBlue` tint.
3. Text inside chips should use the Nunito font at `FontWeight.SemiBold`.
4. Add a subtle press animation: `graphicsLayer { scaleX = 0.95f; scaleY = 0.95f }` on press, springing back on release.

---

## Part 3: Feed Views — Cohesive subpage design (music-ui/feed/)

### 3A. Core Problem

The feed views (`DayFeedView`, `MonthFeedView`, `YearFeedView`) are the weakest part of the app visually. They use stock Material3 `Scaffold`, `TopAppBar`, `FloatingActionButton`, and `Surface` — none of which match the glass/timeline aesthetic. Navigating from the timeline into a feed feels like entering a completely different app.

### 3B. Shared Feed Shell — Replace Scaffold with glass-over-wallpaper

**File:** `music-ui/src/main/java/com/visibeat/musicui/feed/shared/FeedShared.kt`

Create a new shared `FeedShell` composable that replaces Scaffold in all three feed views:

```kotlin
@Composable
fun FeedShell(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    albumArtUri: String? = null,
    onPlayAll: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // The feed should NOT have an opaque toolbar or background.
    // The wallpaper should show through everywhere.
    // Content floats in glass panels over the wallpaper.
    
    Column(Modifier.fillMaxSize()) {
        // Custom top bar: glass pill with back arrow and title
        // NOT a Material TopAppBar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button as a frosted circle
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .agGlass(CircleShape, opacity = 0.12f)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowBack, "Back", tint = AgPalette.TextPrimary)
            }
            
            Spacer(Modifier.width(12.dp))
            
            Column {
                Text(title, style = ..., fontWeight = FontWeight.Bold, color = AgPalette.TextPrimary)
                if (subtitle != null) {
                    Text(subtitle, style = ..., color = AgPalette.TextSecondary)
                }
            }
            
            Spacer(Modifier.weight(1f))
            
            // Play all button as frosted circle (replaces FAB)
            if (onPlayAll != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .agGlass(CircleShape, opacity = 0.12f)
                        .clickable { onPlayAll() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, "Play All", tint = AgPalette.TextPrimary)
                }
            }
        }
        
        content()
    }
}
```

**Replace Scaffold** in `DayFeedView`, `MonthFeedView`, and `YearFeedView` with `FeedShell`. Remove the `FloatingActionButton`, `TopAppBar`, and all opaque `Surface` usage.

### 3C. Feed Row — Glass track rows

**File:** `music-ui/src/main/java/com/visibeat/musicui/feed/shared/FeedShared.kt`

**Problem:** `FeedRow` is a plain clickable Row with no visual treatment. It should feel like a glass list item.

**Changes:**
1. Wrap each row in a subtle glass panel: `agGlass(RoundedCornerShape(12.dp), opacity = 0.06f)` with `Modifier.padding(horizontal = 12.dp, vertical = 3.dp)`
2. Add small album art thumbnail (32x32dp) to the left of each track row. The art URI pattern is `content://media/external/audio/albumart/${row.mediaStoreAlbumId}`.
3. Replace the `Divider()` between rows with spacing (4.dp gap between glass cards is cleaner than dividers in this aesthetic).
4. Artist name should be tappable (it already is) but styled with the wallpaper accent color instead of `MaterialTheme.colorScheme.primary`.

### 3D. Feed Chips Row — Match timeline chips

**File:** `music-ui/src/main/java/com/visibeat/musicui/feed/shared/FeedShared.kt`

**Problem:** Feed uses `AssistChip` (Material3) while the timeline uses custom `AgChip`. They look completely different.

**Solution:** Replace `AssistChip` in `FeedChipsRow` with `AgChip`. Same component, same style, cohesive across the app.

### 3E. Album Art Hero — Glass-framed, not edge-to-edge

**All three feed views** currently show album art as a 200dp edge-to-edge image with `agAlbumTile(RoundedCornerShape(0.dp))`. This looks like a generic media app.

**Change:** 
1. Reduce width to leave 16dp margins on each side
2. Apply `RoundedCornerShape(20.dp)` for rounded corners
3. Apply `agAlbumTile` with the rounded shape
4. Add a subtle drop shadow beneath (`graphicsLayer { shadowElevation = 8.dp }`)
5. Reduce height from 200.dp to 180.dp
6. If no album art is available, show a glass panel with the album title in large text instead of nothing

### 3F. Section Headers in Feeds

**Problem:** Day headers in MonthFeedView and month headers in YearFeedView use basic Text or opaque Surface. They should use the spine aesthetic.

**Change:** Replace section headers with a horizontal version of the spine concept:
- A thin horizontal glass rule (1dp height, full width) with the label in a frosted pill centered on it
- Similar to how `CenterSpineMonthPill` works in ArtistAlbumTimelineScreen, but horizontal

---

## Part 4: Artist Screen — Full redesign for cohesion

**File:** `music-ui/src/main/java/com/visibeat/musicui/artist/ArtistAlbumTimelineScreen.kt`

### Problems:
- Uses stock `Scaffold`/`TopAppBar` (same issue as feeds)
- `AlbumBucketCard` uses opaque `Surface` instead of glass
- `CenterSpineMonthPill` uses opaque `Surface` with a manually drawn black line — doesn't match the main timeline's `AgTimelineSpine`
- `ArtistYearHeaderIfNeeded` has placeholder content ("Jan * Feb") that looks unfinished
- The `Side` enum is redefined here (duplicates the one in `TimelineShared.kt`)

### Changes:
1. **Replace Scaffold** with `FeedShell` (from Part 3B)
2. **Replace opaque Surface** in `AlbumBucketCard` with `AgSurface` (glass material)
3. **Replace `CenterSpineMonthPill`** with the shared `CenterSpine` from `TimelineShared.kt` — use the same `AgTimelineSpine` and `VerticalStackText`
4. **Fix `ArtistYearHeaderIfNeeded`:** Remove the "Jan * Feb" placeholder. Use the same year header pattern as `MonthYearHeaderIfNeeded` from `MonthTimelineView` — centered vertically-stacked year text with spine segments above and below.
5. **Remove duplicate `Side` enum.** Import from `com.visibeat.musicui.timeline.shared.Side`.
6. **Add album art** to `AlbumBucketCard`. Currently it's text-only. Each album row in the card should have a small (28dp) album art thumbnail if `mediaStoreAlbumId` is available. This requires the `AlbumCardRow` data class to include `mediaStoreAlbumId` — check `ArtistAlbumTimelineDao` and `ArtistAlbumTimelineEngine` to see if this field is available. If not, add it.

---

## Part 5: Gesture Improvements

### 5A. Pinch-to-zoom — Visual feedback

**File:** `music-ui/src/main/java/com/visibeat/musicui/timeline/TimelineBucketsScreen.kt`

**Problem:** Pinch-to-zoom between DAY/MONTH/YEAR works functionally but gives zero visual feedback. The user pinches and the view just snaps to a different bucket.

**Changes:**
1. During an active pinch gesture (when `pinchActive == true`), apply a scale transform to the entire timeline content. Scale should track `zoomRatio` in real-time (clamped to 0.8f-1.2f range) using `graphicsLayer { scaleX = ...; scaleY = ... }`.
2. When the zoom threshold is crossed (ratio < 0.6 or > 1.5), briefly flash the content to white at 5% opacity (150ms) as a "level transition" indicator.
3. After the zoom completes, animate the scale back to 1.0f with a spring.

### 5B. Scroll-to-year quick jump

**File:** `music-ui/src/main/java/com/visibeat/musicui/timeline/TimelineBucketsScreen.kt`

**Problem:** With a large library, scrolling through years is tedious. There's no way to jump quickly.

**Add:** A semi-transparent year scrubber on the right edge of the screen (only visible during fast scroll or after 2+ seconds of continuous scrolling). This is a vertical column of year labels that the user can tap to jump to that year.

Implementation approach:
1. Track scroll velocity from `listState`
2. When velocity exceeds a threshold, fade in a year scrubber overlay (aligned to `Alignment.CenterEnd`)
3. The scrubber shows stacked year labels (from the bucket data)
4. Tapping a year triggers `listState.animateScrollToItem()` to jump to that year's first bucket
5. The scrubber fades out after 2 seconds of no scroll activity
6. Style: glass panel (`agGlass`) with compact year labels in `Nunito SemiBold`

### 5C. Track row swipe gestures in feeds

**Files:** `music-ui/src/main/java/com/visibeat/musicui/feed/shared/FeedShared.kt`

**Add swipe-to-play on feed track rows:**
1. Swipe right on a `FeedRow` to immediately play that track (calls `onOpenTrack`)
2. Visual feedback: as the user swipes, a play icon slides in from the left edge with a green-tinted glass background
3. Use `SwipeToDismissBoxState` from Material3 or implement with `Modifier.draggable`
4. The threshold should be ~80dp of horizontal drag before triggering
5. After triggering, the row should spring back to its original position

### 5D. Double-tap on timeline cards to play

**Files:** All timeline views

**Add:** Double-tapping a timeline card (in any view: day, month, year) should play the first track in that bucket. This makes the timeline a launchpad for playback, not just navigation.

Implementation:
1. In the various card click handlers, use `combinedClickable(onDoubleClick = { ... })` alongside the existing `onClick`
2. Single tap behavior remains unchanged (opens feed or expands)
3. Double tap triggers `onOpenTrack(firstTrackId)` where `firstTrackId` is derived from the preview items for that bucket

---

## Part 6: Now Playing Overlay — Glass consistency

### 6A. NowPlayingExpanded

**File:** `music-ui/src/main/java/com/visibeat/musicui/playback/NowPlayingExpanded.kt`

**Problem:** Uses `AgAcrylicSurface` which is good, but the content inside (track title, artist, controls) doesn't match the glass aesthetic.

**Changes:**
1. The play/pause button already uses `agGlass(CircleShape)` — keep that
2. Skip previous/next icons should also be in frosted circles (smaller, 44dp) instead of plain `IconButton`
3. The "View Album Feed" button should be `agGlass(RoundedCornerShape(12.dp))` instead of `ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))`
4. Track title and artist text should use Nunito (handled by the global typography change)

### 6B. Track Detail Bottom Sheet

**File:** `music-ui/src/main/java/com/visibeat/musicui/track/TrackDetailBottomSheet.kt`

**Problem:** Uses stock Material3 `ModalBottomSheet` with no glass treatment.

**Changes:**
1. Set the sheet's `containerColor` to `Color.Black.copy(alpha = 0.3f)` so the wallpaper shows through
2. Apply a subtle blur behind the sheet (if possible with `ModalBottomSheet` — may require a custom implementation)
3. `OutlinedTextField` fields should use glass-styled borders instead of default Material3 outlines. Set `colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.White.copy(alpha = 0.15f), focusedBorderColor = wallpaperAccent)`.
4. The "Save" button should use `agGlass` styling
5. Section dividers should be `Color.White.copy(alpha = 0.1f)` instead of the default Material color

---

## Part 7: Transition Animations Between Screens

**File:** `music-ui/src/main/java/com/visibeat/app/MainActivity.kt`

**Problem:** Screen transitions (Timeline → Feed, Timeline → Artist) are instant cuts. There's no animation connecting the two states.

**Changes:**
1. Wrap the `when (screen)` block in `AnimatedContent` with a custom `transitionSpec`:
   - Timeline → Feed: slide up from bottom + fade in (300ms, `AgMotion.EnteringEasing`)
   - Feed → Timeline (back): slide down + fade out (220ms, `AgMotion.ExitingEasing`)
   - Timeline → Artist: slide in from right + fade (300ms)
   - Artist → Timeline (back): slide out to right + fade (220ms)

2. Use the motion constants from `AgMotion` for duration and easing consistency.

---

## What NOT to Change

- **Data layer** — No changes to `core-db`, `music-db`, `ingest`, `musicbrainz`, or `view-engine` modules (except possibly adding `mediaStoreAlbumId` to `AlbumCardRow` if needed for artist screen album art)
- **TagExtractor.kt** — Just completed in Task 001, do not modify
- **PlaybackManager.kt** — Functional, not a visual concern
- **MediaPlaybackService.kt** — Backend, not visual
- **NowPlayingSlab.kt** — The 3D bouncing album will be addressed in a separate task. Do not modify.
- **Navigation logic** — The `Screen` sealed class and backstack in `MainActivity` should not change structurally. Only wrap transitions in animation.
- **Database schema** — No migrations

## Build Verification

After all changes:
1. The app should build without errors on `compileSdk = 34`
2. The Nunito font should render in all text across the app
3. Glass panels should show the wallpaper behind them (blurred)
4. Feed views should feel visually continuous with the timeline (no "different app" feeling)
5. Pinch-to-zoom should show visual scale feedback during the gesture
6. All existing functionality (playback, navigation, ingest) must still work

## Implementation Order

Suggested order to minimize merge conflicts and allow incremental testing:

1. Typography (1A) — global impact, easy to verify
2. Glass material (1B) — core visual change, everything builds on this
3. Spine upgrade (1D) — small, self-contained
4. Palette locals (1C) — enables wallpaper-reactive accents
5. Timeline view refinements (2A-2D) — incremental per view
6. Feed shell + shared components (3B-3F) — biggest visual cohesion win
7. Artist screen (Part 4) — apply patterns established in feeds
8. Gesture improvements (5A-5D) — additive, won't break existing behavior
9. Now playing + bottom sheet (6A-6B) — polish pass
10. Screen transitions (Part 7) — final flourish

# VisiBeat Task 003: NowPlayingSlab — Longhorn 3D Album Cube with Bass-Reactive Motion

## Visual Reference

The target is Microsoft's Longhorn media player tile (circa 2003-2005 Avalon demos): a thick, physically chunky 3D cube with album art on the front face and visible colored side faces that catch light. When the cube rotates, you see the left/right edge and top/bottom edge as distinct lit surfaces — not a drop shadow, but actual rendered side geometry. A soft reflection sits beneath the cube on whatever surface it's resting on.

**Key difference from the reference:** The Longhorn tile used a fixed gold/amber metallic material for the side faces. VisiBeat's cube should derive its side-face color from the current album art's dominant palette. A dark album gets dark rich sides, a bright album gets warm saturated sides. The cube belongs to its album.

## Current State

`NowPlayingSlab.kt` currently has:
- A fixed-interval breathing pulse (`infiniteTransition`, 600ms sine wave) — **not music-reactive**
- A slow tilt animation (2000ms linear sweep) — **not music-reactive**
- A fake extrusion (loop of offset `drawRoundRect` calls) — **doesn't track rotation**
- A visualizer overlay drawing FFT bars on top of the album art — **indexing issues, magic normalization**
- Visualizer colors derived from the wallpaper — **should come from album art**

## Goal

Replace the entire slab with a physically-modeled 3D album cube that:
1. Renders three visible faces (front + two side faces) that track rotation correctly
2. Has bass-reactive bounce and tilt driven by live FFT data
3. Derives its side-face color and visualizer palette from the current album art
4. Casts a soft reflection beneath itself
5. Feels like a chunky physical object with weight and momentum
6. Is freely draggable — the user can reposition the tile anywhere on screen
7. Persists its position across recompositions (and ideally across sessions)

## Architecture

### DO NOT MODIFY these files:
- `PlaybackManager.kt` — provides `fftData: StateFlow<ByteArray>` and `state: StateFlow<PlaybackState>` (these are correct and unchanged)
- `NowPlayingExpanded.kt` — calls `NowPlayingSlab` as a child, don't change the call site
- Any data layer files

### MODIFY:
- `music-ui/src/main/java/com/visibeat/musicui/playback/NowPlayingSlab.kt` — full rewrite
- `music-ui/src/main/java/com/visibeat/musicui/design/PlaybackColors.kt` — change palette source from wallpaper to album art
- `app/src/main/java/com/visibeat/app/MainActivity.kt` — change the slab's hosting from a fixed `Alignment.BottomCenter` Box to a free-floating draggable container that remembers its position

---

## Part 1: Album Art Color Extraction

### PlaybackColors.kt Changes

Currently `generateVisualizerPalette()` is called from `MainActivity` with the wallpaper bitmap. The slab needs colors from the *album art*, not the wallpaper.

**Add a new function** (keep the existing one for backward compatibility):

```kotlin
/**
 * Extract a palette from album art for the 3D cube.
 * Returns: CubePalette with dominant color for side faces,
 * muted dark variant for shadow faces, and visualizer colors.
 */
data class CubePalette(
    val sideFacePrimary: Color,    // Dominant saturated color — visible side faces
    val sideFaceShadow: Color,     // Darker variant — shadowed side face
    val specularHighlight: Color,  // Lighter variant — edge highlights
    val visualizerColors: List<Color>
)

fun generateCubePalette(bitmap: Bitmap?): CubePalette {
    if (bitmap == null) return defaultCubePalette

    val palette = Palette.from(bitmap).generate()

    // Prefer vibrant, fall back to dominant
    val baseColor = palette.getVibrantColor(
        palette.getDominantColor(0xFF8B7355.toInt()) // warm brown fallback
    ).let { Color(it) }

    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(baseColor.toArgb(), hsv)

    val sidePrimary = Color.hsv(hsv[0], (hsv[1] * 0.9f).coerceIn(0.3f, 0.85f), (hsv[2] * 0.8f).coerceIn(0.3f, 0.8f))
    val sideShadow = Color.hsv(hsv[0], (hsv[1] * 0.7f).coerceIn(0.2f, 0.7f), (hsv[2] * 0.5f).coerceIn(0.15f, 0.5f))
    val specular = Color.hsv(hsv[0], (hsv[1] * 0.4f).coerceIn(0.05f, 0.4f), (hsv[2] * 1.3f).coerceIn(0.7f, 1f))

    val vizColors = generateHarmonicColors(Color(palette.getVibrantColor(baseColor.toArgb())), 5)

    return CubePalette(sidePrimary, sideShadow, specular, vizColors)
}

private val defaultCubePalette = CubePalette(
    sideFacePrimary = Color(0xFF8B7355),
    sideFaceShadow = Color(0xFF5C4D3C),
    specularHighlight = Color(0xFFD4C5A9),
    visualizerColors = listOf(Color(0xFF6200EE), Color(0xFF03DAC6), Color(0xFFBB86FC), Color(0xFF3700B3), Color(0xFF018786))
)
```

### Album Art Bitmap Access

The slab needs a `Bitmap` of the current album art to extract colors. The album art URI is available as `content://media/external/audio/albumart/${mediaStoreAlbumId}`.

In `NowPlayingSlab`, load the bitmap using Coil's `ImageRequest` or `context.contentResolver.openInputStream()` and pass it to `generateCubePalette()`. Cache the result keyed on `mediaStoreAlbumId` so it doesn't re-extract on every recomposition.

```kotlin
val cubePalette = remember(state.currentTrack?.mediaStoreAlbumId) {
    val albumId = state.currentTrack?.mediaStoreAlbumId
    if (albumId != null) {
        try {
            val uri = Uri.parse("content://media/external/audio/albumart/$albumId")
            val stream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(stream)
            stream?.close()
            PlaybackColors.generateCubePalette(bitmap)
        } catch (e: Exception) {
            PlaybackColors.generateCubePalette(null)
        }
    } else {
        PlaybackColors.generateCubePalette(null)
    }
}
```

---

## Part 2: Bass Energy Extraction from FFT

Replace the fixed-interval animations with real music reactivity.

### Bass Energy Calculation

```kotlin
/**
 * Extract bass energy (0f-1f) from FFT data.
 * Uses bins 1-4 (sub-bass + kick drum range, roughly 40-200Hz).
 * Normalizes against a running peak to adapt to different volume levels.
 */
@Composable
fun rememberBassEnergy(fftData: ByteArray): Float {
    var runningPeak by remember { mutableFloatStateOf(1f) }

    return remember(fftData) {
        if (fftData.size < 10) return@remember 0f

        var sum = 0f
        val binCount = minOf(4, fftData.size / 2 - 1) // Safety: don't exceed array bounds
        for (i in 1..binCount) {
            val realIdx = i * 2
            val imIdx = i * 2 + 1
            if (imIdx >= fftData.size) break

            val r = fftData[realIdx].toFloat()
            val im = fftData[imIdx].toFloat()
            sum += sqrt(r * r + im * im)
        }

        val raw = sum / binCount.coerceAtLeast(1)

        // Adaptive normalization: track running peak with slow decay
        if (raw > runningPeak) {
            runningPeak = raw
        } else {
            runningPeak *= 0.998f // Slow decay so it adapts to quiet passages
            runningPeak = runningPeak.coerceAtLeast(1f)
        }

        (raw / runningPeak).coerceIn(0f, 1f)
    }
}
```

### Spring-Driven Scale and Tilt

```kotlin
// Bass drives scale: 1.0 at silence, up to ~1.08 on hard kicks
val targetScale = 1f + (bassEnergy * 0.08f)
val animatedScale by animateFloatAsState(
    targetValue = targetScale,
    animationSpec = spring(
        dampingRatio = 0.55f,  // Quick punch with one natural overshoot
        stiffness = 400f       // Responsive but not twitchy
    ),
    label = "cubeScale"
)

// Bass drives tilt: cube punches toward camera on transients
val targetTiltX = 12f + (bassEnergy * 6f)  // 12° base + up to 6° more on bass
val targetTiltY = 15f + (bassEnergy * 3f)  // Slight Y wobble

val animatedTiltX by animateFloatAsState(
    targetValue = targetTiltX,
    animationSpec = spring(dampingRatio = 0.6f, stiffness = 250f),
    label = "cubeTiltX"
)
val animatedTiltY by animateFloatAsState(
    targetValue = targetTiltY,
    animationSpec = spring(dampingRatio = 0.65f, stiffness = 200f),
    label = "cubeTiltY"
)
```

**The tiltY should also have a slow idle drift** when music is NOT playing (or paused) — a gentle 8-second sine sweep ±3° so the cube doesn't look dead when idle. Use `rememberInfiniteTransition` ONLY for this idle state, and cross-fade to bass-reactive tilt when playing.

---

## Part 3: 3D Cube Rendering

### Geometry Concept

The cube has three potentially visible faces depending on rotation:
- **Front face** — album art image (always visible)
- **Right side face** — visible when rotationY > 0 (cube turned to show right edge)
- **Bottom face** — visible when rotationX > 0 (cube tilted to show bottom edge)

For the rotation angles we're using (12-18° X, 12-18° Y), the right side and bottom face will always be partially visible as thin slivers — exactly like the Longhorn reference images.

### Cube Thickness

The cube should be approximately **12-16% as thick as it is wide**. For a 100dp-wide slab, the visual thickness should be ~14dp. This is the "chunky physical object" feel from the reference.

### Rendering Approach

Compose's `graphicsLayer` handles the perspective transform for the front face. The side faces need to be manually drawn in `drawBehind` to create the illusion of thickness. The key insight: the side faces are parallelograms whose shape depends on the current rotation angles and perspective.

```kotlin
val cubeSize = 100.dp
val thickness = 14.dp

Box(
    modifier = Modifier
        .size(cubeSize)
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
            rotationY = animatedTiltY
            rotationX = animatedTiltX
            cameraDistance = 12f * density
        }
        .drawBehind {
            // Draw side faces BEFORE the front face renders on top

            val thicknessPx = thickness.toPx()
            val radiansY = Math.toRadians(animatedTiltY.toDouble()).toFloat()
            val radiansX = Math.toRadians(animatedTiltX.toDouble()).toFloat()

            // Right side face (visible strip)
            // Width of visible strip = thickness * sin(rotationY)
            val rightStripWidth = (thicknessPx * sin(radiansY.toDouble())).toFloat().coerceAtLeast(0f)

            if (rightStripWidth > 0.5f) {
                // Draw as a vertical strip on the right edge
                // Use sideFacePrimary with a vertical gradient for lighting
                val rightBrush = Brush.horizontalGradient(
                    0f to cubePalette.sideFacePrimary,
                    0.6f to cubePalette.sideFacePrimary,
                    1f to cubePalette.sideFaceShadow
                )
                drawRect(
                    brush = rightBrush,
                    topLeft = Offset(size.width, 0f),
                    size = Size(rightStripWidth, size.height)
                )

                // Specular edge highlight on the leading edge
                drawLine(
                    color = cubePalette.specularHighlight.copy(alpha = 0.4f),
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Bottom face (visible strip)
            val bottomStripHeight = (thicknessPx * sin(radiansX.toDouble())).toFloat().coerceAtLeast(0f)

            if (bottomStripHeight > 0.5f) {
                val bottomBrush = Brush.verticalGradient(
                    0f to cubePalette.sideFacePrimary,
                    0.5f to cubePalette.sideFaceShadow,
                    1f to cubePalette.sideFaceShadow
                )
                drawRect(
                    brush = bottomBrush,
                    topLeft = Offset(0f, size.height),
                    size = Size(size.width + rightStripWidth, bottomStripHeight)
                )
            }

            // Corner where right and bottom meet
            if (rightStripWidth > 0.5f && bottomStripHeight > 0.5f) {
                drawRect(
                    color = cubePalette.sideFaceShadow,
                    topLeft = Offset(size.width, size.height),
                    size = Size(rightStripWidth, bottomStripHeight)
                )
            }
        }
        .clickable { onClick() },
    contentAlignment = Alignment.Center
) {
    // Front face: album art with gloss overlay
    // ... (see Part 4)
}
```

**IMPORTANT:** The side face drawing happens in `drawBehind` which draws BEFORE the content. However, because `graphicsLayer` applies the perspective transform to the entire Box including `drawBehind`, the side faces will be transformed along with the front face. This is actually correct — they'll appear to be part of the same 3D object.

**NOTE:** The approach above is a starting approximation. The exact parallelogram geometry for perfectly accurate perspective would require projecting 3D corner points, but for the small rotation angles we're using (12-18°), simple rectangles offset to the edges look convincing. Test and adjust — if the side faces look disconnected at larger tilt angles, the strips may need to be drawn as `Path` parallelograms instead of `Rect`.

### Front Face Gloss

The album art on the front face should have a diagonal gloss sweep (the "Longhorn shine"):

```kotlin
// Inside the front face Box content
AsyncImage(
    model = artUri,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = Modifier
        .fillMaxSize()
        .padding(1.dp)  // Tiny inset so the side faces peek around the edge
        .clip(RoundedCornerShape(8.dp))
)

// Gloss overlay on the front face
Box(
    Modifier
        .fillMaxSize()
        .drawBehind {
            val glossBrush = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.0f),
                0.3f to Color.White.copy(alpha = 0.12f),
                0.5f to Color.White.copy(alpha = 0.04f),
                1f to Color.White.copy(alpha = 0.0f),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
            drawRect(brush = glossBrush)
        }
)
```

### Corner Radius

The front face should have a small corner radius (8dp) like a physical album case. The side faces should have matching rounded corners on their visible edges. Use `RoundedCornerShape` on the clip and match with `CornerRadius` in the drawBehind calls.

---

## Part 4: Reflection

A soft reflection beneath the cube sells the "object sitting on a surface" illusion.

```kotlin
// Below the main cube Box, add a reflection
Box(
    modifier = Modifier
        .size(cubeSize)
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = -animatedScale * 0.3f  // Flipped vertically + compressed
            rotationY = animatedTiltY
            rotationX = -animatedTiltX  // Mirrored tilt
            alpha = 0.15f
            cameraDistance = 12f * density
        }
        .padding(top = 2.dp)  // Small gap between cube and reflection
) {
    if (albumId != null) {
        val artUri = "content://media/external/audio/albumart/$albumId"
        AsyncImage(
            model = artUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .blur(4.dp)  // Soft reflection
        )
    }
}
```

**The reflection should also scale with the bass energy** — it gets slightly more visible (alpha 0.15 → 0.22) on bass hits, which reinforces the "object bouncing on a surface" feel.

---

## Part 5: Visualizer Overlay (Fixed)

The existing `SlabVisualizer` has bounds-check issues and a magic normalization constant.

### Fixes:

1. **Bounds checking:** Ensure `i * 2 + 1 < fftData.size` before accessing any FFT bin.

2. **Adaptive normalization:** Replace the fixed `/64f` divisor with a running peak tracker (similar to the bass energy calculation). This ensures the visualizer is equally responsive to quiet jazz and loud metal.

3. **Bar count:** Use `minOf(fftData.size / 4, 16)` bins maximum. More than 16 bars on a 100dp surface is too dense.

4. **Colors from album palette:** The `colors` parameter should receive `cubePalette.visualizerColors` instead of the wallpaper-derived colors.

5. **Opacity:** Reduce bar alpha from 0.7f to 0.5f so the visualizer doesn't overpower the album art. The art should always be the primary visual; the visualizer is atmospheric.

6. **Only show when playing:** The existing `if (state.isPlaying && fftData.isNotEmpty())` guard is correct. Keep it.

---

## Part 6: Idle State

When music is paused or stopped, the cube should NOT be static. It should:

1. **Gentle idle drift:** Slow tilt oscillation (±3° on Y axis, 8-second period) using `rememberInfiniteTransition`. This keeps the 3D effect visible and shows the side faces shifting.

2. **Cross-fade to bass-reactive:** When playback starts, the idle drift should smoothly hand off to the bass-reactive tilt over ~500ms. Use `animateFloatAsState` targeting the bass-reactive values — since it uses springs, it will naturally transition from wherever the idle drift left off.

3. **Scale returns to 1.0f** when paused (no bounce).

4. **Visualizer fades out** (already handled by the `isPlaying` guard).

---

## Composable Signature

The public signature should remain compatible with existing call sites:

```kotlin
@Composable
fun NowPlayingSlab(
    state: PlaybackState,
    fftData: ByteArray,
    visualizerColors: List<Color>,  // Keep param for backward compat, but prefer album-derived internally
    albumGlowColor: Color = AgPalette.VividBlue,  // Keep param, used as fallback
    onClick: () -> Unit
)
```

The function internally derives `CubePalette` from the album art and uses those colors instead of the passed-in `visualizerColors` when album art is available. Fall back to the passed-in colors when no album art exists.

---

## Tuning Constants (Starting Points)

These will need testing on-device. Start here and adjust:

| Parameter | Value | Notes |
|-----------|-------|-------|
| Cube size | 100.dp | Same as current |
| Cube thickness | 14.dp | ~14% of width, chunky |
| Bass scale range | 1.0 – 1.08 | Subtle but visible |
| Scale spring damping | 0.55f | Quick punch, one overshoot |
| Scale spring stiffness | 400f | Responsive |
| Base tilt X | 12° | Shows bottom face |
| Base tilt Y | 15° | Shows right face |
| Bass tilt X bonus | +6° max | Punches forward on kick |
| Bass tilt Y bonus | +3° max | Slight lateral wobble |
| Tilt X spring damping | 0.6f | Slightly heavier than scale |
| Tilt Y spring stiffness | 200f | Slower lateral response |
| Camera distance | 12f * density | Moderate perspective |
| Reflection alpha | 0.15 – 0.22 | Bass-reactive |
| Reflection blur | 4.dp | Soft |
| Idle tilt range | ±3° Y | Gentle drift |
| Idle tilt period | 8 seconds | Slow |
| Running peak decay | 0.998f per frame | Slow adaptation |
| Visualizer max bars | 16 | Readable density |
| Visualizer alpha | 0.5f | Atmospheric, not dominant |
| Front face corner radius | 8.dp | Physical album case feel |
| Gloss highlight alpha | 0.12f peak | Subtle diagonal shine |

---

## Part 7: Free-Floating Draggable Tile

The slab should be freely repositionable. The user can drag it to any corner, edge, or position on screen. It's their object — it lives where they put it.

### Hosting Change in MainActivity

Currently in `MainActivity.kt`, the slab is hosted in a fixed position:

```kotlin
// CURRENT — fixed position
Box(
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 80.dp)
) {
    NowPlayingSlab(...)
}
```

Replace with a draggable container:

```kotlin
// NEW — free-floating, draggable
var slabOffsetX by remember { mutableFloatStateOf(0f) }
var slabOffsetY by remember { mutableFloatStateOf(0f) }
var slabInitialized by remember { mutableStateOf(false) }

// Initialize to bottom-center on first layout
BoxWithConstraints(Modifier.fillMaxSize()) {
    if (!slabInitialized) {
        slabOffsetX = constraints.maxWidth / 2f - 50.dp.toPx()  // Center horizontally (half of 100dp cube)
        slabOffsetY = constraints.maxHeight - 180.dp.toPx()     // Near bottom
        slabInitialized = true
    }

    if (playbackState.currentTrack != null && !isNowPlayingExpanded) {
        Box(
            modifier = Modifier
                .offset { IntOffset(slabOffsetX.roundToInt(), slabOffsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        slabOffsetX = (slabOffsetX + dragAmount.x)
                            .coerceIn(0f, constraints.maxWidth - 100.dp.toPx())
                        slabOffsetY = (slabOffsetY + dragAmount.y)
                            .coerceIn(0f, constraints.maxHeight - 100.dp.toPx())
                    }
                }
        ) {
            NowPlayingSlab(
                state = playbackState,
                fftData = fftData,
                visualizerColors = visualizerColors,
                onClick = { isNowPlayingExpanded = true }
            )
        }
    }
}
```

### Gesture Handling: Drag vs Tap

The slab needs to distinguish between:
- **Tap** → expand to now-playing view (existing behavior)
- **Drag** → reposition the tile

Use `detectDragGestures` on the outer container and keep `clickable` on the slab's internal Box. Compose's gesture system will naturally disambiguate: a pointer that moves > touch slop becomes a drag, a quick down-up is a tap.

**Important:** The `clickable` modifier inside `NowPlayingSlab` needs to stay. The drag handler goes on the *outer* container in `MainActivity`, not on the slab itself. This way the slab's internal `onClick` still fires for taps while the outer wrapper handles drags.

### Position Persistence

Save the slab position to `SharedPreferences` (already used for wallpaper URI):

```kotlin
// On drag end, persist position
val prefs = context.getSharedPreferences("visibeat_prefs", MODE_PRIVATE)

// Save after drag settles (debounce to avoid writing on every frame)
LaunchedEffect(slabOffsetX, slabOffsetY) {
    delay(500) // Wait for drag to settle
    prefs.edit()
        .putFloat("slab_x", slabOffsetX)
        .putFloat("slab_y", slabOffsetY)
        .apply()
}

// Load on init
val savedX = prefs.getFloat("slab_x", -1f)
val savedY = prefs.getFloat("slab_y", -1f)
if (savedX >= 0 && savedY >= 0) {
    slabOffsetX = savedX
    slabOffsetY = savedY
    slabInitialized = true
}
```

### Edge Snapping (Optional Enhancement)

After the user releases a drag, the tile could optionally snap to the nearest screen edge (left, right, bottom) with a spring animation. This keeps the tile tidy without constraining where the user can place it. Implement with:

1. On drag end, calculate the nearest edge
2. Animate `slabOffsetX` / `slabOffsetY` toward the snap position using `animateFloatAsState` with `spring(dampingRatio = 0.7f, stiffness = 300f)`
3. Make this behavior toggleable (some users will want free placement, others will want snap)

For now, free placement without snapping is fine. Edge snapping can be added later.

### Interaction with NowPlayingExpanded

When the expanded overlay opens, it currently slides up from the bottom. In the future (separate task), the expanded view should bloom outward from the tile's current position rather than sliding from a fixed edge. For now:

1. When expanding, the slab should fade out in place (150ms) as the expanded overlay appears
2. When collapsing, the slab should fade back in at its saved position
3. The slab's position should NOT reset when the expanded view opens/closes

---

### Reflection Toggle in Dev Menu

The reflection is a nice visual touch but some users (or the developer during testing) may want to disable it — either for aesthetic preference or performance on lower-end devices.

**Add a toggle in the navigation drawer** (the existing dev/settings menu in `MainActivity.kt`):

```kotlin
// In the drawer content, after the existing menu items:
HorizontalDivider(Modifier.padding(vertical = 8.dp))

var reflectionEnabled by remember {
    mutableStateOf(prefs.getBoolean("slab_reflection_enabled", true))
}

NavigationDrawerItem(
    label = { Text(if (reflectionEnabled) "🪞 Reflection: ON" else "🪞 Reflection: OFF") },
    selected = false,
    onClick = {
        reflectionEnabled = !reflectionEnabled
        prefs.edit().putBoolean("slab_reflection_enabled", reflectionEnabled).apply()
    },
    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
)
```

**Pass the value down** to `NowPlayingSlab` as a parameter:

```kotlin
@Composable
fun NowPlayingSlab(
    state: PlaybackState,
    fftData: ByteArray,
    visualizerColors: List<Color>,
    albumGlowColor: Color = AgPalette.VividBlue,
    showReflection: Boolean = true,  // NEW — controlled by dev menu toggle
    onClick: () -> Unit
)
```

**Guard the reflection rendering** (Part 4) with `if (showReflection) { ... }`. When off, skip the reflection Box entirely — no alpha, no blur, no image load.

The toggle state persists in `SharedPreferences` (same file already used for wallpaper URI and slab position), defaulting to `true` (reflection on).

---

## Future: Expanding Widget (Not This Task)

A future task will replace `NowPlayingExpanded` (the bottom sheet) with a widget that expands directly from the tile's current position. The tile becomes the seed for the controls — playback buttons bloom outward from the cube, the track list unfurls below it, and collapsing reverses the animation back into the cube.

This is flagged here for context so the draggable implementation doesn't make assumptions that would conflict with this future direction. Specifically:
- The slab's offset values should be accessible to sibling composables (they'll need to know where the tile is to animate the expansion origin)
- The expanded/collapsed state transition should be designed with animation origins in mind
- Don't hardcode any "slide from bottom" assumptions into the slab hosting code

---

## Verification

After implementation:
1. Play a track with strong bass — the cube should visibly pulse on kick drums with a natural spring bounce
2. Play a quiet acoustic track — the cube should still respond, just subtly (adaptive normalization)
3. Pause playback — the cube should drift gently with idle tilt animation
4. Switch tracks — the side face colors should change to match the new album art
5. Track with no album art — should fall back to the default warm brown palette
6. The cube should look like a chunky 3D object, not a flat card with a shadow
7. The reflection should be visible beneath the cube and pulse subtly with bass
8. No array index out of bounds exceptions from FFT processing
9. Drag the tile — it should move freely with the finger, no lag or jitter
10. Tap the tile (without dragging) — it should still open the expanded now-playing view
11. Release a drag — the tile stays where you put it
12. Kill and reopen the app — the tile should appear at its last saved position
13. The tile should stay within screen bounds (clamped) even after rotation or resize
14. Navigating between screens (Home, Library, Timeline) should NOT reset the tile's position
15. Toggle reflection off in the dev menu — the reflection should disappear immediately; toggle on — it reappears
16. Kill and reopen the app — the reflection toggle state should persist

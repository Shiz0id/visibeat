# VisiBeat Task 001: Replace MediaMetadataRetriever with JAudioTagger

## Context

VisiBeat's `TagExtractor` currently uses Android's `MediaMetadataRetriever`, which can only read ~9 basic fields (title, artist, album, albumArtist, genre, year, trackNo, discNo, composer). The `TagBundle` data class has 25+ fields including MusicBrainz IDs, ISRC, ReplayGain, barcode, and an extras map — all of which are **always null** because MMR can't read them.

The entire data model downstream (observations, identities, MusicBrainz enrichment, timeline resolution) is designed to consume rich metadata. This task unlocks ~80% of the app's data model.

## Goal

Replace `MediaMetadataRetriever` with JAudioTagger (or its Android fork) so that `TagExtractor.extract()` populates **all** `TagBundle` fields from embedded ID3v2/Vorbis/FLAC/MP4 tags.

## Dependency

Use the Android-compatible fork of JAudioTagger. In `ingest/build.gradle.kts`, add:

```kotlin
dependencies {
    // ... existing deps ...
    implementation("net.jthink:jaudiotagger:3.0.1")
}
```

> **Note:** JAudioTagger needs file-system access. For SAF URIs, you'll need to copy to a temp file first. For MediaStore content:// URIs, same approach. See implementation notes below.

## Files to Change

### 1. `ingest/build.gradle.kts`
- Add JAudioTagger dependency

### 2. `ingest/src/main/java/com/visibeat/ingest/TagExtractor.kt`
- **Full rewrite.** Replace the MMR-based implementation.
- New implementation should:
  1. Accept a `Uri` + `Context` (same interface as today)
  2. Copy the URI to a temporary file (JAudioTagger needs a `java.io.File`)
  3. Use `AudioFileIO.read(tempFile)` to get the `Tag`
  4. Read **all** `TagBundle` fields from the tag
  5. Clean up the temp file
  6. Return the populated `TagBundle`

### 3. `ingest/proguard-rules.pro` (if minification is ever enabled)
- Add keep rules for JAudioTagger reflection-heavy classes

## Implementation Notes

### URI → Temp File Pattern

```kotlin
private fun uriToTempFile(context: Context, uri: Uri): File? {
    return try {
        val tempFile = File.createTempFile("visibeat_tag_", ".tmp", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile
    } catch (e: Exception) {
        Log.e("TagExtractor", "Failed to create temp file for $uri", e)
        null
    }
}
```

### Reading Tags with JAudioTagger

Key classes: `AudioFileIO`, `AudioFile`, `Tag`, `FieldKey`

```kotlin
val audioFile = AudioFileIO.read(tempFile)
val tag = audioFile.tag ?: return TagBundle()
```

### Field Mapping Reference

| TagBundle field | JAudioTagger FieldKey / approach |
|---|---|
| `title` | `tag.getFirst(FieldKey.TITLE)` |
| `artist` | `tag.getFirst(FieldKey.ARTIST)` |
| `album` | `tag.getFirst(FieldKey.ALBUM)` |
| `albumArtist` | `tag.getFirst(FieldKey.ALBUM_ARTIST)` |
| `composer` | `tag.getFirst(FieldKey.COMPOSER)` |
| `genre` | `tag.getFirst(FieldKey.GENRE)` |
| `releaseDateRaw` | `tag.getFirst(FieldKey.YEAR)` |
| `originalReleaseDateRaw` | `tag.getFirst(FieldKey.ORIGINAL_YEAR)` — may need custom frame read for some taggers |
| `trackNumberRaw` | `tag.getFirst(FieldKey.TRACK)` — preserves "3/12" format |
| `discNumberRaw` | `tag.getFirst(FieldKey.DISC_NO)` — preserves "1/2" format |
| `isrc` | `tag.getFirst(FieldKey.ISRC)` |
| `barcode` | `tag.getFirst(FieldKey.BARCODE)` |
| `mbRecordingId` | `tag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID)` |
| `mbReleaseId` | `tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID)` |
| `mbReleaseGroupId` | `tag.getFirst(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID)` |
| `mbArtistId` | `tag.getFirst(FieldKey.MUSICBRAINZ_ARTISTID)` |
| `mbAlbumArtistId` | `tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEARTISTID)` |

### Extras Map

For the `extra: Map<String, String>` field, iterate over common TXXX / custom frames that don't have dedicated FieldKey mappings. The `Id3FieldMap.kt` already defines the mapping from raw frame IDs to `MetadataField` — the extras map should capture anything that `Id3FieldMap.mapExtraKeyToField()` would recognize.

Approach for ID3v2 tags:
```kotlin
// Pseudo-code — adapt to JAudioTagger's API
if (tag is AbstractID3v2Tag) {
    // Read TXXX frames
    val txxxFields = tag.getFields("TXXX")
    for (field in txxxFields) {
        val desc = field.description  // e.g. "REPLAYGAIN_TRACK_GAIN"
        val value = field.value
        extras["TXXX:$desc"] = value
    }
}
```

For Vorbis/FLAC, iterate over all fields and include non-standard ones in extras.

### Null/Empty Handling

Every `tag.getFirst(...)` call can return `null` or `""`. Normalize both to `null` in the TagBundle. Use a helper:

```kotlin
private fun Tag.firstOrNull(key: FieldKey): String? =
    try { getFirst(key)?.trim()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
```

Some FieldKey values may throw `UnsupportedOperationException` for certain tag formats (e.g., asking for MusicBrainz IDs in a WAV file). Always wrap in try-catch.

## What NOT to Change

- **`TagBundle.kt`** — No changes needed. The data class is already correct.
- **`MusicIngestRepository.kt`** — No changes. It already consumes all TagBundle fields.
- **`MediaStoreScanner.kt`** / **`SafScanner.kt`** — No changes. They already pass tags through correctly.
- **`Id3FieldMap.kt`** — No changes. It already maps raw frames to MetadataField.
- **Any UI code** — The view layer already displays whatever data the resolver provides.
- **Database schema** — No migration needed. The schema already supports all these fields via the observations table.

## Verification

After implementation, ingest a library with MusicBrainz Picard-tagged files and verify:

1. `TagBundle.mbRecordingId` is non-null for tagged tracks
2. `TagBundle.mbReleaseId` is non-null for tagged tracks
3. `TagBundle.isrc` is populated where present
4. `TagBundle.extra` contains ReplayGain values where present
5. The observations table has rows for `MB_RECORDING_ID`, `MB_RELEASE_ID`, etc.
6. The identity tables have `MB_RECORDING`, `MB_RELEASE`, `MB_ARTIST` entries
7. Existing basic fields (title, artist, album, etc.) still work correctly

## Performance Consideration

The temp-file copy adds I/O overhead per track. For large libraries this matters. Future optimization: if the URI resolves to a file:// path (common for MediaStore on older Android), skip the copy and pass the File directly. But for now, correctness over speed — this is a prototype.

## Risk

JAudioTagger 3.x should work on Android but historically has had minor issues with Android's restricted filesystem. If `AudioFileIO.read()` throws on certain formats, catch gracefully and fall back to the existing MMR approach for that file. Don't let one bad file block the scan.

Consider a fallback pattern:

```kotlin
fun extract(uri: Uri): TagBundle {
    return extractWithJAudioTagger(uri) ?: extractWithMMR(uri)
}
```

Keep the old MMR code as `extractWithMMR()` as a safety net during the transition.

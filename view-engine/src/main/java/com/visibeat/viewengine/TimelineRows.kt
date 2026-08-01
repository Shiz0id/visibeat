package com.visibeat.viewengine

import androidx.compose.runtime.Immutable

import java.io.File

/**
 * A single time bucket on the vertical timeline.
 * bucketStartEpochMs is the anchor (UTC midnight) for the bucket.
 */
/**
 * Immutable so Compose can skip.
 *
 * This module is compiled without the Compose compiler, so without the
 * annotation the compiler has no stability metadata for these types and must
 * assume they can change without notifying the composition. Everything
 * downstream inherits that: rows, lists and screens taking them became
 * non-skippable, re-executing on every parent recomposition regardless of
 * whether their inputs had changed.
 *
 * These are `val`-only data classes over primitives and Strings, so the promise
 * is true — but the compiler cannot see that from another module without help.
 */
@Immutable
data class TimelineBucketRow(
  val bucketStartEpochMs: Long,
  val itemCount: Int,
  val distinctReleaseCount: Int,
  val distinctArtistCount: Int
)

/**
 * Preview items inside a bucket (for the cards left/right).
 * Think: a small subset to render the card contents.
 */
@Immutable
data class TimelineItemRow(
  val trackId: Long,
  val effectiveReleaseDateEpochMs: Long?,
  val effectiveTitle: String?,
  val effectiveAlbumTitle: String?,
  val effectiveArtistDisplay: String?,
  val releaseId: Long?,
  val primaryArtistId: Long?,
  val mediaStoreAlbumId: Long? = null,
  val mediaStoreUri: String? = null,
  val artPath: String? = null
) {
  /**
   * Best available art model for Coil: local File, MediaStore URI, or null.
   *
   * Resolved once per row rather than once per read. This was a `get()`, and
   * resolution stats the filesystem — so every read was a disk syscall, on the
   * main thread, once per visible row per recomposition, in scrolling lists.
   *
   * [LazyThreadSafetyMode.PUBLICATION] rather than the default: rows are read
   * from the composition and from playback callbacks, so it has to be safe
   * across threads, but the work is idempotent and cheap enough that racing
   * twice beats locking every time.
   */
  val artModel: Any? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    resolveArtModel(artPath, releaseId, mediaStoreAlbumId)
  }

  companion object {
    /** Set once from Application/Activity context so artModel can find cached art files. */
    @Volatile var _artDir: File? = null

    /**
     * Whether `<releaseId>.jpg` exists, remembered across rows.
     *
     * An album's tracks all resolve to the same file, so without this a
     * 20-track album meant 20 identical `exists()` calls — and every scroll
     * back over it meant 20 more.
     */
    private val artFileExists = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    fun initArtDir(filesDir: File) {
      _artDir = File(filesDir, "albumart")
      artFileExists.clear()
    }

    /**
     * Call after ingest writes new art. Cached misses are the reason a freshly
     * scanned album would otherwise keep showing no cover until the app
     * restarted.
     */
    fun invalidateArtCache() {
      artFileExists.clear()
    }

    internal fun releaseArtFile(releaseId: Long): File? {
      val dir = _artDir ?: return null
      val exists = artFileExists.getOrPut(releaseId) {
        File(dir, "$releaseId.jpg").exists()
      }
      return if (exists) File(dir, "$releaseId.jpg") else null
    }
  }
}

/**
 * Best available art for Coil: a cached file, the shared per-release file, or a
 * MediaStore album-art URI.
 *
 * Shared so playlist covers resolve art by exactly the same rules as tracks —
 * a playlist whose cover came out blank while its first track showed art would
 * be a confusing difference with no cause the user could see.
 */
fun resolveArtModel(artPath: String?, releaseId: Long?, mediaStoreAlbumId: Long?): Any? {
  // 1. Explicit artPath from this track's resolved record
  artPath?.let { return File(it) }
  // 2. Check art dir by releaseId (another track in same release may have saved it)
  if (releaseId != null) {
    TimelineItemRow.releaseArtFile(releaseId)?.let { return it }
  }
  // 3. MediaStore albumart URI fallback
  return mediaStoreAlbumId?.let { "content://media/external/audio/albumart/$it" }
}

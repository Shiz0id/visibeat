package com.visibeat.musicbrainz

import java.net.URLEncoder

/** Where a portrait came from, stored alongside it. */
object ArtistImageSource {
    /** Wikidata P18, hosted on Wikimedia Commons. */
    const val WIKIDATA = "WIKIDATA"

    /** Looked up and nothing found. Recorded so we stop asking. */
    const val NONE = "NONE"
}

/**
 * URL construction and retry rules for artist portrait lookup.
 *
 * Pure functions, kept apart from the networking so the fiddly parts — encoding
 * Commons filenames, deciding when an artist is worth asking about again — can
 * be tested without touching the internet.
 */
object ArtistImageUrls {

    /**
     * Widest edge requested from Commons. Portraits are shown at up to ~128dp,
     * so 640 covers a 4x-density screen without pulling multi-megabyte originals.
     */
    const val IMAGE_WIDTH = 640

    /** Give up on an artist after this many empty lookups. */
    const val MAX_ATTEMPTS = 3

    /** How long before an empty result is worth re-checking. */
    const val RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

    /**
     * Direct image URL for a Commons filename.
     *
     * `Special:FilePath` redirects to the real thumbnail, which avoids having to
     * reproduce Commons' MD5-hashed upload paths by hand. Spaces are legal in
     * Commons filenames and must be percent-encoded, not turned into `+` —
     * hence the manual fix-up, since URLEncoder is form-encoding.
     */
    fun commonsImageUrl(fileName: String, width: Int = IMAGE_WIDTH): String? {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        return "https://commons.wikimedia.org/wiki/Special:FilePath/$encoded?width=$width"
    }

    /**
     * Pulls the Q-number out of a MusicBrainz wikidata relation URL such as
     * `https://www.wikidata.org/wiki/Q44190`.
     */
    fun wikidataIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val id = url.trimEnd('/').substringAfterLast('/')
        return if (id.matches(Regex("^Q\\d+$"))) id else null
    }

    /**
     * Whether an artist should be looked up now.
     *
     * @param hasImage true once a portrait has been found — never look again
     * @param attempts how many times we have already come up empty
     * @param lastAttemptAt when the last empty lookup happened, or null if never
     */
    fun shouldLookUp(
        hasImage: Boolean,
        attempts: Int,
        lastAttemptAt: Long?,
        now: Long,
        maxAttempts: Int = MAX_ATTEMPTS,
        retryAfterMs: Long = RETRY_AFTER_MS
    ): Boolean = when {
        hasImage -> false
        attempts <= 0 || lastAttemptAt == null -> true
        attempts >= maxAttempts -> false
        else -> now - lastAttemptAt >= retryAfterMs
    }
}

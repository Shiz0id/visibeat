package com.visibeat.viewengine

/**
 * The badge on an album header: what the files actually are.
 *
 * Reference designs put a tier here — Tidal's "MAX", Spotify's bitrate. Neither
 * means anything for a local library, where the honest answer is the container
 * the audio is in. Derived from the MIME type MediaStore and SAF already give
 * us, which is why this needs no tag reading and no extra column beyond the one
 * carried on `resolved_tracks`.
 */
enum class AudioFormat(val label: String, val lossless: Boolean) {
    FLAC("FLAC", true),
    ALAC("ALAC", true),
    WAV("WAV", true),
    AIFF("AIFF", true),
    MP3("MP3", false),
    AAC("AAC", false),
    OGG("OGG", false),
    OPUS("OPUS", false),
    WMA("WMA", false);

    companion object {

        /**
         * @return the format, or null when the MIME type is missing or is one we
         *   have no honest label for. Null means "show no badge" rather than
         *   guessing.
         */
        fun fromMimeType(mimeType: String?): AudioFormat? {
            val mime = mimeType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            // Order matters: several of these are substrings of each other, and
            // "audio/x-ms-wma" contains neither "wav" nor "ms-wav".
            return when {
                mime.contains("flac") -> FLAC
                mime.contains("alac") -> ALAC
                mime.contains("aiff") || mime.contains("x-aiff") -> AIFF
                mime.contains("opus") -> OPUS
                mime.contains("wma") -> WMA
                // Checked before "mp4"/"mpeg" because "audio/mp4" is AAC in
                // practice, while "audio/mpeg" is MP3.
                mime.contains("aac") || mime.contains("m4a") || mime.contains("mp4") -> AAC
                mime.contains("ogg") || mime.contains("vorbis") -> OGG
                mime.contains("wav") -> WAV
                mime.contains("mpeg") || mime.contains("mp3") -> MP3
                else -> null
            }
        }

        /**
         * The one format to show for a whole release.
         *
         * Albums are not always uniform — one stray transcode in a folder of
         * FLAC is common. The badge shows whichever format most of the tracks
         * are, because it is a glance rather than a spec sheet. Ties break
         * toward the lossless one, then by declaration order, so the answer is
         * stable rather than dependent on row order.
         */
        fun dominant(mimeTypes: List<String?>): AudioFormat? {
            val counts = mimeTypes.mapNotNull { fromMimeType(it) }
                .groupingBy { it }
                .eachCount()
            if (counts.isEmpty()) return null
            val most = counts.maxOf { it.value }
            return counts.filterValues { it == most }
                .keys
                .sortedWith(compareByDescending<AudioFormat> { it.lossless }.thenBy { it.ordinal })
                .first()
        }
    }
}

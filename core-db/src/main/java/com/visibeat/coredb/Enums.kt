package com.visibeat.coredb

enum class SubjectType { TRACK, ARTIST, RELEASE }

enum class IngestSourceType { MEDIASTORE, SAF_ROOT }

enum class MetaSource {
  FILE_TAG,      // ID3/Vorbis/FLAC (Picard)
  MEDIASTORE,    // MediaStore fields
  FILENAME,
  FOLDER,
  MUSICBRAINZ,   // enrichment
  SPOTIFY,       // enrichment
  USER
}

enum class Confidence { WEAK, STRONG, VERIFIED, USER }

// Identity sources: local + external IDs
enum class IdentitySource {
  // Local identity
  LOCAL_URI,        // generic uri
  MEDIASTORE_ID,    // numeric audio id
  DOCUMENT_URI,     // SAF document uri string
  PATH_HASH,        // optional later

  // MusicBrainz
  MB_RECORDING,       // recording id (track identity)
  MB_RELEASE,         // release id (album)
  MB_RELEASE_GROUP,   // release group id
  MB_ARTIST,          // artist id
  MB_WORK,            // classical work id (optional)
  MB_LABEL,           // label id (optional)

  // Spotify
  SPOTIFY_TRACK,
  SPOTIFY_ALBUM,
  SPOTIFY_ARTIST
}

enum class ArtistRole { PRIMARY, FEATURED, ALBUM_ARTIST }

enum class DateGranularity { NONE, YEAR, MONTH, DAY, INSTANT }

/**
 * Broad ID3 coverage lives here.
 * Store raw values as strings in observations; parse/normalize in resolver.
 *
 * Naming is intentionally explicit to avoid collisions when you later add non-music domains.
 */
enum class MetadataField {
  // ---- Core track identity/display ----
  TRACK_TITLE,
  TRACK_TITLE_SORT,
  TRACK_SUBTITLE,          // e.g. TIT3 (ID3)
  TRACK_VERSION,           // "Remastered", "Radio Edit"
  TRACK_GROUPING,          // GRP1 (iTunes), "Grouping"
  TRACK_WORK,              // "Work" (classical)
  TRACK_MOVEMENT,          // movement name/number
  TRACK_MOVEMENT_NUMBER,

  // ---- People/credits (track-level) ----
  TRACK_ARTIST,            // TPE1
  TRACK_ARTIST_SORT,
  TRACK_ALBUM_ARTIST,      // TPE2
  TRACK_ALBUM_ARTIST_SORT,
  TRACK_COMPOSER,          // TCOM
  TRACK_COMPOSER_SORT,
  TRACK_CONDUCTOR,         // TPE3 (often)
  TRACK_LYRICIST,          // TEXT
  TRACK_REMIXER,           // TPE4 (often)
  TRACK_MIXER,             // custom
  TRACK_DJ_MIXER,          // iTunes
  TRACK_PRODUCER,          // custom
  TRACK_ENGINEER,          // custom
  TRACK_ARRANGER,          // custom

  // ---- Release/album fields ----
  RELEASE_TITLE,           // TALB
  RELEASE_TITLE_SORT,
  RELEASE_SUBTITLE,
  RELEASE_VERSION,
  RELEASE_GROUPING,        // album grouping
  RELEASE_TYPE,            // album/single/ep/compilation (best-effort)
  RELEASE_LABEL,           // publisher/label name
  RELEASE_CATALOG_NUMBER,
  RELEASE_MEDIA,           // CD/Vinyl/Digital
  RELEASE_COUNTRY,
  RELEASE_LANGUAGE,

  // ---- Track/disc numbers ----
  TRACK_NUMBER,            // TRCK (may be "3/12")
  TRACK_TOTAL,
  DISC_NUMBER,             // TPOS (may be "1/2")
  DISC_TOTAL,

  // ---- Dates (store raw + granularity; timeline uses resolved epoch) ----
  RELEASE_DATE,            // Prefer for default axis (Picard usually sets)
  ORIGINAL_RELEASE_DATE,
  RECORDING_DATE,
  TAGGING_DATE,            // when encoded/tagged (rare)
  YEAR,                    // some taggers store year separately

  // ---- Genre & classification ----
  GENRE,                   // TCON
  GENRE_ID,                // numeric genre / ID3v1 style
  MOOD,                    // TMOO
  STYLE,                   // custom
  THEME,                   // custom

  // ---- Content descriptors ----
  BPM,                     // TBPM
  KEY,                     // TKEY
  INITIAL_KEY,             // custom
  TEMPO,                   // custom
  ENERGY,                  // custom
  DANCEABILITY,            // custom

  // ---- Identifiers (non-MB) ----
  ISRC,                    // TSRC
  BARCODE,                 // UPC/EAN (often custom)
  CATALOG_ID,              // alternate catalog id

  // ---- MusicBrainz IDs (Picard) ----
  MB_RECORDING_ID,
  MB_TRACK_ID,
  MB_RELEASE_ID,
  MB_RELEASE_GROUP_ID,
  MB_ARTIST_ID,
  MB_ALBUM_ARTIST_ID,
  MB_WORK_ID,
  MB_LABEL_ID,

  // ---- ReplayGain / Loudness ----
  REPLAYGAIN_TRACK_GAIN,
  REPLAYGAIN_TRACK_PEAK,
  REPLAYGAIN_ALBUM_GAIN,
  REPLAYGAIN_ALBUM_PEAK,
  ITUNES_NORM,             // Sound Check

  // ---- Lyrics / comments ----
  LYRICS,                  // USLT
  COMMENT,                 // COMM
  DESCRIPTION,             // iTunes/Podcast
  COPYRIGHT,               // TCOP
  LICENSE,                 // custom

  // ---- Artwork ----
  ARTWORK_EMBEDDED,        // presence flag / mime
  ARTWORK_URI,             // resolved uri to cached art
  ARTWORK_SIDE_CAR_PATH,   // folder.jpg/cover.png etc.

  // ---- Technical / encoding ----
  ENCODER,                 // TENC
  ENCODED_BY,              // TENC (sometimes)
  ENCODING_SETTINGS,
  MEDIA_TYPE,              // TMED
  FILETYPE,                // TFLT
  BITRATE,                 // derived
  SAMPLE_RATE,             // derived
  CHANNELS,                // derived

  // ---- User-ish / play stats (if you choose to track) ----
  RATING,                  // POPM or app-defined
  PLAY_COUNT,              // PCNT or app-defined
  SKIP_COUNT,
  LAST_PLAYED_AT,
  FAVORITE,                // boolean-ish

  // ---- Podcasts / audiobook-ish ----
  PODCAST,
  EPISODE_ID,
  SEASON_NUMBER,
  EPISODE_NUMBER,

  // ---- Misc ----
  CUSTOM_TAG               // escape hatch for uncommon frames
}

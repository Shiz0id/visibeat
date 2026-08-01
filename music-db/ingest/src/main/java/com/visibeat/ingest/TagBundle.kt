package com.visibeat.ingest

data class TagBundle(
  // Display-ish
  val title: String? = null,
  val album: String? = null,
  val artist: String? = null,
  val albumArtist: String? = null,
  val composer: String? = null,
  val genre: String? = null,

  // Numbers (raw strings so you can preserve weird cases; parse best-effort)
  val trackNumberRaw: String? = null, // "3/12"
  val discNumberRaw: String? = null,  // "1/2"

  // Dates (raw ISO-ish: "1999", "1999-06", "1999-06-01")
  val releaseDateRaw: String? = null,
  val originalReleaseDateRaw: String? = null,

  // Identifiers
  val isrc: String? = null,
  val barcode: String? = null,

  // MusicBrainz (Picard)
  val mbRecordingId: String? = null,
  val mbReleaseId: String? = null,
  val mbReleaseGroupId: String? = null,
  val mbArtistId: String? = null,
  val mbAlbumArtistId: String? = null,

  // Embedded album art (first picture found, if any)
  val embeddedArtBytes: ByteArray? = null,

  // Extra frames (catch-all for “most ID3 tags”)
  // Keys can be raw frames (e.g. “TXXX:REPLAYGAIN_TRACK_GAIN”) or normalized keys you choose.
  val extra: Map<String, String> = emptyMap()
)

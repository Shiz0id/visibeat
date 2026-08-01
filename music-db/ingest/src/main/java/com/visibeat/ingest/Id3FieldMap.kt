package com.visibeat.ingest

import com.visibeat.coredb.MetadataField

object Id3FieldMap {
  /**
   * Map raw frames / normalized keys -> MetadataField.
   * Supports both:
   * - literal ID3 frames (e.g. "TIT2", "TXXX:REPLAYGAIN_TRACK_GAIN")
   * - tagger-friendly keys (e.g. "replaygain_track_gain")
   */
  fun mapExtraKeyToField(keyRaw: String): MetadataField? {
    val k = keyRaw.trim()

    return when {
      k.equals("TIT2", true) -> MetadataField.TRACK_TITLE
      k.equals("TSOT", true) -> MetadataField.TRACK_TITLE_SORT
      k.equals("TIT3", true) -> MetadataField.TRACK_SUBTITLE
      k.equals("TPE1", true) -> MetadataField.TRACK_ARTIST
      k.equals("TSOP", true) -> MetadataField.TRACK_ARTIST_SORT
      k.equals("TPE2", true) -> MetadataField.TRACK_ALBUM_ARTIST
      k.equals("TSO2", true) -> MetadataField.TRACK_ALBUM_ARTIST_SORT
      k.equals("TCOM", true) -> MetadataField.TRACK_COMPOSER
      k.equals("TPE3", true) -> MetadataField.TRACK_CONDUCTOR
      k.equals("TEXT", true) -> MetadataField.TRACK_LYRICIST
      k.equals("TALB", true) -> MetadataField.RELEASE_TITLE
      k.equals("TSOA", true) -> MetadataField.RELEASE_TITLE_SORT
      k.equals("TCON", true) -> MetadataField.GENRE
      k.equals("TBPM", true) -> MetadataField.BPM
      k.equals("TKEY", true) -> MetadataField.KEY
      k.equals("TRCK", true) -> MetadataField.TRACK_NUMBER
      k.equals("TPOS", true) -> MetadataField.DISC_NUMBER
      k.equals("TSRC", true) -> MetadataField.ISRC
      k.equals("USLT", true) -> MetadataField.LYRICS
      k.equals("COMM", true) -> MetadataField.COMMENT
      k.equals("TENC", true) -> MetadataField.ENCODER
      k.equals("TCOP", true) -> MetadataField.COPYRIGHT
      k.equals("TMOO", true) -> MetadataField.MOOD

      // ReplayGain (common as TXXX frames)
      k.equals("TXXX:REPLAYGAIN_TRACK_GAIN", true) -> MetadataField.REPLAYGAIN_TRACK_GAIN
      k.equals("TXXX:REPLAYGAIN_TRACK_PEAK", true) -> MetadataField.REPLAYGAIN_TRACK_PEAK
      k.equals("TXXX:REPLAYGAIN_ALBUM_GAIN", true) -> MetadataField.REPLAYGAIN_ALBUM_GAIN
      k.equals("TXXX:REPLAYGAIN_ALBUM_PEAK", true) -> MetadataField.REPLAYGAIN_ALBUM_PEAK
      k.equals("replaygain_track_gain", true) -> MetadataField.REPLAYGAIN_TRACK_GAIN
      k.equals("replaygain_track_peak", true) -> MetadataField.REPLAYGAIN_TRACK_PEAK
      k.equals("replaygain_album_gain", true) -> MetadataField.REPLAYGAIN_ALBUM_GAIN
      k.equals("replaygain_album_peak", true) -> MetadataField.REPLAYGAIN_ALBUM_PEAK

      // iTunes Sound Check / Normalization
      k.equals("iTunNORM", true) -> MetadataField.ITUNES_NORM
      k.equals("TXXX:iTunNORM", true) -> MetadataField.ITUNES_NORM

      // MusicBrainz (often as TXXX / Vorbis)
      k.contains("MUSICBRAINZ_RECORDINGID", true) -> MetadataField.MB_RECORDING_ID
      k.contains("MUSICBRAINZ_TRACKID", true) -> MetadataField.MB_TRACK_ID
      k.contains("MUSICBRAINZ_RELEASEID", true) -> MetadataField.MB_RELEASE_ID
      k.contains("MUSICBRAINZ_RELEASEGROUPID", true) -> MetadataField.MB_RELEASE_GROUP_ID
      k.contains("MUSICBRAINZ_ARTISTID", true) -> MetadataField.MB_ARTIST_ID
      k.contains("MUSICBRAINZ_ALBUMARTISTID", true) -> MetadataField.MB_ALBUM_ARTIST_ID
      k.contains("MUSICBRAINZ_WORKID", true) -> MetadataField.MB_WORK_ID
      k.contains("MUSICBRAINZ_LABELID", true) -> MetadataField.MB_LABEL_ID

      else -> null
    }
  }
}

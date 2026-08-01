package com.visibeat.ingest

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File

class TagExtractor(private val context: Context) {

    companion object {
        private const val TAG = "TagExtractor"
        private val KNOWN_EXTENSIONS = setOf(
            ".mp3", ".flac", ".m4a", ".mp4", ".ogg", ".opus",
            ".wav", ".wma", ".aif", ".aiff", ".dsf", ".wv"
        )
    }

    fun extract(uri: Uri): TagBundle {
        return extractWithJAudioTagger(uri) ?: extractWithMMR(uri)
    }

    private fun extractWithJAudioTagger(uri: Uri): TagBundle? {
        val tempFile = uriToTempFile(uri) ?: return null
        return try {
            val audioFile = AudioFileIO.read(tempFile)
            val tag = audioFile.tag ?: return TagBundle()

            val extras = mutableMapOf<String, String>()
            collectExtras(tag, extras)

            val artBytes = try {
                tag.firstArtwork?.binaryData
            } catch (_: Exception) { null }

            TagBundle(
                title = tag.firstOrNull(FieldKey.TITLE),
                artist = tag.firstOrNull(FieldKey.ARTIST),
                album = tag.firstOrNull(FieldKey.ALBUM),
                albumArtist = tag.firstOrNull(FieldKey.ALBUM_ARTIST),
                composer = tag.firstOrNull(FieldKey.COMPOSER),
                genre = tag.firstOrNull(FieldKey.GENRE),
                releaseDateRaw = tag.firstOrNull(FieldKey.YEAR),
                originalReleaseDateRaw = tag.firstOrNull(FieldKey.ORIGINAL_YEAR),
                trackNumberRaw = tag.firstOrNull(FieldKey.TRACK),
                discNumberRaw = tag.firstOrNull(FieldKey.DISC_NO),
                isrc = tag.firstOrNull(FieldKey.ISRC),
                barcode = tag.firstOrNull(FieldKey.BARCODE),
                mbRecordingId = tag.firstOrNull(FieldKey.MUSICBRAINZ_TRACK_ID),
                mbReleaseId = tag.firstOrNull(FieldKey.MUSICBRAINZ_RELEASEID),
                mbReleaseGroupId = tag.firstOrNull(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID),
                mbArtistId = tag.firstOrNull(FieldKey.MUSICBRAINZ_ARTISTID),
                mbAlbumArtistId = tag.firstOrNull(FieldKey.MUSICBRAINZ_RELEASEARTISTID),
                embeddedArtBytes = artBytes,
                extra = extras
            )
        } catch (e: Exception) {
            Log.e(TAG, "JAudioTagger failed for $uri, falling back to MMR", e)
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun extractWithMMR(uri: Uri): TagBundle {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val artBytes = try { retriever.embeddedPicture } catch (_: Exception) { null }

            TagBundle(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                releaseDateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                trackNumberRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                discNumberRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                embeddedArtBytes = artBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract tags from $uri", e)
            TagBundle()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun uriToTempFile(uri: Uri): File? {
        return try {
            val mime = context.contentResolver.getType(uri)
            // Prefer extension from URI (most reliable for SAF), then MIME type
            val ext = extensionFromUri(uri) ?: mimeToExtension(mime) ?: ".mp3"
            val tempFile = File.createTempFile("visibeat_tag_", ext, context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create temp file for $uri", e)
            null
        }
    }

    private fun extensionFromUri(uri: Uri): String? {
        // Try lastPathSegment first, then the full URI string (handles encoded SAF paths)
        val candidates = listOfNotNull(
            uri.lastPathSegment,
            Uri.decode(uri.toString())
        )
        for (path in candidates) {
            val dot = path.lastIndexOf('.')
            if (dot < 0) continue
            val ext = path.substring(dot).lowercase()
            if (ext in KNOWN_EXTENSIONS) return ext
        }
        return null
    }

    private fun mimeToExtension(mime: String?): String? = when {
        mime == null -> null
        mime.contains("flac") -> ".flac"
        mime.contains("mp4") || mime.contains("m4a") -> ".m4a"
        mime.contains("ogg") || mime.contains("vorbis") -> ".ogg"
        mime.contains("opus") -> ".opus"
        mime.contains("wav") || mime.contains("wave") -> ".wav"
        mime.contains("aiff") -> ".aif"
        mime.contains("wma") -> ".wma"
        mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
        else -> null
    }

    private fun collectExtras(tag: Tag, extras: MutableMap<String, String>) {
        when (tag) {
            is AbstractID3v2Tag -> collectId3Extras(tag, extras)
            is VorbisCommentTag -> collectVorbisExtras(tag, extras)
        }
    }

    private fun collectId3Extras(tag: AbstractID3v2Tag, extras: MutableMap<String, String>) {
        try {
            val txxxFields = tag.getFields("TXXX")
            for (field in txxxFields) {
                val body = field as? org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX ?: continue
                val desc = body.description ?: continue
                val value = body.text ?: continue
                if (desc.isNotBlank() && value.isNotBlank()) {
                    extras["TXXX:$desc"] = value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read TXXX frames", e)
        }
    }

    private fun collectVorbisExtras(tag: VorbisCommentTag, extras: MutableMap<String, String>) {
        try {
            val fields = tag.fields
            while (fields.hasNext()) {
                val field = fields.next()
                val id = field.id ?: continue
                val value = field.toString().trim().takeIf { it.isNotBlank() } ?: continue
                extras[id] = value
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Vorbis comment fields", e)
        }
    }

    private fun Tag.firstOrNull(key: FieldKey): String? =
        try { getFirst(key)?.trim()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
}

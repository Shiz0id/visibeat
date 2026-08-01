package com.visibeat.ingest

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

class MediaStoreScanner(
    private val context: Context,
    private val repository: MusicIngestRepository,
    private val tagExtractor: TagExtractor
) {

    suspend fun scan() {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID
        )

        // Without this the scan ingests every audio file on the device: ringtones,
        // notification tones, alarms and voice recordings all land in the library
        // as tracks, most of them with no tags and a filename for a title.
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )

        cursor?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val dataColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val nameColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val mimeColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val durationColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modifiedColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val addedColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val albumIdColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (c.moveToNext()) {
                val mediaStoreId = c.getLong(idColumn)
                val path = c.getString(dataColumn)
                val name = c.getString(nameColumn)
                val mime = c.getString(mimeColumn)
                val duration = c.getLong(durationColumn)
                val size = c.getLong(sizeColumn)
                val modified = c.getLong(modifiedColumn) * 1000 // MediaStore stores seconds
                val added = c.getLong(addedColumn) * 1000
                val albumId = c.getLong(albumIdColumn)

                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId.toString())
                val tags = tagExtractor.extract(uri)

                try {
                    repository.upsertFromMediaStore(
                        contentUriString = uri.toString(),
                        mediaStoreAudioId = mediaStoreId,
                        mediaStoreAlbumId = albumId,
                        fileName = name,
                        mimeType = mime,
                        durationMs = duration,
                        sizeBytes = size,
                        fileModifiedAt = modified,
                        addedToLibraryAt = added,
                        tags = tags
                    )
                } catch (e: Exception) {
                    Log.e("MediaStoreScanner", "Failed to ingest $uri", e)
                }
            }
        }
    }
}

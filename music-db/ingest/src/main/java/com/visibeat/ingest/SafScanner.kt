package com.visibeat.ingest

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

class SafScanner(
    private val context: Context,
    private val repository: MusicIngestRepository,
    private val tagExtractor: TagExtractor
) {

    suspend fun scan(treeUri: Uri, rootId: Long) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return
        scanRecursive(rootDoc, rootId)
    }

    private suspend fun scanRecursive(doc: DocumentFile, rootId: Long) {
        if (doc.isDirectory) {
            doc.listFiles().forEach { child ->
                scanRecursive(child, rootId)
            }
        } else if (isAudioFile(doc)) {
            val tags = tagExtractor.extract(doc.uri)
            try {
                repository.upsertFromSaf(
                    documentUriString = doc.uri.toString(),
                    rootId = rootId,
                    fileName = doc.name,
                    mimeType = doc.type,
                    durationMs = null, // DocumentFile doesn't have duration easily
                    sizeBytes = doc.length(),
                    fileModifiedAt = doc.lastModified(),
                    addedToLibraryAt = System.currentTimeMillis(),
                    tags = tags
                )
            } catch (e: Exception) {
                Log.e("SafScanner", "Failed to ingest ${doc.uri}", e)
            }
        }
    }

    private fun isAudioFile(doc: DocumentFile): Boolean {
        val mime = doc.type ?: ""
        return mime.startsWith("audio/") || 
               doc.name?.lowercase()?.endsWith(".mp3") == true ||
               doc.name?.lowercase()?.endsWith(".flac") == true ||
               doc.name?.lowercase()?.endsWith(".m4a") == true ||
               doc.name?.lowercase()?.endsWith(".ogg") == true
    }
}

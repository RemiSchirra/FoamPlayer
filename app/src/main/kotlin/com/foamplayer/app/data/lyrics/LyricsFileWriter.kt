package com.foamplayer.app.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

/**
 * Writes .lrc / .ttml sidecar files next to the audio file, following the
 * naming conventions [com.foamplayer.app.lyrics.providers.LocalLyricsProvider]
 * reads back: same base name as the audio file, in the same directory.
 * Supports both plain filesystem paths and SAF content URIs.
 */
@Singleton
class LyricsFileWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Writes [content] as a sidecar for the audio at [trackPath].
     * [wordSynced] selects the .ttml extension; everything else is written as .lrc.
     */
    fun writeSidecar(trackPath: String, content: String, wordSynced: Boolean): Result<Unit> = runCatching {
        val uri = trackPath.toUri()
        when (uri.scheme) {
            "content" -> writeToSaf(uri, content, wordSynced)
            "file" -> writeToFile(File(uri.path ?: error("Invalid file path")), content, wordSynced)
            else -> writeToFile(File(trackPath), content, wordSynced)
        }.getOrThrow()
    }

    /** Deletes the .lrc / .ttml sidecars of the audio at [trackPath]; returns how many were removed. */
    fun deleteSidecars(trackPath: String): Result<Int> = runCatching {
    val uri = trackPath.toUri()
        when (uri.scheme) {
            "content" -> deleteFromSaf(uri)
            "file" -> deleteFromFile(File(uri.path ?: error("Invalid file path")))
            else -> deleteFromFile(File(trackPath))
        }
    }

    private fun writeToFile(trackFile: File, content: String, wordSynced: Boolean): Result<Unit> = runCatching {
        val parent = trackFile.parentFile ?: error("Track has no parent directory")
        val sidecar = File(parent, "${trackFile.nameWithoutExtension}.${if (wordSynced) "ttml" else "lrc"}")
        sidecar.writeText(content, Charsets.UTF_8)
    }

    private fun deleteFromFile(trackFile: File): Int {
        val parent = trackFile.parentFile ?: return 0
        var deleted = 0
        for (extension in listOf("lrc", "ttml")) {
            val sidecar = File(parent, "${trackFile.nameWithoutExtension}.$extension")
            if (sidecar.isFile && sidecar.delete()) deleted++
        }
        return deleted
    }

    private fun writeToSaf(trackUri: Uri, content: String, wordSynced: Boolean): Result<Unit> = runCatching {
        val documentId = DocumentsContract.getDocumentId(trackUri)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(trackUri)
        val fileName = documentId.substringAfterLast('/')
        val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = documentId)
        val baseName = fileName.substringBeforeLast('.')
        val displayName = "$baseName.${if (wordSynced) "ttml" else "lrc"}"

        val treeUri = DocumentsContract.buildTreeDocumentUri(trackUri.authority, treeDocumentId)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        // NOTE: "text/plain" makes some SAF providers append ".txt" to unknown
        // extensions like ".lrc"; a generic (or provider-unknown) type keeps the
        // display name intact. Reading back only matches the file name anyway.
        val mimeType = if (wordSynced) "application/ttml+xml" else "application/octet-stream"

        var documentUri = DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, displayName)
            ?: error("Could not create lyrics document")
        documentUri = enforceDisplayName(documentUri, displayName)

        context.contentResolver.openOutputStream(documentUri)?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: error("Could not open lyrics document for writing")
    }

    /**
     * Some providers append a mime-derived extension to the created document
     * (e.g. "song.lrc.txt"). Best-effort rename back so the sidecar matches the
     * [com.foamplayer.app.lyrics.providers.LocalLyricsProvider] naming
     * convention; on failure the (misnamed) document is still written to.
     */
    private fun enforceDisplayName(documentUri: Uri, displayName: String): Uri {
        val createdName = runCatching {
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        if (createdName == null || createdName == displayName) return documentUri

        return runCatching {
            DocumentsContract.renameDocument(context.contentResolver, documentUri, displayName)
        }.getOrNull() ?: documentUri
    }

    private fun deleteFromSaf(trackUri: Uri): Int {
        val documentId = DocumentsContract.getDocumentId(trackUri)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(trackUri)
        val fileName = documentId.substringAfterLast('/')
        val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = documentId)
        val baseName = fileName.substringBeforeLast('.').lowercase()

        val treeUri = DocumentsContract.buildTreeDocumentUri(trackUri.authority, treeDocumentId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        var deleted = 0
        val targetNames = setOf("$baseName.lrc", "$baseName.ttml")
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.lowercase() ?: continue
                if (name !in targetNames) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                if (DocumentsContract.deleteDocument(context.contentResolver, docUri)) deleted++
            }
        }
        return deleted
    }
}

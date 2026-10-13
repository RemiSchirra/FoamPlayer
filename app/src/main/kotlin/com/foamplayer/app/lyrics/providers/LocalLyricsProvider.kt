package com.foamplayer.app.lyrics.providers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

@Singleton
class LocalLyricsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getLyrics(trackPath: String): String? = runCatching {
        val uri = trackPath.toUri()
        when (uri.scheme) {
            "content" -> getLyricsFromSaf(uri)
            "file" -> getLyricsFromFile(File(uri.path ?: return null))
            else -> getLyricsFromFile(File(trackPath))
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun getLyricsFromFile(trackFile: File): String? {
        val parent = trackFile.parentFile ?: return null
        val baseName = trackFile.nameWithoutExtension.lowercase()
        if (baseName.isBlank()) return null

        val candidates = parent.listFiles { file ->
            if (!file.isFile) return@listFiles false
            val name = file.name.lowercase()
            name == "$baseName.ttml" || name == "$baseName.lrc"
        } ?: return null

        val chosen = candidates.minByOrNull { if (it.name.lowercase().endsWith(".ttml")) 0 else 1 }
            ?: return null

        return runCatching { chosen.readText(Charsets.UTF_8) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun getLyricsFromSaf(trackUri: Uri): String? {
        val documentId = DocumentsContract.getDocumentId(trackUri)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(trackUri)
        val fileName = documentId.substringAfterLast('/')
        val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = documentId)

        val baseName = fileName.substringBeforeLast('.').lowercase()
        if (baseName.isBlank()) return null

        val treeUri = DocumentsContract.buildTreeDocumentUri(trackUri.authority, treeDocumentId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        val candidates = mutableListOf<Pair<String, Int>>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.lowercase() ?: continue
                val priority = when (name) {
                    // ttml has the priority
                    "$baseName.ttml" -> 0
                    "$baseName.lrc" -> 1
                    else -> continue
                }
                candidates.add(cursor.getString(idIdx) to priority)
            }
        }

        val chosen = candidates.minByOrNull { it.second } ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, chosen.first)
        return context.contentResolver.openInputStream(docUri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }?.trim()?.takeIf { it.isNotBlank() }
    }
}

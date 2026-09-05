package com.foamplayer.app.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.foamplayer.app.data.db.entities.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class ScanStatus {
    object Idle : ScanStatus()
    data class Scanning(val progress: Float, val currentFile: String) : ScanStatus()
    data class Finished(val count: Int) : ScanStatus()
}

@Singleton
class LocalAudioScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val extensions = listOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "m4b", "opus")

    suspend fun scanFoldersSync(
        folderPaths: Set<String>,
        artistSeparators: Set<String>,
        genreSeparators: Set<String>,
        onProgress: (Float, String) -> Unit
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val allTracks = mutableListOf<TrackEntity>()
        val foundFiles = mutableListOf<Pair<Uri, String>>()

        folderPaths.forEach { pathString ->
            try {
                val rootUri = Uri.parse(pathString)
                if (rootUri.scheme == "content") {
                    val treeId = DocumentsContract.getTreeDocumentId(rootUri)
                    collectUrisRecursive(rootUri, treeId, foundFiles, onProgress)
                } else {
                    val rootFile = if (rootUri.scheme == "file") File(rootUri.path ?: "") else File(pathString)
                    if (rootFile.exists() && rootFile.isDirectory) {
                        collectLegacyFiles(rootFile, foundFiles)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (foundFiles.isEmpty()) return@withContext emptyList<TrackEntity>()

        val total = foundFiles.size
        val retriever = MediaMetadataRetriever()

        foundFiles.forEachIndexed { index, (uri, fileName) ->
            if (index % 5 == 0 || index == total - 1) {
                onProgress(index.toFloat() / total, fileName)
            }

            try {
                val track = extractMetadataFromUri(uri, fileName, retriever, artistSeparators, genreSeparators)
                if (track != null) {
                    allTracks.add(track)
                }
            } catch (e: Exception) {
                allTracks.add(createFallbackTrack(uri, fileName))
            }
        }
        retriever.release()
        return@withContext allTracks
    }

    private fun collectUrisRecursive(rootUri: Uri, parentId: String, result: MutableList<Pair<Uri, String>>, onProgress: (Float, String) -> Unit) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx)
                    val name = cursor.getString(nameIdx) ?: "Unknown"
                    val mime = cursor.getString(mimeIdx)

                    if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                        onProgress(0f, "Searching in $name...")
                        collectUrisRecursive(rootUri, id, result, onProgress)
                    } else if (isAudioFile(name)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, id)
                        result.add(docUri to name)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun collectLegacyFiles(dir: File, result: MutableList<Pair<Uri, String>>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                collectLegacyFiles(file, result)
            } else if (isAudioFile(file.name)) {
                result.add(Uri.fromFile(file) to file.name)
            }
        }
    }

    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return extensions.any { lower.endsWith(".$it") }
    }

    private fun extractMetadataFromUri(
        uri: Uri,
        fileName: String,
        retriever: MediaMetadataRetriever,
        artistSeparators: Set<String>,
        genreSeparators: Set<String>
    ): TrackEntity? {
        var title = fileName.substringBeforeLast('.')
        var rawArtist = "<Unknown Artist>"
        var album = "<Unknown Album>"
        var rawAlbumArtist: String? = null
        var duration = 0L
        var bitrate = 0
        var sampleRate = 44100
        var bitDepth = 16
        var size = 0L
        var albumArtPath: String? = null

        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                size = afd.length
            }
        } catch (_: Exception) {}

        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: rawArtist
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
            rawAlbumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L

            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let {
                bitrate = it / 1000
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let {
                sampleRate = it
            }

            if (fileName.lowercase().let { it.endsWith(".flac") || it.endsWith(".wav") }) {
                bitDepth = 24
            }

            if (retriever.embeddedPicture != null) {
                albumArtPath = uri.toString()
            }
        } catch (e: Exception) {
            return createFallbackTrack(uri, fileName, size)
        }

        val processedArtist = splitAndJoin(rawArtist, artistSeparators)
        val processedAlbumArtist = if (rawAlbumArtist != null) splitAndJoin(rawAlbumArtist, artistSeparators) else processedArtist
        val rawGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
        val processedGenre = rawGenre?.let { splitAndJoin(it, genreSeparators) }

        return TrackEntity(
            title = title,
            artist = processedArtist,
            album = album,
            albumArtist = processedAlbumArtist,
            genre = processedGenre,
            year = null,
            trackNumber = null,
            discNumber = null,
            duration = duration,
            bitrate = bitrate,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = 2,
            format = fileName.substringAfterLast('.', "MP3").uppercase(),
            path = uri.toString(),
            size = size,
            albumArtPath = albumArtPath
        )
    }

    private fun splitAndJoin(value: String, separators: Set<String>): String {
        if (separators.isEmpty()) return value
        var result = listOf(value)
        separators.forEach { sep ->
            result = result.flatMap { it.split(sep) }
        }
        return result.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
    }

    private fun createFallbackTrack(uri: Uri, fileName: String, size: Long = 0): TrackEntity {
        val format = fileName.substringAfterLast('.', "MP3").uppercase()
        return TrackEntity(
            title = fileName.substringBeforeLast('.'),
            artist = "<Unknown Artist>",
            album = "<Unknown Album>",
            albumArtist = null,
            genre = null,
            year = null,
            trackNumber = null,
            discNumber = null,
            duration = 0,
            bitrate = 0,
            sampleRate = 44100,
            bitDepth = 16,
            channels = 2,
            format = format,
            path = uri.toString(),
            size = size,
            albumArtPath = null
        )
    }

    suspend fun scanDeviceAudioFiles(): List<TrackEntity> = emptyList()
}

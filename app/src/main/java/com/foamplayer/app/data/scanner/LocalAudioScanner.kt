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
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var composer: String? = null
        var year: Int? = null

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

            // Real reported bit depth when the container provides one (FLAC, WAV...),
            // falling back to the extension guess for lossy/unreported formats.
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                ?.toIntOrNull()?.takeIf { it > 0 }?.let { bitDepth = it }
                ?: run {
                    if (fileName.lowercase().let { it.endsWith(".flac") || it.endsWith(".wav") }) {
                        bitDepth = 24
                    }
                }

            composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                ?.trim()?.takeIf { it.isNotEmpty() }
            // YEAR is not always populated: FLAC/ID3v2.4 files often only carry a
            // DATE tag ("2021" or full "2021-03-04"), so fall back to it and pick
            // the first 4-digit group.
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ?.yearPart()

            // The platform FLAC extractor maps only a subset of the Vorbis comment
            // fields, so DATE/YEAR may never surface through MediaMetadataRetriever
            // at all. Read the VORBIS_COMMENT block straight from the file as a
            // last resort.
            if (year == null && fileName.lowercase().endsWith(".flac")) {
                readFlacVorbisComments(uri)?.let { comments ->
                    year = comments["DATE"]?.yearPart()
                        ?: comments["YEAR"]?.yearPart()
                        ?: comments["ORIGINALDATE"]?.yearPart()
                        ?: comments["ORIGINALYEAR"]?.yearPart()
                }
            }

            // Tag values can be "3" or "3/12" — keep only the number before the slash.
            trackNumber = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')
                ?.trim()
                ?.toIntOrNull()
            discNumber = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ?.substringBefore('/')
                ?.trim()
                ?.toIntOrNull()

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
            composer = composer,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
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

    /**
     * Reads a FLAC file's VORBIS_COMMENT metadata block directly. The platform
     * extractor only surfaces a subset of the comment fields through
     * MediaMetadataRetriever, so tags like DATE/YEAR/COMMENT may otherwise be
     * invisible. Returns an empty map when the stream isn't a FLAC file or can't
     * be parsed; never throws.
     */
    private fun readFlacVorbisComments(uri: Uri): Map<String, String>? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val magic = ByteArray(4)
            if (!readFully(input, magic) || !magic.decodeToString(0, 4).startsWith("fLaC")) {
                return@use null
            }

            val blockHeader = ByteArray(4)
            while (readFully(input, blockHeader)) {
                val isLastBlock = (blockHeader[0].toInt() and 0x80) != 0
                val blockType = blockHeader[0].toInt() and 0x7F
                val blockLength = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                        ((blockHeader[2].toInt() and 0xFF) shl 8) or
                        (blockHeader[3].toInt() and 0xFF)

                if (blockType == 4) { // VORBIS_COMMENT
                    val data = ByteArray(blockLength)
                    if (!readFully(input, data)) return@use null
                    return@use parseVorbisComments(data)
                }
                if (isLastBlock || !skipFully(input, blockLength.toLong())) return@use null
            }
            null
        }
    }.getOrNull()

    private fun parseVorbisComments(data: ByteArray): Map<String, String> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val comments = mutableMapOf<String, String>()

        fun readUtf8(): String? {
            if (buffer.remaining() < 4) return null
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) return null
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        readUtf8() // vendor string, unused
        val count = if (buffer.remaining() >= 4) buffer.int else 0
        repeat(count.coerceIn(0, 1024)) {
            val comment = readUtf8() ?: return comments
            val separator = comment.indexOf('=')
            if (separator > 0) {
                comments[comment.substring(0, separator).uppercase(Locale.US)] =
                    comment.substring(separator + 1)
            }
        }
        return comments
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    /** Read-and-discard skip: SAF streams often don't support InputStream.skip. */
    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) return false
            remaining -= read
        }
        return true
    }

    suspend fun scanDeviceAudioFiles(): List<TrackEntity> = emptyList()
}

/** First 4-digit group not glued to other digits: "2021" or "2021-03-04" -> 2021. */
private val YearRegex = Regex("""(?<!\d)\d{4}(?!\d)""")

private fun String.yearPart(): Int? = YearRegex.find(this)?.value?.toIntOrNull()

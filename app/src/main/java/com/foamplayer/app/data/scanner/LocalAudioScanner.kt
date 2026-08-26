package com.foamplayer.app.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class ScanStatus {
    object Idle : ScanStatus()
    data class Scanning(val progress: Float, val currentFile: String) : ScanStatus()
    data class Finished(val count: Int) : ScanStatus()
}

@Singleton
class LocalAudioScanner @Inject constructor(
    private val context: Context
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

            val artBytes = retriever.embeddedPicture
            if (artBytes != null) {
                albumArtPath = saveAlbumArt(uri, artBytes)
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
            albumArtPath = albumArtPath,
            lyrics = readLyricsSafely(uri, fileName)
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

    private fun saveAlbumArt(uri: Uri, artBytes: ByteArray): String? {
        return try {
            val folder = File(context.cacheDir, "album_art")
            if (!folder.exists()) folder.mkdirs()

            val fileName = "art_${uri.toString().hashCode()}.jpg"
            val file = File(folder, fileName)
            if (!file.exists()) {
                FileOutputStream(file).use { fos ->
                    fos.write(artBytes)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
            albumArtPath = null,
            lyrics = readLyricsSafely(uri, fileName)
        )
    }

    suspend fun scanDeviceAudioFiles(): List<TrackEntity> = emptyList()

    private fun readLyricsSafely(uri: Uri, fileName: String): String? {
        return try {
            readEmbeddedLyrics(uri, fileName)
        } catch (e: Exception) {
            null
        }
    }

    private fun readEmbeddedLyrics(uri: Uri, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in extensions) return null

        context.contentResolver.openInputStream(uri)?.use { input ->
            return when (ext) {
                "mp3", "aac" -> parseId3Lyrics(input)
                "flac" -> parseFlacLyrics(input)
                "ogg", "opus" -> parseOggLyrics(input)
                "m4a", "m4b" -> parseMp4Lyrics(input)
                else -> null
            }
        }
        return null
    }

    private fun parseId3Lyrics(input: InputStream): String? {
        val header = input.readNBytesCompat(10)
        if (header.size < 10) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }
        val majorVersion = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        var remaining = syncSafeInt(header, 6).toLong()

        if (flags and 0x40 != 0) {
            val extHeader = input.readNBytesCompat(4)
            if (extHeader.size < 4) return null
            val extSize = if (majorVersion >= 4) syncSafeInt(extHeader, 0) else beInt(extHeader)
            val toSkip = (extSize - 4).coerceAtLeast(0)
            input.skipFully(toSkip.toLong())
            remaining -= (4 + toSkip)
        }

        while (remaining > 10) {
            val frameHeader = input.readNBytesCompat(10)
            if (frameHeader.size < 10) return null
            if (frameHeader[0] == 0.toByte()) return null

            val frameId = String(frameHeader, 0, 4, Charsets.US_ASCII)
            val frameSize = if (majorVersion >= 4) syncSafeInt(frameHeader, 4) else beInt(frameHeader, 4)
            if (frameSize <= 0 || frameSize.toLong() > remaining) return null

            if (frameId == "USLT") {
                val body = input.readNBytesCompat(frameSize)
                return parseUsltFrame(body)
            } else {
                input.skipFully(frameSize.toLong())
            }
            remaining -= (10 + frameSize)
        }
        return null
    }

    private fun parseUsltFrame(body: ByteArray): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xFF
        val offset = 4
        if (offset >= body.size) return null

        val (charset, terminatorLen) = when (encoding) {
            0 -> Charsets.ISO_8859_1 to 1
            1 -> Charsets.UTF_16 to 2
            2 -> Charsets.UTF_16BE to 2
            3 -> Charsets.UTF_8 to 1
            else -> Charsets.ISO_8859_1 to 1
        }

        var descEnd = offset
        if (terminatorLen == 1) {
            while (descEnd < body.size && body[descEnd] != 0.toByte()) descEnd++
        } else {
            while (descEnd + 1 < body.size && !(body[descEnd] == 0.toByte() && body[descEnd + 1] == 0.toByte())) {
                descEnd += 2
            }
        }
        val lyricsStart = (descEnd + terminatorLen).coerceAtMost(body.size)
        if (lyricsStart >= body.size) return null

        var lyricsBytes = body.copyOfRange(lyricsStart, body.size)
        lyricsBytes = trimTrailingNulls(lyricsBytes, terminatorLen)

        val text = String(lyricsBytes, charset).trim()
        return text.ifBlank { null }
    }

    private fun parseFlacLyrics(input: InputStream): String? {
        val magic = input.readNBytesCompat(4)
        if (magic.size < 4 || String(magic, Charsets.US_ASCII) != "fLaC") return null

        while (true) {
            val blockHeader = input.readNBytesCompat(4)
            if (blockHeader.size < 4) return null
            val isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val blockSize = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                    ((blockHeader[2].toInt() and 0xFF) shl 8) or
                    (blockHeader[3].toInt() and 0xFF)

            if (blockType == 4) {
                val block = input.readNBytesCompat(blockSize)
                return extractLyricsFromVorbisComment(block)
            } else {
                input.skipFully(blockSize.toLong())
            }
            if (isLast) return null
        }
    }

    private fun parseOggLyrics(input: InputStream): String? {
        var pagesRead = 0
        while (pagesRead < 10) {
            val capture = input.readNBytesCompat(4)
            if (capture.size < 4 || String(capture, Charsets.US_ASCII) != "OggS") return null

            input.skipFully(1)
            input.skipFully(1)
            input.skipFully(8)
            input.skipFully(4)
            input.skipFully(4)
            input.skipFully(4)

            val segCountByte = input.readNBytesCompat(1)
            if (segCountByte.isEmpty()) return null
            val segCount = segCountByte[0].toInt() and 0xFF
            val segTable = input.readNBytesCompat(segCount)
            var bodySize = 0
            for (b in segTable) bodySize += (b.toInt() and 0xFF)

            val body = input.readNBytesCompat(bodySize)
            pagesRead++

            if (body.size >= 7 && body[0] == 0x03.toByte() &&
                String(body, 1, 6, Charsets.US_ASCII) == "vorbis"
            ) {
                return extractLyricsFromVorbisComment(body.copyOfRange(7, body.size))
            }
            if (body.size >= 8 && String(body, 0, 8, Charsets.US_ASCII) == "OpusTags") {
                return extractLyricsFromVorbisComment(body.copyOfRange(8, body.size))
            }
        }
        return null
    }

    private fun extractLyricsFromVorbisComment(bytes: ByteArray): String? {
        var pos = 0
        fun readLEInt(): Int? {
            if (pos + 4 > bytes.size) return null
            val v = (bytes[pos].toInt() and 0xFF) or
                    ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        val vendorLen = readLEInt() ?: return null
        if (vendorLen < 0 || pos + vendorLen > bytes.size) return null
        pos += vendorLen

        val ownerCount = readLEInt() ?: return null
        if (ownerCount < 0) return null

        repeat(ownerCount) {
            val len = readLEInt() ?: return null
            if (len < 0 || pos + len > bytes.size) return null
            val comment = String(bytes, pos, len, Charsets.UTF_8)
            pos += len

            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "LYRICS-XXX") {
                    val value = comment.substring(eq + 1).trim()
                    if (value.isNotBlank()) return value
                }
            }
        }
        return null
    }

    private fun parseMp4Lyrics(input: InputStream): String? {
        return findMp4LyricsAtom(input, Long.MAX_VALUE)
    }

    private fun findMp4LyricsAtom(input: InputStream, limit: Long): String? {
        var consumed = 0L
        while (consumed < limit) {
            val sizeBytes = input.readNBytesCompat(4)
            if (sizeBytes.size < 4) return null
            var size = beInt(sizeBytes).toLong()

            val typeBytes = input.readNBytesCompat(4)
            if (typeBytes.size < 4) return null
            val type = String(typeBytes, Charsets.ISO_8859_1)

            var headerLen = 8L
            if (size == 1L) {
                val ext = input.readNBytesCompat(8)
                if (ext.size < 8) return null
                size = beLong(ext)
                headerLen = 16L
            }
            if (size <= 0) return null

            val bodySize = size - headerLen
            if (bodySize < 0) return null

            when (type) {
                "moov", "udta", "ilst" -> {
                    val result = findMp4LyricsAtom(input, bodySize)
                    if (result != null) return result
                }
                "meta" -> {
                    input.skipFully(4)
                    val result = findMp4LyricsAtom(input, bodySize - 4)
                    if (result != null) return result
                }
                "\u00A9lyr" -> {
                    val body = input.readNBytesCompat(bodySize.toInt())
                    return extractLyricsFromIlstData(body)
                }
                else -> {
                    input.skipFully(bodySize)
                }
            }
            consumed += size
        }
        return null
    }

    private fun extractLyricsFromIlstData(body: ByteArray): String? {
        var pos = 0
        while (pos + 8 <= body.size) {
            val size = beInt(body, pos)
            val type = String(body, pos + 4, 4, Charsets.ISO_8859_1)
            if (size <= 0 || pos + size > body.size) break

            if (type == "data" && size > 16) {
                val textBytes = body.copyOfRange(pos + 16, pos + size)
                val text = String(textBytes, Charsets.UTF_8).trim { it.isISOControl() }
                if (text.isNotBlank()) return text
            }
            pos += size
        }
        return null
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
                ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
                ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
                (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun beInt(bytes: ByteArray, offset: Int = 0): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun beLong(bytes: ByteArray, offset: Int = 0): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun trimTrailingNulls(bytes: ByteArray, step: Int): ByteArray {
        var end = bytes.size
        while (end >= step) {
            var allZero = true
            for (i in end - step until end) {
                if (bytes[i] != 0.toByte()) {
                    allZero = false
                    break
                }
            }
            if (allZero) end -= step else break
        }
        return if (end == bytes.size) bytes else bytes.copyOfRange(0, end)
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buffer = ByteArray(n)
        var totalRead = 0
        while (totalRead < n) {
            val read = read(buffer, totalRead, n - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == n) buffer else buffer.copyOf(totalRead)
    }

    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                val toRead = minOf(remaining, 8192L).toInt()
                val discarded = readNBytesCompat(toRead)
                if (discarded.isEmpty()) break
                remaining -= discarded.size
            } else {
                remaining -= skipped
            }
        }
    }
}

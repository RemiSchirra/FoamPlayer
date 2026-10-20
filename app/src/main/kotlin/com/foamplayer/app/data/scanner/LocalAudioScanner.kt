package com.foamplayer.app.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.foamplayer.app.data.db.entities.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

/** State of a library scan as observed by the progress UI. */
sealed class ScanStatus {
    object Idle : ScanStatus()
    data class Scanning(
        val progress: Float,
        val currentFile: String,
        /** Auto-scan on app open: progress mirrors in-app only, no notification. */
        val automatic: Boolean = false
    ) : ScanStatus()
    data class Finished(val count: Int, val automatic: Boolean = false) : ScanStatus()
}

/** An audio file found during enumeration, with its size and mtime already resolved. */
private data class FoundAudioFile(val uri: Uri, val name: String, val size: Long, val lastModified: Long)

/**
 * Enumerates audio files under SAF tree URIs (or plain directories) and
 * extracts their metadata with MediaMetadataRetriever. Extraction runs on a
 * bounded parallel pool; unchanged files can be reused from a previous scan;
 * container-level fallbacks read FLAC Vorbis comments and the MP4 ©day/©wrt
 * atoms when the platform extractor hides them.
 */
@Singleton
class LocalAudioScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val extensions = listOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "m4b", "opus")
    private val scanDispatcher = Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)

    /**
     * Scans [folderPaths] and extracts metadata for every audio file found.
     * [knownTracks] entries with an unchanged size are reused instead of
     * re-reading the file; pass an empty map to force a full extraction.
     * Callers must not reuse entries produced with different
     * [artistSeparators]/[genreSeparators], since those transform the strings.
     */
    suspend fun scanFoldersSync(
        folderPaths: Set<String>,
        artistSeparators: Set<String>,
        genreSeparators: Set<String>,
        knownTracks: Map<String, TrackEntity> = emptyMap(),
        onProgress: (Float, String) -> Unit
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val foundFiles = collectAudioFiles(folderPaths, onProgress)
        if (foundFiles.isEmpty()) return@withContext emptyList<TrackEntity>()

        val total = foundFiles.size
        val results = arrayOfNulls<TrackEntity>(total)
        val freshTrack = BooleanArray(total)
        val processed = AtomicInteger(0)

        // Each file gets its own MediaMetadataRetriever (not thread-safe).
        coroutineScope {
            foundFiles.forEachIndexed { index, file ->
                launch(scanDispatcher) {
                    val cached = knownTracks[file.uri.toString()]?.takeIf { it.size == file.size }
                    if (cached == null) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            results[index] = try {
                                extractMetadataFromUri(file, retriever, artistSeparators, genreSeparators)
                            } catch (_: Exception) {
                                createFallbackTrack(file.uri, file.name, file.size, file.lastModified)
                            }
                        } finally {
                            runCatching { retriever.release() }
                        }
                    } else {
                        // Update the date only if the track has no date yet
                        results[index] =
                            if (cached.dateAdded == 0L) cached.copy(dateAdded = file.lastModified) else cached
                    }
                    freshTrack[index] = cached == null
                    val done = processed.incrementAndGet()
                    if (done % 5 == 0 || done == total) {
                        onProgress(done.toFloat() / total, file.name)
                    }
                }
            }
        }

        // Embedded art is per album: probe one representative file per album.
        val artByAlbum = HashMap<String, Boolean>()
        val freshTracks = results.mapIndexedNotNull { index, track -> track?.takeIf { freshTrack[index] } }
        coroutineScope {
            freshTracks.groupBy { it.album }.forEach { (album, albumTracks) ->
                launch(scanDispatcher) {
                    val hasArt = albumHasEmbeddedArt(albumTracks.first())
                    synchronized(artByAlbum) { artByAlbum[album] = hasArt }
                }
            }
        }

        // Reused entries keep their previously stored albumArtPath untouched.
        results.mapIndexed { index, track ->
            when {
                track == null -> null
                !freshTrack[index] -> track
                artByAlbum[track.album] == true -> track.copy(albumArtPath = track.path)
                else -> track.copy(albumArtPath = null)
            }
        }.filterNotNull()
    }

    /** Lists the audio files under the given folders (recursive), without reading metadata. */
    suspend fun listAudioFiles(folderPaths: Set<String>): List<Pair<Uri, String>> = withContext(Dispatchers.IO) {
        collectAudioFiles(folderPaths) { _, _ -> }.map { it.uri to it.name }
    }

    /** Reads only the title/album tags of the given files, keyed by URI. */
    suspend fun readTitlesAlbums(uris: List<Uri>): Map<Uri, Pair<String, String>> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyMap()
        val result = ConcurrentHashMap<Uri, Pair<String, String>>()
        coroutineScope {
            uris.forEach { uri ->
                launch(scanDispatcher) {
                    // Fresh retriever per file: a failed setDataSource poisons the instance.
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
                        if (!title.isNullOrEmpty()) {
                            val album = retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                ?.trim().orEmpty()
                            result[uri] = title to album
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            }
        }
        result
    }

    private suspend fun collectAudioFiles(
        folderPaths: Set<String>,
        onProgress: (Float, String) -> Unit
    ): List<FoundAudioFile> = withContext(Dispatchers.IO) {
        val foundFiles = mutableListOf<FoundAudioFile>()
        folderPaths.forEach { pathString ->
            try {
                val rootUri = pathString.toUri()
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
        // Sort by URI so repeated scans see a stable file order.
        foundFiles.sortBy { it.uri.toString() }
        foundFiles
    }

    private suspend fun collectUrisRecursive(
        rootUri: Uri,
        parentId: String,
        result: MutableList<FoundAudioFile>,
        onProgress: (Float, String) -> Unit
    ) {
        coroutineScope {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            try {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    val subFolderIds = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIdx)
                        val name = cursor.getString(nameIdx) ?: "Unknown"
                        val mime = cursor.getString(mimeIdx)

                        if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                            onProgress(0f, "Searching in $name...")
                            subFolderIds += id
                        } else if (isAudioFile(name)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, id)
                            val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                            val lastModified = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) else 0L
                            synchronized(result) {
                                result.add(FoundAudioFile(docUri, name, size, lastModified))
                            }
                        }
                    }
                    // Each subdirectory listing is a full IPC round trip: walk concurrently.
                    subFolderIds.forEach { id ->
                        launch(scanDispatcher) { collectUrisRecursive(rootUri, id, result, onProgress) }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun collectLegacyFiles(dir: File, result: MutableList<FoundAudioFile>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                collectLegacyFiles(file, result)
            } else if (isAudioFile(file.name)) {
                result.add(FoundAudioFile(Uri.fromFile(file), file.name, file.length(), file.lastModified()))
            }
        }
    }

    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return extensions.any { lower.endsWith(".$it") }
    }

    private fun extractMetadataFromUri(
        file: FoundAudioFile,
        retriever: MediaMetadataRetriever,
        artistSeparators: Set<String>,
        genreSeparators: Set<String>
    ): TrackEntity {
        val uri = file.uri
        val fileName = file.name
        var title = fileName.substringBeforeLast('.')
        var rawArtist = "<Unknown Artist>"
        var album = "<Unknown Album>"
        var rawAlbumArtist: String? = null
        var duration = 0L
        var bitrate = 0
        var sampleRate = 44100
        var bitDepth = 16
        val size = file.size
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var composer: String? = null
        var year: Int? = null

        try {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
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

            // Reported bit depth when the container provides one, extension guess otherwise.
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                ?.toIntOrNull()?.takeIf { it > 0 }?.let { bitDepth = it }
                ?: run {
                    if (fileName.lowercase().let { it.endsWith(".flac") || it.endsWith(".wav") }) {
                        bitDepth = 24
                    }
                }

            composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                ?.trim()?.takeIf { it.isNotEmpty() }
            // YEAR is often absent; DATE carries the value instead ("2021-03-04").
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ?.yearPart()

            // The FLAC extractor may never surface DATE/YEAR or COMPOSER: read
            // the Vorbis comments, which carry both.
            if (fileName.lowercase().endsWith(".flac") && (year == null || composer == null)) {
                readFlacVorbisComments(uri)?.let { comments ->
                    if (year == null) {
                        year = comments["DATE"]?.yearPart()
                            ?: comments["YEAR"]?.yearPart()
                                    ?: comments["ORIGINALDATE"]?.yearPart()
                                    ?: comments["ORIGINALYEAR"]?.yearPart()
                    }
                    if (composer == null) {
                        composer = comments["COMPOSER"]
                            ?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
            }

            // M4A/M4B files often hide ©day from the retriever too.
            if (year == null && fileName.lowercase().let { it.endsWith(".m4a") || it.endsWith(".m4b") }) {
                year = readMp4Year(uri)
            }

            // M4A/M4B files often hide ©wrt (composer) from the retriever too.
            if (composer == null && fileName.lowercase().let { it.endsWith(".m4a") || it.endsWith(".m4b") }) {
                composer = readMp4Composer(uri)
            }

            // Tag values can be "3" or "3/12" — keep the number before the slash.
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
        } catch (e: Exception) {
            return createFallbackTrack(uri, fileName, size, file.lastModified)
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
            albumArtPath = null,
            dateAdded = file.lastModified
        )
    }

    /** True when [track]'s file carries an embedded picture. Never throws. */
    private fun albumHasEmbeddedArt(track: TrackEntity): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track.path.toUri())
            retriever.embeddedPicture != null
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Splits on [separators] and joins with "; " (the app's multi-value convention). */
    private fun splitAndJoin(value: String, separators: Set<String>): String {
        if (separators.isEmpty()) return value
        var result = listOf(value)
        separators.forEach { sep ->
            result = result.flatMap { it.split(sep) }
        }
        return result.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
    }

    private fun createFallbackTrack(uri: Uri, fileName: String, size: Long, lastModified: Long): TrackEntity {
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
            dateAdded = lastModified
        )
    }

    /**
     * Reads a FLAC file's VORBIS_COMMENT block directly; the platform extractor
     * only surfaces a subset of the comment fields. Returns null for non-FLAC
     * or unparseable streams; never throws.
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

    /**
     * Reads an M4A/M4B file's `moov/udta/meta/ilst/©day/data` atom directly;
     * MediaMetadataRetriever often reports no YEAR for MP4 containers. Returns
     * null when the atom is absent or the stream is malformed; never throws.
     */
    private fun readMp4Year(uri: Uri): Int? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val data = findMp4Atom(input, MP4_YEAR_ATOM_PATH, Long.MAX_VALUE) ?: return@use null
            // 'data' box payload: 4-byte type indicator + 4-byte locale, then the text.
            if (data.size <= 8) return@use null
            String(data, 8, data.size - 8, Charsets.UTF_8).yearPart()
        }
    }.getOrNull()

    /**
     * Reads an M4A/M4B file's `moov/udta/meta/ilst/©wrt/data` atom (the MP4
     * composer tag); the platform extractor often reports no COMPOSER for MP4
     * containers. Returns null when the atom is absent; never throws.
     */
    private fun readMp4Composer(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val data = findMp4Atom(input, MP4_COMPOSER_ATOM_PATH, Long.MAX_VALUE) ?: return@use null
            // 'data' box payload: 4-byte type indicator + 4-byte locale, then the text.
            if (data.size <= 8) return@use null
            String(data, 8, data.size - 8, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    /**
     * Walks nested MP4 boxes following [path], skipping boxes not on the path
     * without buffering them. Returns the final box's raw bytes, or null.
     */
    private fun findMp4Atom(input: InputStream, path: List<String>, bytesAvailable: Long): ByteArray? {
        var remaining = bytesAvailable
        val header = ByteArray(8)
        while (remaining >= 8 && readFully(input, header)) {
            val size32 = ((header[0].toLong() and 0xFF) shl 24) or
                    ((header[1].toLong() and 0xFF) shl 16) or
                    ((header[2].toLong() and 0xFF) shl 8) or
                    (header[3].toLong() and 0xFF)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)

            var headerSize = 8L
            var boxSize = size32
            if (size32 == 1L) {
                // 64-bit extended size follows the header for boxes bigger than 4GB.
                val ext = ByteArray(8)
                if (!readFully(input, ext)) return null
                boxSize = ext.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                headerSize = 16L
            }
            if (boxSize < headerSize) return null // malformed box

            val contentSize = boxSize - headerSize
            if (type == path.first()) {
                if (path.size == 1) {
                    if (contentSize !in 1..MAX_MP4_ATOM_BYTES) return null
                    val content = ByteArray(contentSize.toInt())
                    return if (readFully(input, content)) content else null
                }
                // 'meta' is a full box: a 4-byte version/flags precedes its children.
                val skip = if (type == "meta") 4L else 0L
                if (skip > 0 && !skipFully(input, skip)) return null
                return findMp4Atom(input, path.drop(1), contentSize - skip)
            } else {
                if (!skipFully(input, contentSize)) return null
            }
            remaining -= boxSize
        }
        return null
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
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) return false
            remaining -= read
        }
        return true
    }

    /** Legacy no-op kept for compatibility with older callers. */
    suspend fun scanDeviceAudioFiles(): List<TrackEntity> = emptyList()
}

/** Concurrent per-file metadata extractions; MediaMetadataRetriever is a native object, keep it modest. */
private const val SCAN_PARALLELISM = 8

/** Box path to the MP4 "date recorded" tag: moov/udta/meta/ilst/©day/data. */
private val MP4_YEAR_ATOM_PATH = listOf("moov", "udta", "meta", "ilst", "\u00A9day", "data")

/** Box path to the MP4 composer tag: moov/udta/meta/ilst/©wrt/data. */
private val MP4_COMPOSER_ATOM_PATH = listOf("moov", "udta", "meta", "ilst", "\u00A9wrt", "data")

/** The ©day/data atom is a short text tag; refuse anything implausibly large. */
private const val MAX_MP4_ATOM_BYTES = 1 shl 16 // 64 KB

/** First 4-digit group not glued to other digits: "2021-03-04" -> 2021. */
private val YearRegex = Regex("""(?<!\d)\d{4}(?!\d)""")

private fun String.yearPart(): Int? = YearRegex.find(this)?.value?.toIntOrNull()

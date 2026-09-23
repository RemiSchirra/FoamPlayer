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

sealed class ScanStatus {
    object Idle : ScanStatus()
    data class Scanning(val progress: Float, val currentFile: String) : ScanStatus()
    data class Finished(val count: Int) : ScanStatus()
}

/** An audio file found during enumeration, with its size already resolved. */
private data class FoundAudioFile(val uri: Uri, val name: String, val size: Long)

@Singleton
class LocalAudioScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val extensions = listOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "m4b", "opus")
    private val scanDispatcher = Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)

    /**
     * Scans [folderPaths] and extracts metadata for every audio file found.
     * When [knownTracks] contains a track for a file's URI with an unchanged
     * size, it is reused as-is instead of re-reading the file — pass the
     * previous scan's entities to make rescans incremental, or an empty map to
     * force a full extraction. NOTE: callers must not reuse entries produced
     * with different [artistSeparators]/[genreSeparators], since those
     * transform the extracted strings.
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

        // Metadata extraction is I/O + header parsing bound: run it on a bounded
        // worker pool instead of one file at a time. Each file gets its own
        // MediaMetadataRetriever (not thread-safe, released right after use).
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
                                createFallbackTrack(file.uri, file.name, file.size)
                            }
                        } finally {
                            runCatching { retriever.release() }
                        }
                    } else {
                        results[index] = cached
                    }
                    freshTrack[index] = cached == null
                    val done = processed.incrementAndGet()
                    if (done % 5 == 0 || done == total) {
                        onProgress(done.toFloat() / total, file.name)
                    }
                }
            }
        }

        // Embedded art is effectively per album: probing retriever.embeddedPicture
        // decodes the whole cover image, so probe one representative file per album
        // of freshly extracted tracks and apply the verdict to all of its tracks.
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

    /** Lists the audio files under the given folders (recursive) without reading any metadata. */
    suspend fun listAudioFiles(folderPaths: Set<String>): List<Pair<Uri, String>> = withContext(Dispatchers.IO) {
        collectAudioFiles(folderPaths) { _, _ -> }.map { it.uri to it.name }
    }

    /** Reads only the title/album tags of the given audio files (no full scan), keyed by URI. */
    suspend fun readTitlesAlbums(uris: List<Uri>): Map<Uri, Pair<String, String>> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyMap()
        val result = ConcurrentHashMap<Uri, Pair<String, String>>()
        coroutineScope {
            uris.forEach { uri ->
                launch(scanDispatcher) {
                    // A fresh retriever per file: a failed setDataSource can leave the
                    // instance in a bad state that would poison all subsequent files.
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
        // Parallel traversal completes in nondeterministic order: sort by URI so
        // repeated scans see a stable file order (stable progress, stable results).
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
                DocumentsContract.Document.COLUMN_SIZE
            )

            try {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

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
                            // Size comes straight from the directory listing so no extra
                            // openAssetFileDescriptor round-trip is needed per file later.
                            val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                            synchronized(result) {
                                result.add(FoundAudioFile(docUri, name, size))
                            }
                        }
                    }
                    // Every subdirectory listing is a full IPC round trip: walk them
                    // concurrently instead of one directory at a time.
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
                result.add(FoundAudioFile(Uri.fromFile(file), file.name, file.length()))
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
            // Direct file paths skip the ContentResolver layer entirely.
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

            // MediaMetadataRetriever frequently leaves both YEAR and DATE null for
            // M4A/M4B (MP4) files even when the ©day atom is present: read it
            // straight from the container as a last resort, same idea as the FLAC
            // fallback above.
            if (year == null && fileName.lowercase().let { it.endsWith(".m4a") || it.endsWith(".m4b") }) {
                year = readMp4Year(uri)
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
            albumArtPath = null
        )
    }

    /** True when [track]'s file carries an embedded picture. Never throws. */
    private fun albumHasEmbeddedArt(track: TrackEntity): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(track.path))
            retriever.embeddedPicture != null
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
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

    /**
     * Reads an M4A/M4B file's `moov/udta/meta/ilst/©day/data` atom directly.
     * MediaMetadataRetriever often reports no YEAR or DATE for MP4 containers
     * even when the ©day atom is populated, so this walks the box structure
     * by hand. Returns null when the atom is absent or the stream isn't a
     * well-formed MP4; never throws.
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
     * Walks nested MP4 boxes following [path] (e.g. moov -> udta -> meta ->
     * ilst -> ©day -> data), skipping every box not on that path without
     * buffering its contents. Returns the raw bytes of the final box in
     * [path] once reached, or null if any step is missing/malformed.
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
                    if (contentSize <= 0 || contentSize > MAX_MP4_ATOM_BYTES) return null
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

    suspend fun scanDeviceAudioFiles(): List<TrackEntity> = emptyList()
}

/** Concurrent per-file metadata extractions; MediaMetadataRetriever is a native object, keep it modest. */
private const val SCAN_PARALLELISM = 8

/** Box path to the MP4 "date recorded" tag: moov/udta/meta/ilst/©day/data. */
private val MP4_YEAR_ATOM_PATH = listOf("moov", "udta", "meta", "ilst", "\u00A9day", "data")

/** The ©day/data atom is a short text tag; refuse anything implausibly large. */
private const val MAX_MP4_ATOM_BYTES = 1 shl 16 // 64 KB

/** First 4-digit group not glued to other digits: "2021" or "2021-03-04" -> 2021. */
private val YearRegex = Regex("""(?<!\d)\d{4}(?!\d)""")

private fun String.yearPart(): Int? = YearRegex.find(this)?.value?.toIntOrNull()
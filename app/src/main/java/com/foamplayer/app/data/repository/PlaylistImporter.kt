package com.foamplayer.app.data.repository

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.scanner.LocalAudioScanner
import com.foamplayer.app.data.scanner.M3uEntry
import com.foamplayer.app.data.scanner.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The tracks matched in the library and the entries that were not found. */
data class PlaylistMatchResult(
    val matched: List<TrackEntity>,
    val missing: List<String>
)

/**
 * Matches external playlist sources (an .m3u file or a picked folder) against
 * the scanned library, producing the tracks to insert into a new playlist plus
 * the entries that were not found (the "missing songs" report).
 */
@Singleton
class PlaylistImporter @Inject constructor(
    private val scanner: LocalAudioScanner,
    private val m3uParser: M3uParser
) {
    /** Matches an .m3u file: path, then filename, then title/artist metadata. */
    suspend fun importM3u(m3uUri: String, tracks: List<TrackEntity>): PlaylistMatchResult =
        withContext(Dispatchers.Default) {
            val text = m3uParser.readText(Uri.parse(m3uUri))
                ?: return@withContext PlaylistMatchResult(emptyList(), emptyList())
            val entries = m3uParser.parse(text).entries
            val index = TrackPathIndex(tracks)

            val matched = mutableListOf<TrackEntity>()
            val addedIds = HashSet<Long>()
            val missing = mutableListOf<String>()

            entries.forEach { entry ->
                val path = entryPath(entry.path).lowercase()
                val match = index.matchPath(path)
                    ?: index.matchBase(baseName(path))
                    ?: index.matchMetadata(entry.title, entry.artist)
                if (match != null) {
                    if (addedIds.add(match.id)) matched += match
                } else {
                    missing += missingLabel(entry)
                }
            }
            PlaylistMatchResult(matched, missing)
        }

    /** Matches a picked folder's audio files against the library. */
    suspend fun matchFolder(folderUri: String, tracks: List<TrackEntity>): PlaylistMatchResult =
        withContext(Dispatchers.IO) {
            val files = scanner.listAudioFiles(setOf(folderUri))
            val index = TrackPathIndex(tracks)

            val matched = mutableListOf<TrackEntity>()
            val addedIds = HashSet<Long>()
            val missing = mutableListOf<String>()

            val pending = mutableListOf<Pair<Uri, String>>()
            files.forEach { (uri, fileName) ->
                val path = trackLookupPath(uri.toString()).lowercase()
                val match = index.matchPath(path)
                if (match != null) {
                    if (addedIds.add(match.id)) matched += match
                } else {
                    pending += uri to fileName
                }
            }

            // Files unknown by path/filename are matched through their own tags.
            if (pending.isNotEmpty()) {
                val metadata = scanner.readTitlesAlbums(pending.map { it.first })
                pending.forEach { (uri, fileName) ->
                    val match = metadata[uri]?.let { (title, album) ->
                        index.matchTitleAlbum(title, album)
                    }
                    if (match != null) {
                        if (addedIds.add(match.id)) matched += match
                    } else {
                        missing += fileName
                    }
                }
            }

            PlaylistMatchResult(matched, missing)
        }
}

/** In-memory lookup index over the library: by path, filename, title and title+album/artist. */
private class TrackPathIndex(tracks: List<TrackEntity>) {
    private val paths = ArrayList<Pair<String, TrackEntity>>(tracks.size)
    private val byPath = HashMap<String, TrackEntity>(tracks.size)
    private val byBase = HashMap<String, TrackEntity>(tracks.size)
    private val byTitle = HashMap<String, MutableList<TrackEntity>>()

    init {
        tracks.forEach { track ->
            val lookup = trackLookupPath(track.path).lowercase()
            paths += lookup to track
            byPath[lookup] = track
            byBase.getOrPut(baseName(lookup)) { track }
            byTitle.getOrPut(track.title.lowercase()) { mutableListOf() }.add(track)
        }
    }

    fun matchPath(path: String): TrackEntity? =
        byPath[path] ?: paths.firstOrNull { it.first.endsWith("/$path") }?.second

    fun matchBase(base: String): TrackEntity? = byBase[base.takeIf { it.isNotEmpty() } ?: return null]

    fun matchTitleAlbum(title: String?, album: String?): TrackEntity? {
        val normalizedTitle = title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val candidates = byTitle[normalizedTitle] ?: return null
        val normalizedAlbum = album?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return candidates.first()
        return candidates.firstOrNull { it.album.lowercase() == normalizedAlbum }
            ?: candidates.first()
    }

    fun matchMetadata(title: String?, artist: String?): TrackEntity? {
        val normalizedTitle = title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val candidates = byTitle[normalizedTitle] ?: return null
        val normalizedArtist = artist?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return candidates.first()
        return candidates.firstOrNull { candidate ->
            candidate.artist.lowercase().contains(normalizedArtist) ||
                    candidate.albumArtist?.lowercase()?.contains(normalizedArtist) == true
        } ?: candidates.first()
    }
}

/** Turns a stored track path (SAF document URI or file URI) into a comparable document-id-like path. */
private fun trackLookupPath(path: String): String {
    if (path.startsWith("content:")) {
        return runCatching { DocumentsContract.getDocumentId(path.toUri()) }
            .getOrElse { Uri.parse(path).lastPathSegment ?: path }
    }
    return decodeSafely(path.removePrefix("file://"))
}

/** Normalizes an M3U entry (relative path, file:// URI, backslashes) for comparison. */
private fun entryPath(raw: String): String {
    val withoutScheme = if (raw.startsWith("file://", ignoreCase = true)) raw.substring(7) else raw
    return decodeSafely(withoutScheme.replace('\\', '/')).trimStart('.', '/')
}

private fun baseName(path: String): String =
    path.substringAfterLast('/').substringBeforeLast('.')

private fun decodeSafely(value: String): String =
    runCatching { Uri.decode(value) }.getOrDefault(value)

private fun missingLabel(entry: M3uEntry): String =
    when {
        !entry.title.isNullOrBlank() && !entry.artist.isNullOrBlank() -> "${entry.artist} – ${entry.title}"
        !entry.title.isNullOrBlank() -> entry.title!!
        else -> entryPath(entry.path)
    }

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
    /**
     * Matches an .m3u file: path, then filename, then title/artist metadata.
     * Paths are matched through several candidates because M3U authors disagree
     * about encoding: plain entries keep their literal name ("0%20Angel.mp3"
     * stays as written) while file:// URIs are percent-decoded once.
     */
    suspend fun importM3u(m3uUri: String, tracks: List<TrackEntity>): PlaylistMatchResult =
        withContext(Dispatchers.Default) {
            val text = m3uParser.readText(m3uUri.toUri())
                ?: return@withContext PlaylistMatchResult(emptyList(), emptyList())
            val entries = m3uParser.parse(text).entries
            val index = TrackPathIndex(tracks)

            val matched = mutableListOf<TrackEntity>()
            val addedIds = HashSet<Long>()
            val missing = mutableListOf<String>()

            entries.forEach { entry ->
                val match = entryPathCandidates(entry.path).firstNotNullOfOrNull { candidate ->
                    index.matchPath(candidate.lowercase())
                        ?: index.matchBase(baseName(candidate).lowercase())
                }
                val resolved = match ?: index.matchMetadata(entry.title, entry.artist)
                if (resolved != null) {
                    if (addedIds.add(resolved.id)) matched += resolved
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
            .getOrElse { path.toUri().lastPathSegment ?: path }
    }
    return decodeSafely(path.removePrefix("file://"))
}

/**
 * Path forms tried for one M3U entry, in order:
 * 1. The literal line with backslashes normalized and dot-slash prefixes
 *    trimmed — how plain-filesystem playlists reference the file.
 * 2. The percent-decoded form — how file:// URIs (and tools that wrongly
 *    URL-encode plain entries) reference it.
 *
 * Percent-decoding unconditionally was the bug behind "0%20Angel.mp3" never
 * matching: the decode rewrote the name to "0 Angel.mp3" while the library
 * kept the literal percent.
 */
private fun entryPathCandidates(raw: String): List<String> {
    val normalized = raw.replace('\\', '/').trimStart('.', '/')
    val decoded = decodeSafely(normalized)
    val forms = ArrayList<String>(2)
    forms += normalized
    if (decoded != normalized) forms += decoded
    return forms
}

private fun baseName(path: String): String =
    path.substringAfterLast('/').substringBeforeLast('.')

private fun decodeSafely(value: String): String =
    runCatching { Uri.decode(value) }.getOrDefault(value)

private fun missingLabel(entry: M3uEntry): String =
    when {
        !entry.title.isNullOrBlank() && !entry.artist.isNullOrBlank() -> "${entry.artist} – ${entry.title}"
        !entry.title.isNullOrBlank() -> entry.title!!
        else -> entry.path
    }

package com.foamplayer.app.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.foamplayer.app.data.db.dao.CachedLyricsDao
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.lyrics.providers.EmbeddedLyricsProvider
import com.foamplayer.app.lyrics.providers.LocalLyricsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Library-wide lyrics source scan behind the lyrics manager screen.
 *
 * The scan is shaped around two facts: every track's sidecar lives in the
 * track's own folder, and embedded lyrics can only change when the audio file
 * itself is rewritten. Instead of inspecting every track independently, it:
 *
 *  - resolves the online cache with a single Room query for all track ids,
 *  - groups tracks by parent folder so one directory listing (SAF children
 *    query or [File.listFiles]) serves every track in that folder,
 *  - runs the expensive embedded-tag parse only for tracks whose audio file
 *    changed (size + last-modified fingerprint) since the last scan; verdicts
 *    are reused across scans within the app session.
 *
 * Results are emitted incrementally through [onStatus] as folders complete,
 * so the UI fills in progressively while the scan runs.
 */
@Singleton
class LyricsStatusScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cachedLyricsDao: CachedLyricsDao,
    private val localLyricsProvider: LocalLyricsProvider,
    private val embeddedLyricsProvider: EmbeddedLyricsProvider
) {
    /** Cached embedded-lyrics verdict for one track, valid while the audio file keeps [fingerprint]. */
    private data class EmbeddedScanEntry(val fingerprint: Long, val hasEmbedded: Boolean)

    /** One entry of a folder listing; keys are lowercase file names. */
    private data class ListedChild(val size: Long, val lastModified: Long)

    private val embeddedScans = ConcurrentHashMap<Long, EmbeddedScanEntry>()

    /**
     * Scans [tracks] and reports each track's [LyricsSourceStatus] through
     * [onStatus] (invoked from background threads). Safe to call repeatedly:
     * unchanged tracks skip the directory and tag IO entirely.
     */
    suspend fun scan(tracks: List<TrackEntity>, onStatus: (Long, LyricsSourceStatus) -> Unit) =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext

            val cachedIds = runCatching { cachedLyricsDao.getCachedTrackIds().toSet() }.getOrDefault(emptySet())
            embeddedScans.keys.retainAll(tracks.mapTo(HashSet()) { it.id })

            val folderLimiter = Semaphore(FOLDER_CONCURRENCY)
            val parseLimiter = Semaphore(EMBEDDED_PARSE_CONCURRENCY)
            coroutineScope {
                tracks.groupBy { folderKey(it) }.values.forEach { folderTracks ->
                    launch {
                        folderLimiter.withPermit { scanFolder(folderTracks, cachedIds, parseLimiter, onStatus) }
                    }
                }
            }
        }

    /** Drops the cached embedded-lyrics verdict for [trackId]; the next scan re-reads the file. */
    fun invalidate(trackId: Long) {
        embeddedScans.remove(trackId)
    }

    private suspend fun scanFolder(
        tracks: List<TrackEntity>,
        cachedIds: Set<Long>,
        parseLimiter: Semaphore,
        onStatus: (Long, LyricsSourceStatus) -> Unit
    ) = coroutineScope {
        val listing = listingFor(tracks.first())
        tracks.map { track ->
            async {
                val status = computeStatus(track, listing, cachedIds, parseLimiter)
                onStatus(track.id, status)
            }
        }.awaitAll()
    }

    private suspend fun computeStatus(
        track: TrackEntity,
        listing: Map<String, ListedChild>?,
        cachedIds: Set<Long>,
        parseLimiter: Semaphore
    ): LyricsSourceStatus {
        val hasCached = track.id in cachedIds

        if (listing == null) {
            // Unrecognized URI scheme: fall back to the per-track providers.
            val hasFile = localLyricsProvider.getLyrics(track.path) != null
            val hasEmbedded = parseEmbedded(track, parseLimiter)
            return LyricsSourceStatus(hasCached, hasFile, hasEmbedded)
        }

        val trackName = trackFileName(track)?.lowercase()
            ?: return LyricsSourceStatus(hasCached, hasFile = false, hasEmbedded = false)
        val baseName = trackName.substringBeforeLast('.')
        val hasFile = listing.containsKey("$baseName.ttml") || listing.containsKey("$baseName.lrc")

        val fingerprint = listing[trackName]?.fingerprint() ?: 0L
        val hasEmbedded = if (fingerprint != 0L) {
            embeddedScans[track.id]?.takeIf { it.fingerprint == fingerprint }?.hasEmbedded
                ?: parseLimiter.withPermit {
                    val has = embeddedLyricsProvider.getLyrics(track.path.toUri()) != null
                    embeddedScans[track.id] = EmbeddedScanEntry(fingerprint, has)
                    has
                }
        } else {
            // No reliable file metadata: re-check without caching the verdict.
            parseEmbedded(track, parseLimiter)
        }

        return LyricsSourceStatus(hasCached, hasFile, hasEmbedded)
    }

    private suspend fun parseEmbedded(track: TrackEntity, parseLimiter: Semaphore): Boolean =
        parseLimiter.withPermit { embeddedLyricsProvider.getLyrics(track.path.toUri()) != null }

    /** Groups tracks that share a parent folder so one directory listing serves all of them. */
    private fun folderKey(track: TrackEntity): String? {
        val uri = runCatching { Uri.parse(track.path) }.getOrNull() ?: return null
        return when (uri.scheme) {
            "content" -> runCatching {
                val documentId = DocumentsContract.getDocumentId(uri)
                "content:" + documentId.substringBeforeLast('/', missingDelimiterValue = documentId)
            }.getOrNull()
            "file" -> uri.path?.let { "file:" + File(it).parent }
            else -> null
        }
    }

    /** The audio document's file name, used to find its entry in the folder listing. */
    private fun trackFileName(track: TrackEntity): String? {
        val uri = runCatching { Uri.parse(track.path) }.getOrNull() ?: return null
        return when (uri.scheme) {
            "content" -> runCatching { DocumentsContract.getDocumentId(uri).substringAfterLast('/') }.getOrNull()
            "file" -> uri.lastPathSegment?.substringAfterLast('/')
            else -> null
        }
    }

    private fun listingFor(track: TrackEntity): Map<String, ListedChild>? {
        val uri = runCatching { Uri.parse(track.path) }.getOrNull() ?: return null
        return when (uri.scheme) {
            "content" -> runCatching { listSafChildren(uri) }.getOrDefault(emptyMap())
            "file" -> listFileChildren(uri)
            else -> null
        }
    }

    /** One SAF children query per folder, including the metadata used to fingerprint the audio file. */
    private fun listSafChildren(trackUri: Uri): Map<String, ListedChild> {
        val documentId = DocumentsContract.getDocumentId(trackUri)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(trackUri)
        val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = documentId)
        val treeUri = DocumentsContract.buildTreeDocumentUri(trackUri.authority, treeDocumentId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val result = HashMap<String, ListedChild>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.lowercase() ?: continue
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                val lastModified = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) else 0L
                result[name] = ListedChild(size, lastModified)
            }
        }
        return result
    }

    private fun listFileChildren(trackUri: Uri): Map<String, ListedChild> {
        val trackFile = File(trackUri.path ?: return emptyMap())
        val children = trackFile.parentFile?.listFiles() ?: return emptyMap()
        return children.associateTo(HashMap()) { child ->
            child.name.lowercase() to ListedChild(child.length(), child.lastModified())
        }
    }

    /** Change signal for the audio file; nonzero only when both metadata values are known. */
    private fun ListedChild.fingerprint(): Long =
        if (size > 0L && lastModified > 0L) 31L * size + lastModified else 0L

    private companion object {
        /** Parallel directory listings; one listing serves every track in its folder. */
        const val FOLDER_CONCURRENCY = 8

        /** Cap on concurrent embedded-tag parses (stream opens) across all folders. */
        const val EMBEDDED_PARSE_CONCURRENCY = 16
    }
}

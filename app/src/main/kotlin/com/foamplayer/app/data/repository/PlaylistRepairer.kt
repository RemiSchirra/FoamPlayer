package com.foamplayer.app.data.repository

import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.foamplayer.app.data.db.AppDatabase
import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.TrackDeletionLogEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of one repair run, for the UI's summary sheet. */
data class PlaylistRepairResult(
    /** Dangling memberships re-pointed at the re-scanned row of the same file. */
    val relinkedCount: Int,
    /** Stale memberships dropped because nothing identifies their track anymore. */
    val removedCount: Int,
    /** Logged deletions with no library match yet; their references are kept. */
    val unmatchedCount: Int,
) {
    /** True when the run changed at least one playlist membership. */
    val hasChanges: Boolean get() = relinkedCount > 0 || removedCount > 0
}

/**
 * Re-links playlist entries whose track id a library rescan invalidated: a
 * moved file is re-scanned under a new path with a fresh autoGenerate id, the
 * old row is deleted by the orphan cleanup, and the playlist's cross-reference
 * silently dangles (getTracksForPlaylist's INNER JOIN hides the entry while
 * getPlaylistTrackCounts still counts it).
 *
 * Rescans log every deleted track's identity into track_deletion_log; a
 * repair run resolves each dangling cross-reference against the current
 * library — the physical signature (size + duration + title) first, then the
 * logged tag metadata, then the bare file name — rewrites the reference and
 * consumes the log entry. Each library track is claimed at most once per
 * playlist per run so two broken entries never collapse onto the same song.
 */
@Singleton
class PlaylistRepairer @Inject constructor(
    private val db: AppDatabase,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao
) {
    suspend fun repair(): PlaylistRepairResult = withContext(Dispatchers.IO) {
        val orphaned = playlistDao.getOrphanedTrackRefs()
        if (orphaned.isEmpty()) return@withContext PlaylistRepairResult(0, 0, 0)

        db.withTransaction {
            val deletedByTrackId = trackDao.getDeletionLog().associateBy { it.trackId }
            val liveTracks = trackDao.getAllTracksOnce()
            val liveIds = liveTracks.mapTo(HashSet()) { it.id }
            val index = TrackMatchIndex(liveTracks)

            // Per-playlist claim sets seeded with the playlist's live track
            // ids: a repair must never duplicate an existing membership, and
            // two orphans of the same playlist must not resolve to one song.
            val claimedByPlaylist = HashMap<Long, HashSet<Long>>()
            suspend fun claimsFor(playlistId: Long): HashSet<Long> =
                claimedByPlaylist.getOrPut(playlistId) {
                    playlistDao.getTrackIdsForPlaylistOnce(playlistId)
                        .filterTo(HashSet()) { it in liveIds }
                }

            var relinked = 0
            var removed = 0
            var unmatched = 0
            val consumedLogIds = ArrayList<Long>()

            for (ref in orphaned) {
                val deleted = deletedByTrackId[ref.trackId]
                if (deleted == null) {
                    // Deleted before the log existed (or the entry was pruned):
                    // nothing identifies the track anymore, and the dangling
                    // membership only inflates the playlist's track count.
                    playlistDao.removeTrackFromPlaylist(ref.playlistId, ref.trackId)
                    removed++
                    continue
                }
                val match = index.match(deleted, claimsFor(ref.playlistId))
                if (match == null) {
                    // The file may come back (a disconnected drive, a later
                    // scan): keep both the logged identity and the reference
                    // for a future run.
                    unmatched++
                    continue
                }
                playlistDao.retargetPlaylistTrack(ref.playlistId, ref.trackId, match.id)
                claimsFor(ref.playlistId).add(match.id)
                consumedLogIds.add(ref.trackId)
                relinked++
            }

            if (consumedLogIds.isNotEmpty()) {
                trackDao.deleteDeletionLogByTrackIds(consumedLogIds)
            }
            trackDao.pruneDeletionLog(System.currentTimeMillis() - LOG_RETENTION_MS)
            PlaylistRepairResult(relinked, removed, unmatched)
        }
    }

    private companion object {
        /** Deletion log entries older than this are pruned on every repair run. */
        const val LOG_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }
}

/**
 * Match index over the current library, keyed by the traits a deleted track's
 * snapshot preserves. Tiers, strongest first:
 *  1. size + duration + title — the moved file's physical signature.
 *  2. title + artist + album — tag metadata.
 *  3. title alone.
 *  4. file name + size — for files whose tags were rewritten in the move too.
 */
private class TrackMatchIndex(tracks: List<TrackEntity>) {
    private val bySignature = HashMap<String, MutableList<TrackEntity>>()
    private val byTags = HashMap<String, MutableList<TrackEntity>>()
    private val byTitle = HashMap<String, MutableList<TrackEntity>>()
    private val byNameAndSize = HashMap<String, MutableList<TrackEntity>>()

    init {
        for (track in tracks) {
            val title = normalize(track.title)
            if (title.isNotEmpty()) {
                bySignature.getOrPut("${track.size}|${track.duration}|$title") { ArrayList() }.add(track)
                byTags.getOrPut("$title|${normalize(track.artist)}|${normalize(track.album)}") { ArrayList() }.add(track)
                byTitle.getOrPut(title) { ArrayList() }.add(track)
            }
            byNameAndSize.getOrPut("${fileName(track.path)}|${track.size}") { ArrayList() }.add(track)
        }
    }

    /** Strongest tier still holding a candidate the playlist hasn't claimed. */
    fun match(deleted: TrackDeletionLogEntity, claimed: Set<Long>): TrackEntity? {
        val title = normalize(deleted.title)
        if (title.isNotEmpty()) {
            bySignature["${deleted.size}|${deleted.duration}|$title"]
                ?.firstOrNull { it.id !in claimed }?.let { return it }
            byTags["$title|${normalize(deleted.artist)}|${normalize(deleted.album)}"]
                ?.firstOrNull { it.id !in claimed }?.let { return it }
            byTitle[title]?.firstOrNull { it.id !in claimed }?.let { return it }
        }
        return byNameAndSize["${fileName(deleted.path)}|${deleted.size}"]
            ?.firstOrNull { it.id !in claimed }
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    /**
     * File name extracted the same way [com.foamplayer.app.data.repository.PlaylistImporter]
     * compares paths: the decoded document id of a SAF URI ("primary:Music/Foo
     * Bar.mp3"), the bare path of a file:// URI, else the raw string — so the
     * names extracted on both sides agree.
     */
    private fun fileName(path: String): String {
        val comparable = if (path.startsWith("content:")) {
            runCatching { DocumentsContract.getDocumentId(path.toUri()) }
                .getOrElse { path.toUri().lastPathSegment.orEmpty() }
        } else {
            path.removePrefix("file://")
        }
        return comparable.substringAfterLast('/').substringBeforeLast('.').lowercase()
    }
}

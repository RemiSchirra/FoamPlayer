package com.foamplayer.app.lastfm

import androidx.room.withTransaction
import com.foamplayer.app.data.db.AppDatabase
import com.foamplayer.app.data.db.dao.ListenHistoryDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.ListenHistoryEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.lastfm.LastFmClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One Last.fm track that the import could not match to a library track. */
data class LastFmUnmatchedTrack(
    val artist: String,
    val title: String,
    val playCount: Int
)

/** Live progress of an import; [saving] is true once fetching has finished. */
data class LastFmImportProgress(
    val page: Int = 0,
    val totalPages: Int = 0,
    val scrobbles: Int = 0,
    val saving: Boolean = false
) {
    val fraction: Float
        get() = when {
            saving -> 1f
            totalPages <= 0 -> 0f
            else -> (page.toFloat() / totalPages).coerceIn(0f, 1f)
        }
}

/** Outcome of an import, including the scrobbles that had no library match. */
data class LastFmImportResult(
    val scrobbles: Int,
    val matchedListens: Int,
    val matchedTracks: Int,
    val unmatchedTracks: List<LastFmUnmatchedTrack>
)

/**
 * Downloads a Last.fm account's complete scrobble history and folds it into the
 * local listening statistics.
 *
 * Scrobbles are matched to library tracks by normalized artist + title, with a
 * unique-title fallback. Each match adds one listen to the track's counter,
 * advances its last-played date and appends the scrobble timestamp to
 * `listen_history` so the period-based stats screens include the imported data.
 * With [import] `eraseHistory = true`, every track's counter/date and the whole
 * listen history are reset first; user scores are preserved.
 */
@Singleton
class LastFmHistoryImporter @Inject constructor(
    private val db: AppDatabase,
    private val trackDao: TrackDao,
    private val listenHistoryDao: ListenHistoryDao
) {

    suspend fun import(
        client: LastFmClient,
        username: String,
        eraseHistory: Boolean,
        onProgress: (LastFmImportProgress) -> Unit = {}
    ): LastFmImportResult = withContext(Dispatchers.Default) {
        val trackIndex = LibraryTrackIndex(trackDao.getAllTracksOnce())
        val listensByTrack = HashMap<Long, MutableList<Long>>()
        val unmatched = HashMap<String, UnmatchedAccumulator>()
        val seenScrobbles = HashSet<String>()

        var fetched = 0
        var matched = 0
        var page = 1
        var totalPages = 1

        while (page <= totalPages) {
            coroutineContext.ensureActive()

            val pageResult = client.fetchRecentTracks(username = username, page = page)
                .getOrElse { throw it }

            // A missing/zero total just means "keep asking until a page is empty".
            if (pageResult.totalPages > 0) {
                totalPages = pageResult.totalPages
            } else if (pageResult.tracks.isNotEmpty()) {
                totalPages = page + 1
            }

            pageResult.tracks.forEach { scrobble ->
                val artistKey = normalize(scrobble.artist)
                val titleKey = normalize(scrobble.title)
                val key = "${scrobble.timestampSeconds}\n$artistKey\n$titleKey"
                // Pages shift while a new scrobble arrives mid-import; skip the
                // duplicate instead of counting that listen twice.
                if (!seenScrobbles.add(key)) return@forEach

                fetched++
                val track = trackIndex.match(
                    artist = scrobble.artist,
                    title = scrobble.title,
                    album = scrobble.album
                )
                if (track != null) {
                    matched++
                    listensByTrack.getOrPut(track.id) { mutableListOf() }
                        .add(scrobble.timestampSeconds * 1000L)
                } else {
                    unmatched.getOrPut("$artistKey\n$titleKey") {
                        UnmatchedAccumulator(scrobble.artist.trim(), scrobble.title.trim())
                    }.add()
                }
            }

            onProgress(
                LastFmImportProgress(
                    page = page,
                    totalPages = totalPages,
                    scrobbles = fetched
                )
            )

            if (pageResult.tracks.isEmpty() || page >= totalPages) break
            page++
        }

        onProgress(
            LastFmImportProgress(
                page = page,
                totalPages = totalPages,
                scrobbles = fetched,
                saving = true
            )
        )

        // One transaction: clearing (when requested), the history rows and the
        // per-track counters land together or not at all.
        db.withTransaction {
            if (eraseHistory) {
                trackDao.resetAllPlayCounts()
                listenHistoryDao.clearAll()
            }

            listensByTrack.forEach { (trackId, timestamps) ->
                val lastPlayedAt = timestamps.maxOrNull() ?: return@forEach
                trackDao.addImportedPlays(
                    trackId = trackId,
                    plays = timestamps.size,
                    lastPlayedAt = lastPlayedAt
                )
                timestamps.chunked(LISTEN_INSERT_CHUNK).forEach { chunk ->
                    listenHistoryDao.insertListens(
                        chunk.map { listenedAt ->
                            ListenHistoryEntity(trackId = trackId, listenedAt = listenedAt)
                        }
                    )
                }
            }
        }

        LastFmImportResult(
            scrobbles = fetched,
            matchedListens = matched,
            matchedTracks = listensByTrack.size,
            unmatchedTracks = unmatched.values
                .map { LastFmUnmatchedTrack(it.artist, it.title, it.playCount) }
                .sortedWith(
                    compareByDescending<LastFmUnmatchedTrack> { it.playCount }
                        .thenBy { it.artist.lowercase() }
                        .thenBy { it.title.lowercase() }
                )
        )
    }

    private class UnmatchedAccumulator(
        val artist: String,
        val title: String
    ) {
        var playCount: Int = 0
            private set

        fun add() {
            playCount++
        }
    }

    private companion object {
        /** listen_history rows per insert statement (3 host variables per row). */
        const val LISTEN_INSERT_CHUNK = 300
    }
}

private val WHITESPACE = Regex("\\s+")

private fun normalize(value: String?): String =
    value.orEmpty()
        .replace('\u00A0', ' ')
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")

/**
 * Matches Last.fm artist/title pairs against the library. Exact artist + title
 * wins (preferring the scrobble's album when several versions exist); when the
 * artist differs but the title is unique in the library, that track is used.
 */
private class LibraryTrackIndex(tracks: List<TrackEntity>) {
    private val byArtistTitle = HashMap<String, MutableList<TrackEntity>>()
    private val byTitle = HashMap<String, MutableList<TrackEntity>>()

    init {
        tracks.forEach { track ->
            val title = normalize(track.title)
            if (title.isEmpty()) return@forEach
            byArtistTitle.getOrPut("${normalize(track.artist)}\n$title") { mutableListOf() }
                .add(track)
            byTitle.getOrPut(title) { mutableListOf() }.add(track)
        }
    }

    fun match(artist: String, title: String, album: String?): TrackEntity? {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return null

        byArtistTitle["${normalize(artist)}\n$normalizedTitle"]?.let { candidates ->
            val normalizedAlbum = normalize(album)
            if (normalizedAlbum.isNotEmpty()) {
                candidates.firstOrNull { normalize(it.album) == normalizedAlbum }
                    ?.let { return it }
            }
            return candidates.first()
        }

        return byTitle[normalizedTitle]?.singleOrNull()
    }
}

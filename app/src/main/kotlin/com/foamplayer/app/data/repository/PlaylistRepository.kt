package com.foamplayer.app.data.repository

import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.PlaylistRecommendation
import com.foamplayer.app.data.db.dao.PlaylistTrackCount
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.PlaylistTrackCrossRef
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.data.scanner.PlaylistCoverProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Where a track added from the add-to-playlist picker lands inside the
 * playlist's order.
 */
enum class PlaylistInsertPosition { START, END, RANDOM }

/** Playlist CRUD and ordering, on top of [PlaylistDao]. */
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val playlistCoverProvider: PlaylistCoverProvider
) {
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    /**
     * Resolved covers per playlist id: the user's custom image when set, else
     * the first track in the playlist's custom order that carries embedded
     * art (see [PlaylistCoverProvider]); a missing entry means "letter fallback".
     */
    fun getPlaylistCovers(): Flow<Map<Long, PlaylistCover>> =
        playlistCoverProvider.coversFlow(playlistDao.getAllPlaylists())

    fun getTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>> =
        playlistDao.getTracksForPlaylist(playlistId)

    /** Live ids of the playlists that contain a track; pre-checks their rows in the add-to-playlist picker. */
    fun playlistIdsContainingTrack(trackId: Long): Flow<Set<Long>> =
        playlistDao.playlistIdsContainingTrack(trackId).map { it.toSet() }

    /** Creates a playlist and returns its id. */
    suspend fun createPlaylist(name: String, imageUrl: String? = null): Long =
        playlistDao.createPlaylist(
            PlaylistEntity(
                name = name,
                imageUrl = imageUrl,
                sortOrder = playlistDao.nextPlaylistSortOrder()
            )
        )

    /** Persists a manual playlist ordering (edit-mode drag & drop). */
    suspend fun reorderPlaylists(orderedPlaylistIds: List<Long>) {
        playlistDao.reorderPlaylists(orderedPlaylistIds.mapIndexed { index, playlistId ->
            playlistId to index
        })
    }

    suspend fun renamePlaylist(playlistId: Long, name: String) {
        playlistDao.renamePlaylist(playlistId, name)
    }

    suspend fun setPlaylistImage(playlistId: Long, imageUrl: String?) {
        playlistDao.setPlaylistImage(playlistId, imageUrl)
    }

    fun getPlaylistTrackCounts(): Flow<List<PlaylistTrackCount>> =
        playlistDao.getPlaylistTrackCounts()

    /** Playlists ranked by the aggregate play count + likes of their tracks (Home row + see-all page). */
    fun getRecommendedPlaylists(): Flow<List<PlaylistRecommendation>> =
        playlistDao.getRecommendedPlaylists()

    suspend fun getTracksForPlaylistOnce(playlistId: Long): Flow<List<TrackEntity>> =
        playlistDao.getTracksForPlaylist(playlistId)

    /** Live first-track-art fallback of one playlist in its custom order (detail hero). */
    fun getFirstTrackArtForPlaylist(playlistId: Long): Flow<String?> =
        playlistDao.getFirstTrackArtForPlaylist(playlistId)

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long, orderIndex: Int) {
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId, orderIndex))
    }

    /**
     * Adds a track to a playlist at [position]'s slot: start (index 0, later
     * tracks shift down), end (append), or a uniformly random slot among the
     * existing order (0..count inclusive, so the track can land anywhere
     * including both ends).
     */
    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long, position: PlaylistInsertPosition) {
        when (position) {
            PlaylistInsertPosition.START ->
                playlistDao.insertTrackAtIndex(playlistId, trackId, 0)
            PlaylistInsertPosition.END ->
                appendTrackToPlaylist(playlistId, trackId)
            PlaylistInsertPosition.RANDOM -> {
                val count = playlistDao.getTrackCountForPlaylist(playlistId)
                val index = if (count == 0) 0 else Random.nextInt(count + 1)
                playlistDao.insertTrackAtIndex(playlistId, trackId, index)
            }
        }
    }

    /** Appends a track at the end of a playlist's current order. */
    suspend fun appendTrackToPlaylist(playlistId: Long, trackId: Long) {
        addTrackToPlaylist(playlistId, trackId, playlistDao.nextOrderIndex(playlistId))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    /** Persists a manual track ordering within one playlist (edit-mode drag & drop). */
    suspend fun reorderPlaylistTracks(playlistId: Long, orderedTrackIds: List<Long>) {
        playlistDao.reorderPlaylistTracks(
            playlistId,
            orderedTrackIds.mapIndexed { index, trackId -> trackId to index }
        )
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }
}

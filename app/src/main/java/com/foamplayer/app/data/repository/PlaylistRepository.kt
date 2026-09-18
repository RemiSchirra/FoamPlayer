package com.foamplayer.app.data.repository

import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.PlaylistRecommendation
import com.foamplayer.app.data.db.dao.PlaylistTrackCount
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.PlaylistTrackCrossRef
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao
) {
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    fun getTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>> =
        playlistDao.getTracksForPlaylist(playlistId)

    /** Live ids of the playlists that contain a track; pre-checks their rows in the add-to-playlist picker. */
    fun playlistIdsContainingTrack(trackId: Long): Flow<Set<Long>> =
        playlistDao.playlistIdsContainingTrack(trackId).map { it.toSet() }

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

    fun getTracksForPlaylistOnce(playlistId: Long): Flow<List<TrackEntity>> =
        playlistDao.getTracksForPlaylist(playlistId)

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long, orderIndex: Int) {
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId, orderIndex))
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
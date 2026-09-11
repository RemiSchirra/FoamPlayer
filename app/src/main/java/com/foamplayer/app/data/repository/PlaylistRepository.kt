package com.foamplayer.app.data.repository

import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.PlaylistTrackCrossRef
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao
) {
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    fun getTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>> =
        playlistDao.getTracksForPlaylist(playlistId)

    suspend fun createPlaylist(name: String): Long =
        playlistDao.createPlaylist(PlaylistEntity(name = name))

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long, orderIndex: Int) {
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId, orderIndex))
    }

    /** Appends a track at the end of a playlist's current order. */
    suspend fun appendTrackToPlaylist(playlistId: Long, trackId: Long) {
        addTrackToPlaylist(playlistId, trackId, playlistDao.nextOrderIndex(playlistId))
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }
}
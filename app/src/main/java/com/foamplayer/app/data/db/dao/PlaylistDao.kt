package com.foamplayer.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.PlaylistTrackCrossRef
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef)

    @Query("""
        SELECT COALESCE(MAX(orderIndex), -1) + 1 
        FROM playlist_track_cross_ref 
        WHERE playlistId = :playlistId
    """)
    suspend fun nextOrderIndex(playlistId: Long): Int

    @Query("""
        SELECT t.* FROM tracks t 
        INNER JOIN playlist_track_cross_ref ref ON t.id = ref.trackId 
        WHERE ref.playlistId = :playlistId 
        ORDER BY ref.orderIndex ASC
    """)
    fun getTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)
}
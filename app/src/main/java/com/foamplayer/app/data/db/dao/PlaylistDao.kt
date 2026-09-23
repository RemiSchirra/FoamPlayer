package com.foamplayer.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.PlaylistTrackCrossRef
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.Flow

/** Track count per playlist, for list-row subtitles. */
data class PlaylistTrackCount(val playlistId: Long, val trackCount: Int)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC, name ASC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef)

    @Query("UPDATE playlists SET name = :name WHERE playlistId = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    @Query("UPDATE playlists SET imageUrl = :imageUrl WHERE playlistId = :playlistId")
    suspend fun setPlaylistImage(playlistId: Long, imageUrl: String?)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM playlists")
    suspend fun nextPlaylistSortOrder(): Int

    @Query("UPDATE playlists SET sortOrder = :sortOrder WHERE playlistId = :playlistId")
    suspend fun setPlaylistSortOrder(playlistId: Long, sortOrder: Int)

    /** Persists a full drag-and-drop reordering in one transaction (no intermediate emissions). */
    @Transaction
    suspend fun reorderPlaylists(orders: List<Pair<Long, Int>>) {
        orders.forEach { (playlistId, sortOrder) ->
            setPlaylistSortOrder(playlistId, sortOrder)
        }
    }

    @Query("""
        SELECT playlistId, COUNT(*) AS trackCount 
        FROM playlist_track_cross_ref 
        GROUP BY playlistId
    """)
    fun getPlaylistTrackCounts(): Flow<List<PlaylistTrackCount>>

    @Query("""
        SELECT COALESCE(MAX(orderIndex), -1) + 1 
        FROM playlist_track_cross_ref 
        WHERE playlistId = :playlistId
    """)
    suspend fun nextOrderIndex(playlistId: Long): Int

    @Query("""
        DELETE FROM playlist_track_cross_ref 
        WHERE playlistId = :playlistId AND trackId = :trackId
    """)
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    @Query("""
        UPDATE playlist_track_cross_ref 
        SET orderIndex = :orderIndex 
        WHERE playlistId = :playlistId AND trackId = :trackId
    """)
    suspend fun setPlaylistTrackOrderIndex(playlistId: Long, trackId: Long, orderIndex: Int)

    /** Persists a full drag-and-drop track reordering in one transaction (no intermediate emissions). */
    @Transaction
    suspend fun reorderPlaylistTracks(playlistId: Long, orders: List<Pair<Long, Int>>) {
        orders.forEach { (trackId, orderIndex) ->
            setPlaylistTrackOrderIndex(playlistId, trackId, orderIndex)
        }
    }

    @Query("""
        SELECT t.* FROM tracks t 
        INNER JOIN playlist_track_cross_ref ref ON t.id = ref.trackId 
        WHERE ref.playlistId = :playlistId 
        ORDER BY ref.orderIndex ASC
    """)
    fun getTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    /** Ids of the playlists that contain a track; pre-checks their rows in the add-to-playlist picker. */
    @Query("SELECT playlistId FROM playlist_track_cross_ref WHERE trackId = :trackId")
    fun playlistIdsContainingTrack(trackId: Long): Flow<List<Long>>

    /**
     * Home "Recommended playlists": playlists ranked by the aggregate play
     * count + likes of the songs they contain. Empty playlists (no tracks) are
     * excluded — they have nothing to recommend.
     */
    @Query("""
        SELECT p.playlistId AS playlistId, 
               p.name AS name, 
               p.imageUrl AS imageUrl, 
               COUNT(t.id) AS trackCount, 
               SUM(t.playCount + MAX(t.score, 0) * 10) AS totalScore 
        FROM playlists p 
        INNER JOIN playlist_track_cross_ref ref ON ref.playlistId = p.playlistId 
        INNER JOIN tracks t ON t.id = ref.trackId 
        GROUP BY p.playlistId 
        ORDER BY totalScore DESC, p.name COLLATE NOCASE ASC 
        LIMIT 50
    """)
    fun getRecommendedPlaylists(): Flow<List<PlaylistRecommendation>>

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    /** Ids of one playlist's tracks in play order, for backup export. */
    @Query("""
        SELECT trackId FROM playlist_track_cross_ref 
        WHERE playlistId = :playlistId 
        ORDER BY orderIndex ASC
    """)
    suspend fun getTrackIdsForPlaylistOnce(playlistId: Long): List<Long>

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)
}

/** One row of [PlaylistDao.getRecommendedPlaylists]: a playlist plus its aggregate listen/like score. */
data class PlaylistRecommendation(
    val playlistId: Long,
    val name: String,
    val imageUrl: String?,
    val trackCount: Int,
    val totalScore: Int
)
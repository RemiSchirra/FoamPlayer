package com.foamplayer.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksOnce(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE path = :path LIMIT 1")
    suspend fun getTrackByPath(path: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchTracks(query: String): Flow<List<TrackEntity>>

    @Query("SELECT DISTINCT artist FROM tracks WHERE artist LIKE '%' || :query || '%'")
    fun searchArtists(query: String): Flow<List<String>>

    @Query("""
        SELECT album, 
               GROUP_CONCAT(DISTINCT COALESCE(albumArtist, artist)) as artist, 
               MAX(albumArtPath) as albumArtPath 
        FROM tracks 
        WHERE album LIKE '%' || :query || '%' 
           OR artist LIKE '%' || :query || '%' 
           OR albumArtist LIKE '%' || :query || '%'
        GROUP BY album
    """)
    fun searchAlbums(query: String): Flow<List<AlbumSearchResult>>

    @Query("""
        SELECT album, 
               GROUP_CONCAT(DISTINCT COALESCE(albumArtist, artist)) as artist, 
               MAX(albumArtPath) as albumArtPath 
        FROM tracks 
        GROUP BY album
        ORDER BY album COLLATE NOCASE ASC
    """)
    fun getAllAlbums(): Flow<List<AlbumSearchResult>>

    @Query("SELECT DISTINCT artist FROM tracks")
    fun getAllArtistNames(): Flow<List<String>>

    @Query("SELECT DISTINCT genre FROM tracks WHERE genre IS NOT NULL AND genre != ''")
    fun getAllGenres(): Flow<List<String>>

    @Query("""
        SELECT * FROM tracks 
        WHERE genre = :genre 
           OR genre LIKE :genre || '; %' 
           OR genre LIKE '%; ' || :genre 
           OR genre LIKE '%; ' || :genre || '; %'
        ORDER BY title ASC
    """)
    fun getTracksByGenre(genre: String): Flow<List<TrackEntity>>

    @Query("SELECT DISTINCT year FROM tracks WHERE year IS NOT NULL ORDER BY year DESC")
    fun getAllYears(): Flow<List<Int>>

    @Query("SELECT * FROM tracks WHERE year = :year ORDER BY album ASC, discNumber ASC, trackNumber ASC, title ASC")
    fun getTracksByYear(year: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE album = :album ORDER BY discNumber ASC, trackNumber ASC, title ASC")
    fun getTracksByAlbum(album: String): Flow<List<TrackEntity>>

    @Query("""
        SELECT album, 
               GROUP_CONCAT(DISTINCT COALESCE(albumArtist, artist)) as artist, 
               MAX(albumArtPath) as albumArtPath 
        FROM tracks 
        WHERE artist = :artist OR albumArtist = :artist
        GROUP BY album
        ORDER BY album ASC
    """)
    fun getAlbumsByArtist(artist: String): Flow<List<AlbumSearchResult>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :timestamp WHERE id = :trackId")
    suspend fun incrementPlayCount(trackId: Long, timestamp: Long)

    @Query("UPDATE tracks SET playCount = 0, lastPlayedAt = NULL WHERE id = :trackId")
    suspend fun resetPlayStats(trackId: Long)

    @Query("UPDATE tracks SET score = :score WHERE id = :trackId")
    suspend fun setScore(trackId: Long, score: Int)

    @Query("SELECT path, playCount, lastPlayedAt, score FROM tracks")
    suspend fun getPlayStats(): List<TrackPlayStatsRow>

    @Query("DELETE FROM tracks WHERE path NOT IN (:existingPaths)")
    suspend fun deleteOrphanedTracks(existingPaths: List<String>)
}

data class AlbumSearchResult(
    val album: String,
    val artist: String,
    val albumArtPath: String?
)

data class TrackPlayStatsRow(
    val path: String,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val score: Int = 0
)

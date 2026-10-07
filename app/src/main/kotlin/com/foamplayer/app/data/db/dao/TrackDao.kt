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

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrackById(trackId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:trackIds)")
    suspend fun getTracksByIds(trackIds: List<Long>): List<TrackEntity>

    // Search clauses feed a user-typed query into LIKE, so every pattern
    // carries an ESCAPE '\' clause; TrackRepository escapes \, % and _ first.
    @Query("""
        SELECT * FROM tracks 
        WHERE title LIKE '%' || :query || '%' ESCAPE '\' 
           OR artist LIKE '%' || :query || '%' ESCAPE '\' 
           OR album LIKE '%' || :query || '%' ESCAPE '\' 
        ORDER BY title ASC
    """)
    fun searchTracks(query: String): Flow<List<TrackEntity>>

    @Query("""
        SELECT DISTINCT artist FROM tracks 
        WHERE artist LIKE '%' || :query || '%' ESCAPE '\'
    """)
    fun searchArtists(query: String): Flow<List<String>>

    @Query("""
        SELECT album, 
               GROUP_CONCAT(DISTINCT COALESCE(albumArtist, artist)) as artist, 
               MAX(albumArtPath) as albumArtPath 
        FROM tracks 
        WHERE album LIKE '%' || :query || '%' ESCAPE '\' 
           OR artist LIKE '%' || :query || '%' ESCAPE '\' 
           OR albumArtist LIKE '%' || :query || '%' ESCAPE '\' 
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
        WHERE artist = :artist 
           OR artist LIKE :artist || '; %' 
           OR artist LIKE '%; ' || :artist 
           OR artist LIKE '%; ' || :artist || '; %'
           OR albumArtist = :artist 
           OR albumArtist LIKE :artist || '; %' 
           OR albumArtist LIKE '%; ' || :artist 
           OR albumArtist LIKE '%; ' || :artist || '; %'
        GROUP BY album
        ORDER BY album ASC
    """)
    fun getAlbumsByArtist(artist: String): Flow<List<AlbumSearchResult>>

    /**
     * All tracks naming [artist] as artist or album artist (the "; " multi-artist
     * convention, same patterns as [getTracksByGenre]), alphabetical by title.
     */
    @Query("""
        SELECT * FROM tracks 
        WHERE artist = :artist 
           OR artist LIKE :artist || '; %' 
           OR artist LIKE '%; ' || :artist 
           OR artist LIKE '%; ' || :artist || '; %'
           OR albumArtist = :artist 
           OR albumArtist LIKE :artist || '; %' 
           OR albumArtist LIKE '%; ' || :artist 
           OR albumArtist LIKE '%; ' || :artist || '; %'
        ORDER BY title ASC
    """)
    fun getTracksByArtist(artist: String): Flow<List<TrackEntity>>

    /** Tracks with at least one listen, newest listen first (Home "Recent listens"). */
    @Query("""
        SELECT * FROM tracks 
        WHERE lastPlayedAt IS NOT NULL 
        ORDER BY lastPlayedAt DESC 
        LIMIT 50
    """)
    fun getRecentlyPlayed(): Flow<List<TrackEntity>>

    /**
     * Home "Old favorites": tracks with listening history whose last listen is
     * older than [cutoff], ranked by aggregate play count + likes (long-standing
     * favorites rather than recent hits; never-played-but-liked tracks qualify).
     */
    @Query("""
        SELECT * FROM tracks 
        WHERE (lastPlayedAt IS NULL OR lastPlayedAt < :cutoff) 
          AND (playCount > 0 OR score > 0) 
        ORDER BY (playCount + MAX(score, 0) * 10) DESC, playCount DESC, title ASC 
        LIMIT 50
    """)
    fun getOldFavorites(cutoff: Long): Flow<List<TrackEntity>>

    /**
     * Home "Recommended albums": albums ranked by the aggregate play count +
     * likes of their tracks. Same art/artist projection as [getAllAlbums].
     */
    @Query("""
        SELECT album, 
               GROUP_CONCAT(DISTINCT COALESCE(albumArtist, artist)) as artist, 
               MAX(albumArtPath) as albumArtPath, 
               SUM(playCount + MAX(score, 0) * 10) as totalScore 
        FROM tracks 
        GROUP BY album 
        ORDER BY totalScore DESC, album COLLATE NOCASE ASC 
        LIMIT 50
    """)
    fun getRecommendedAlbums(): Flow<List<AlbumRecommendation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :timestamp WHERE id = :trackId")
    suspend fun incrementPlayCount(trackId: Long, timestamp: Long)

    @Query("UPDATE tracks SET playCount = 0, lastPlayedAt = NULL WHERE id = :trackId")
    suspend fun resetPlayStats(trackId: Long)

    /** Clears every track's listen counters, last-listen date and score. */
    @Query("UPDATE tracks SET playCount = 0, lastPlayedAt = NULL, score = 0")
    suspend fun resetAllPlayStats()

    /**
     * Overwrites one track's whole stats block (count, last-listen date,
     * score) — the dev "generate random data" tool's per-track write.
     */
    @Query("""
        UPDATE tracks 
        SET playCount = :playCount, 
            lastPlayedAt = :lastPlayedAt, 
            score = :score 
        WHERE id = :trackId
    """)
    suspend fun setPlayStats(trackId: Long, playCount: Int, lastPlayedAt: Long?, score: Int)

    @Query("UPDATE tracks SET score = :score WHERE id = :trackId")
    suspend fun setScore(trackId: Long, score: Int)

    @Query("UPDATE tracks SET instrumental = :instrumental WHERE id = :trackId")
    suspend fun setInstrumental(trackId: Long, instrumental: Boolean)

    @Query("SELECT instrumental FROM tracks WHERE id = :trackId")
    fun observeInstrumental(trackId: Long): Flow<Boolean>

    /**
     * Includes the row id: the scanner re-attaches it when merging rescanned
     * files, so INSERT OR REPLACE updates the SAME row instead of deleting and
     * re-inserting it — which would re-assign the autoGenerate id and orphan
     * every listen_history row pointing at the old id (the stats screens'
     * only data source).
     */
    @Query("SELECT id, path, playCount, lastPlayedAt, score, instrumental FROM tracks")
    suspend fun getPlayStats(): List<TrackPlayStatsRow>

    @Query("""
        UPDATE tracks 
        SET playCount = :playCount, 
            lastPlayedAt = :lastPlayedAt, 
            score = :score 
        WHERE id = :trackId
    """)
    suspend fun importPlayStats(trackId: Long, playCount: Int, lastPlayedAt: Long?, score: Int)

    @Query("SELECT path FROM tracks")
    suspend fun getAllTrackPaths(): List<String>

    @Query("SELECT id FROM tracks")
    suspend fun getAllTrackIds(): List<Long>

    @Query("DELETE FROM tracks WHERE path IN (:paths)")
    suspend fun deleteTracksByPaths(paths: List<String>)
}

data class AlbumSearchResult(
    val album: String,
    val artist: String,
    val albumArtPath: String?
)

/** One row of [TrackDao.getRecommendedAlbums]: an album plus its aggregate listen/like score. */
data class AlbumRecommendation(
    val album: String,
    val artist: String,
    val albumArtPath: String?,
    val totalScore: Int
)

data class TrackPlayStatsRow(
    val id: Long,
    val path: String,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val score: Int = 0,
    val instrumental: Boolean = false
)

package com.foamplayer.app.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import com.foamplayer.app.data.db.entities.ListenHistoryEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.Flow

/** Aggregate counters over the listen rows inside one analysis period. */
data class ListenOverview(
    val totalPlays: Int = 0,
    val totalDurationMs: Long = 0,
    val uniqueTracks: Int = 0
)

/**
 * One row of [ListenHistoryDao.observeTopTracks]: the full track plus its
 * period play count (named [periodPlays] to stay clear of the track's own
 * lifetime [TrackEntity.playCount] column inside the embedded entity).
 */
data class TopTrackStat(
    @Embedded val track: TrackEntity,
    val periodPlays: Int
)

/** One row of [ListenHistoryDao.observeArtistPlayRows]: plays credited to one raw artist string. */
data class ArtistPlayRow(
    val rawArtist: String,
    val playCount: Int,
    val totalDurationMs: Long
)

/** One row of [ListenHistoryDao.observeTopAlbums]: an album plus its period play count. */
data class TopAlbumStat(
    val album: String,
    val artist: String,
    val albumArtPath: String?,
    val playCount: Int,
    val totalDurationMs: Long
)

/** One day bucket of [ListenHistoryDao.observeDailyPlays]: days-since-epoch plus its counters. */
data class DailyPlayBucket(
    val dayIndex: Long,
    val playCount: Int,
    val totalDurationMs: Long
)

/** One hour bucket of [ListenHistoryDao.observeHourHistogram]. */
data class HourBucket(
    val hourOfDay: Int,
    val playCount: Int
)

/** One row of [ListenHistoryDao.observeGenrePlayRows]: plays credited to one raw genre string. */
data class GenrePlayRow(
    val rawGenre: String,
    val playCount: Int
)

@Dao
interface ListenHistoryDao {

    @Insert
    suspend fun insertListen(listen: ListenHistoryEntity)

    @Insert
    suspend fun insertListens(listens: List<ListenHistoryEntity>)

    @Query("SELECT * FROM listen_history")
    suspend fun getAllListens(): List<ListenHistoryEntity>

    /** Removes every listen row of one track (per-track stats reset). */
    @Query("DELETE FROM listen_history WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: Long)

    @Query("DELETE FROM listen_history")
    suspend fun clearAll()

    // Every query takes [since] = period start (epoch ms) or NULL for all time;
    // the duration sums join the track's own duration, i.e. "listening time".

    @Query("""
        SELECT COUNT(*) AS totalPlays,
               COALESCE(SUM(t.duration), 0) AS totalDurationMs,
               COUNT(DISTINCT h.trackId) AS uniqueTracks
        FROM listen_history h
        JOIN tracks t ON t.id = h.trackId
        WHERE :since IS NULL OR h.listenedAt >= :since
    """)
    fun observeOverview(since: Long?): Flow<ListenOverview>

    /**
     * Distinct raw artist strings of the period. The multi-artist "; " convention
     * is split and re-counted in Kotlin (SQL cannot split strings), so this stays
     * a raw projection instead of a COUNT(DISTINCT) that would fuse "A; B" into
     * one impossible artist.
     */
    @Query("""
        SELECT DISTINCT t.artist
        FROM listen_history h
        JOIN tracks t ON t.id = h.trackId
        WHERE (:since IS NULL OR h.listenedAt >= :since) AND t.artist != ''
    """)
    fun observeDistinctArtists(since: Long?): Flow<List<String>>

    @Query("""
        SELECT t.*, COUNT(*) AS periodPlays
        FROM listen_history h
        JOIN tracks t ON t.id = h.trackId
        WHERE :since IS NULL OR h.listenedAt >= :since
        GROUP BY h.trackId
        ORDER BY periodPlays DESC, t.title COLLATE NOCASE ASC
    """)
    fun observeTopTracks(since: Long?): Flow<List<TopTrackStat>>

    @Query("""
        SELECT t.artist AS rawArtist,
               COUNT(*) AS playCount,
               COALESCE(SUM(t.duration), 0) AS totalDurationMs
        FROM listen_history h
        JOIN tracks t ON t.id = h.trackId
        WHERE :since IS NULL OR h.listenedAt >= :since
        GROUP BY t.artist
    """)
    fun observeArtistPlayRows(since: Long?): Flow<List<ArtistPlayRow>>

    @Query("""
        SELECT t.album AS album,
               GROUP_CONCAT(DISTINCT COALESCE(t.albumArtist, t.artist)) AS artist,
               MAX(t.albumArtPath) AS albumArtPath,
               COUNT(*) AS playCount,
               COALESCE(SUM(t.duration), 0) AS totalDurationMs
        FROM listen_history h
        JOIN tracks t ON t.id = h.trackId
        WHERE :since IS NULL OR h.listenedAt >= :since
        GROUP BY t.album
        ORDER BY playCount DESC, t.album COLLATE NOCASE ASC
        LIMIT :limit
    """)
    fun observeTopAlbums(since: Long?, limit: Int): Flow<List<TopAlbumStat>>

    /**
     * Play/duration counters bucketed by local calendar day. [tzOffsetMs] shifts
     * the UTC timestamps into the device's current offset before the integer
     * division, so day buckets land on local midnights (DST shifts inside a
     * period are approximated by the current offset).
     */
    @Query("""
        SELECT (h.listenedAt + :tzOffsetMs) / 86400000 AS dayIndex,
               COUNT(*) AS playCount,
               COALESCE(SUM(t.duration), 0) AS totalDurationMs
        FROM listen_history h
        JOIN tracks t ON t.id = h.trackId
        WHERE :since IS NULL OR h.listenedAt >= :since
        GROUP BY dayIndex
        ORDER BY dayIndex ASC
    """)
    fun observeDailyPlays(since: Long?, tzOffsetMs: Long): Flow<List<DailyPlayBucket>>

    /** Plays per local hour of day (0..23), for the "listening clock" chart. */
    @Query("""
        SELECT ((h.listenedAt + :tzOffsetMs) % 86400000) / 3600000 AS hourOfDay,
               COUNT(*) AS playCount
        FROM listen_history h
        JOIN tracks t ON t.id = h.trackId
        WHERE :since IS NULL OR h.listenedAt >= :since
        GROUP BY hourOfDay
        ORDER BY hourOfDay ASC
    """)
    fun observeHourHistogram(since: Long?, tzOffsetMs: Long): Flow<List<HourBucket>>

    @Query("""
        SELECT t.genre AS rawGenre, COUNT(*) AS playCount
        FROM listen_history h
        JOIN tracks t ON t.id = h.trackId
        WHERE (:since IS NULL OR h.listenedAt >= :since)
          AND t.genre IS NOT NULL AND t.genre != ''
        GROUP BY t.genre
    """)
    fun observeGenrePlayRows(since: Long?): Flow<List<GenrePlayRow>>
}

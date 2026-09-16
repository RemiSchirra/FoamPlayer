package com.foamplayer.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.foamplayer.app.data.db.entities.CachedLyricsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedLyricsDao {
    @Query("SELECT * FROM cached_lyrics WHERE trackId = :trackId LIMIT 1")
    suspend fun getByTrackId(trackId: Long): CachedLyricsEntity?

    @Query("SELECT * FROM cached_lyrics")
    suspend fun getAll(): List<CachedLyricsEntity>

    @Query("SELECT trackId FROM cached_lyrics")
    fun observeCachedTrackIds(): Flow<List<Long>>

    @Query("SELECT trackId FROM cached_lyrics")
    suspend fun getCachedTrackIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedLyricsEntity)

    @Query("DELETE FROM cached_lyrics WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: Long)

    @Query("DELETE FROM cached_lyrics WHERE trackId IN (:trackIds)")
    suspend fun deleteByTrackIds(trackIds: List<Long>)
}

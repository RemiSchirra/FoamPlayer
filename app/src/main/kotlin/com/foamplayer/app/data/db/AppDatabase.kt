package com.foamplayer.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.foamplayer.app.data.db.dao.CachedLyricsDao
import com.foamplayer.app.data.db.dao.ListenHistoryDao
import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.QueueDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.CachedLyricsEntity
import com.foamplayer.app.data.db.entities.FolderEntity
import com.foamplayer.app.data.db.entities.ListenHistoryEntity
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.PlaylistTrackCrossRef
import com.foamplayer.app.data.db.entities.QueueItemEntity
import com.foamplayer.app.data.db.entities.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        QueueItemEntity::class,
        FolderEntity::class,
        CachedLyricsEntity::class,
        ListenHistoryEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao
    abstract fun cachedLyricsDao(): CachedLyricsDao
    abstract fun listenHistoryDao(): ListenHistoryDao
}
package com.foamplayer.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import com.foamplayer.app.data.db.entities.TrackDeletionLogEntity
import com.foamplayer.app.data.db.entities.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        QueueItemEntity::class,
        FolderEntity::class,
        CachedLyricsEntity::class,
        ListenHistoryEntity::class,
        TrackDeletionLogEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao
    abstract fun cachedLyricsDao(): CachedLyricsDao
    abstract fun listenHistoryDao(): ListenHistoryDao

    /** 10 -> 11: the tracks table gains the "dateAdded" column (file mtime at first scan). */
    companion object {
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN dateAdded INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 11 -> 12: new track_deletion_log table. The scanner snapshots every
         * track row it deletes (a moved file re-scans under a new path with a
         * fresh id, orphaning its playlist cross-references); the repair
         * feature matches those snapshots against the re-scanned library to
         * re-link the dangling references.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_deletion_log (
                        trackId INTEGER NOT NULL PRIMARY KEY,
                        path TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        duration INTEGER NOT NULL,
                        deletedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
    }
}

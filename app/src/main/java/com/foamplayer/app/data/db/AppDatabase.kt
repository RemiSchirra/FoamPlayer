package com.foamplayer.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.QueueDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.FolderEntity
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
        FolderEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao

    companion object {
        /** Adds the composer column to the tracks table (track details modal). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN composer TEXT")
            }
        }

        /** Adds the listen counter and last-listen timestamp to the tracks table. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lastPlayedAt INTEGER")
            }
        }

        /** Adds the user score (-1 dislike / 0 not noted / 1 like / 2 super like) to the tracks table. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN score INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
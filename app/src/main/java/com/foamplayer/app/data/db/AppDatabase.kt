package com.foamplayer.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foamplayer.app.data.db.dao.CachedLyricsDao
import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.QueueDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.CachedLyricsEntity
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
        FolderEntity::class,
        CachedLyricsEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao
    abstract fun cachedLyricsDao(): CachedLyricsDao

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

        /** Adds the user-chosen cover image to the playlists table. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN imageUrl TEXT")
            }
        }

        /** Adds the manual ordering rank to the playlists table (edit-mode reordering). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the user-set instrumental flag to the tracks table (lyrics view override). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN instrumental INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the cached-lyrics table (online-fetched lyrics persisted per track). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cached_lyrics (" +
                            "trackId INTEGER NOT NULL PRIMARY KEY, " +
                            "lyrics TEXT NOT NULL, " +
                            "provider TEXT, " +
                            "fetchedAtMs INTEGER NOT NULL)"
                )
            }
        }
    }
}
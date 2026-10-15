package com.foamplayer.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.foamplayer.app.data.db.AppDatabase
import com.foamplayer.app.data.db.dao.CachedLyricsDao
import com.foamplayer.app.data.db.dao.ListenHistoryDao
import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.repository.PlaylistRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.LocalAudioScanner
import com.foamplayer.app.data.scanner.PlaylistCoverProvider
import com.foamplayer.app.data.scanner.RemoteLibraryScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "foam_settings")

/** Provides repositories, the library scanner and the settings DataStore. */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideLocalAudioScanner(@ApplicationContext context: Context): LocalAudioScanner {
        return LocalAudioScanner(context)
    }

    @Provides
    @Singleton
    fun provideTrackRepository(
        database: AppDatabase,
        trackDao: TrackDao,
        scanner: LocalAudioScanner,
        remoteScanner: RemoteLibraryScanner,
        settingsRepository: SettingsRepository,
        cachedLyricsDao: CachedLyricsDao,
        listenHistoryDao: ListenHistoryDao
    ): TrackRepository {
        return TrackRepository(
            database,
            trackDao,
            scanner,
            remoteScanner,
            settingsRepository,
            cachedLyricsDao,
            listenHistoryDao
        )
    }

    @Provides
    @Singleton
    fun providePlaylistRepository(
        playlistDao: PlaylistDao,
        playlistCoverProvider: PlaylistCoverProvider
    ): PlaylistRepository {
        return PlaylistRepository(playlistDao, playlistCoverProvider)
    }

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}

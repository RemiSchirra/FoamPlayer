package com.foamplayer.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.foamplayer.app.data.db.dao.CachedLyricsDao
import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.repository.PlaylistRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.LocalAudioScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "foam_settings")

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
        trackDao: TrackDao,
        scanner: LocalAudioScanner,
        settingsRepository: SettingsRepository,
        cachedLyricsDao: CachedLyricsDao
    ): TrackRepository {
        return TrackRepository(trackDao, scanner, settingsRepository, cachedLyricsDao)
    }

    @Provides
    @Singleton
    fun providePlaylistRepository(playlistDao: PlaylistDao): PlaylistRepository {
        return PlaylistRepository(playlistDao)
    }

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}
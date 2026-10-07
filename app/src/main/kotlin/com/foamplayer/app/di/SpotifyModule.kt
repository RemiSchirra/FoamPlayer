package com.foamplayer.app.di

import android.util.Log
import com.foamplayer.spotify.SpotifyAlbumArtResolver
import com.foamplayer.spotify.SpotifyArtistImageResolver
import com.foamplayer.spotify.SpotifyLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings for the Spotify resolvers. */
@Module
@InstallIn(SingletonComponent::class)
object SpotifyModule {

    @Provides
    @Singleton
    fun provideSpotifyLogger(): SpotifyLogger = SpotifyLogger { message ->
        Log.d("Spotify", message)
    }

    @Provides
    @Singleton
    fun provideSpotifyAlbumArtResolver(logger: SpotifyLogger): SpotifyAlbumArtResolver =
        SpotifyAlbumArtResolver(logger = logger)

    @Provides
    @Singleton
    fun provideSpotifyArtistImageResolver(logger: SpotifyLogger): SpotifyArtistImageResolver =
        SpotifyArtistImageResolver(logger = logger)
}

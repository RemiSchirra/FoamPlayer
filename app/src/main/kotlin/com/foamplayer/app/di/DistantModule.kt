package com.foamplayer.app.di

import com.foamplayer.distant.DistantProviderRegistry
import com.foamplayer.distant.jellyfin.JellyfinProvider
import com.foamplayer.distant.plex.PlexProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wires every distant provider into the registry the repository consumes. */
@Module
@InstallIn(SingletonComponent::class)
object DistantModule {

    @Provides
    @Singleton
    fun provideDistantProviderRegistry(): DistantProviderRegistry = DistantProviderRegistry(
        listOf(
            PlexProvider(),
            JellyfinProvider()
        )
    )
}

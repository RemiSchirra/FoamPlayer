package com.foamplayer.app.di

import android.util.Log
import com.foamplayer.discord.DiscordAssetRegistrar
import com.foamplayer.discord.DiscordLogger
import com.foamplayer.discord.DiscordOAuth
import com.foamplayer.discord.DiscordPresenceClient
import com.foamplayer.discord.YouTubeThumbnailResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings for the Discord rich-presence components. */
@Module
@InstallIn(SingletonComponent::class)
object DiscordModule {

    @Provides
    @Singleton
    fun provideDiscordLogger(): DiscordLogger = DiscordLogger { message ->
        Log.d("DiscordRPC", message)
    }

    @Provides
    @Singleton
    fun provideDiscordOAuth(): DiscordOAuth = DiscordOAuth()

    @Provides
    @Singleton
    fun provideDiscordAssetRegistrar(logger: DiscordLogger): DiscordAssetRegistrar =
        DiscordAssetRegistrar(logger = logger)

    @Provides
    @Singleton
    fun provideDiscordPresenceClient(logger: DiscordLogger): DiscordPresenceClient =
        DiscordPresenceClient(logger)

    @Provides
    @Singleton
    fun provideYouTubeThumbnailResolver(logger: DiscordLogger): YouTubeThumbnailResolver =
        YouTubeThumbnailResolver(logger = logger)
}

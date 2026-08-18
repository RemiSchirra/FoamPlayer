package com.foamplayer.app.di

import android.content.Context
import com.foamplayer.app.audio.bitperfect.BitPerfectDetector
import com.foamplayer.app.audio.equalizer.EqualizerManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideBitPerfectDetector(@ApplicationContext context: Context): BitPerfectDetector {
        return BitPerfectDetector(context)
    }

    @Provides
    @Singleton
    fun provideEqualizerManager(): EqualizerManager {
        return EqualizerManager()
    }
}
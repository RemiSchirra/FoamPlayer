package com.foamplayer.app.di

import android.content.Context
import android.media.AudioManager
import com.foamplayer.app.audio.bitperfect.BitPerfectDetector
import com.foamplayer.app.audio.device.OutputDeviceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings for the audio system: manager, device tracking and bit-perfect evaluation. */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Provides
    @Singleton
    fun provideOutputDeviceManager(audioManager: AudioManager): OutputDeviceManager {
        return OutputDeviceManager(audioManager)
    }

    @Provides
    @Singleton
    fun provideBitPerfectDetector(
        audioManager: AudioManager,
        outputDeviceManager: OutputDeviceManager
    ): BitPerfectDetector {
        return BitPerfectDetector(audioManager, outputDeviceManager)
    }
}

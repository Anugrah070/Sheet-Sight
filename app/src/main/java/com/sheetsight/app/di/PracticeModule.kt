package com.sheetsight.app.di

import com.sheetsight.app.data.audio.AudioPitchSource
import com.sheetsight.app.data.audio.AudioRecordPitchSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PracticeModule {
    @Binds
    @Singleton
    abstract fun bindAudioPitchSource(implementation: AudioRecordPitchSource): AudioPitchSource
}

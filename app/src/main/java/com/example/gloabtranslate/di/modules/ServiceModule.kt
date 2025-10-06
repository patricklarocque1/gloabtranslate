package com.example.gloabtranslate.di.modules

import com.example.gloabtranslate.service.LiveTranslateService
import com.example.gloabtranslate.speech.AudioRecordingService
import dagger.Module
import dagger.android.ContributesAndroidInjector

/**
 * Service module for Android injection.
 */
@Module
abstract class ServiceModule {

    @ContributesAndroidInjector
    abstract fun contributeLiveTranslateService(): LiveTranslateService

    @ContributesAndroidInjector
    abstract fun contributeAudioRecordingService(): AudioRecordingService
}
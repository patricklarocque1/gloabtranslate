package com.example.gloabtranslate.di.modules

import android.content.Context
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import com.example.gloabtranslate.speech.AudioConfig
import com.example.gloabtranslate.speech.AudioFilter
import com.example.gloabtranslate.speech.AudioProcessor
import com.example.gloabtranslate.speech.AudioQualityMonitor
import com.example.gloabtranslate.speech.AudioRecorder
import com.example.gloabtranslate.core.logging.DebugLogger
import com.example.gloabtranslate.speech.AudioSessionManager
import com.example.gloabtranslate.speech.memory.AudioBufferManager
import com.example.gloabtranslate.speech.optimization.AudioOptimizer
import com.example.gloabtranslate.speech.SpeechRecognitionService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Speech module providing speech recognition and audio processing dependencies.
 */
@Module
object SpeechModule {

    @Provides
    @Singleton
    fun provideAudioConfig(): AudioConfig {
        return AudioConfig() // Data class with default constructor
    }

    @Provides
    @Singleton
    fun provideAudioFilter(): AudioFilter {
        return AudioFilter() // Regular class with default constructor
    }

    @Provides
    @Singleton
    fun provideAudioProcessor(context: Context, configurationManager: ConfigurationManager): AudioProcessor {
        return AudioProcessor(context, configurationManager)
    }

    @Provides
    @Singleton
    fun provideAudioQualityMonitor(): AudioQualityMonitor {
        return AudioQualityMonitor()
    }

    @Provides
    @Singleton
    fun provideAudioRecorder(context: Context, configurationManager: ConfigurationManager, debugLogger: DebugLogger): AudioRecorder {
        return AudioRecorder(context, configurationManager, debugLogger)
    }

    @Provides
    @Singleton
    fun provideAudioSessionManager(context: Context): AudioSessionManager {
        return AudioSessionManager(context)
    }

    @Provides
    @Singleton
    fun provideAudioBufferManager(): AudioBufferManager {
        return AudioBufferManager()
    }

    @Provides
    @Singleton
    fun provideAudioOptimizer(): AudioOptimizer {
        return AudioOptimizer()
    }

    @Provides
    @Singleton
    fun provideSpeechRecognitionService(context: Context): SpeechRecognitionService {
        return SpeechRecognitionService(context)
    }
}

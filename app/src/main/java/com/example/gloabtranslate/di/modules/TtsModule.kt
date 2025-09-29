package com.example.gloabtranslate.di.modules

import android.content.Context
import com.example.gloabtranslate.tts.AudioOutputManager
import com.example.gloabtranslate.tts.SpeechRateController
import com.example.gloabtranslate.tts.TextToSpeechService
import com.example.gloabtranslate.tts.VoiceSelector
import com.example.gloabtranslate.tts.VoiceSynthesizer
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * TTS module providing text-to-speech dependencies.
 */
@Module
object TtsModule {

    @Provides
    @Singleton
    fun provideAudioOutputManager(context: Context): AudioOutputManager {
        return AudioOutputManager(context)
    }

    @Provides
    @Singleton
    fun provideSpeechRateController(context: Context): SpeechRateController {
        return SpeechRateController(context)
    }

    @Provides
    @Singleton
    fun provideTextToSpeechService(context: Context): TextToSpeechService {
        return TextToSpeechService(context)
    }

    @Provides
    @Singleton
    fun provideVoiceSelector(context: Context): VoiceSelector {
        return VoiceSelector(context)
    }

    @Provides
    @Singleton
    fun provideVoiceSynthesizer(context: Context): VoiceSynthesizer {
        return VoiceSynthesizer(context)
    }
}

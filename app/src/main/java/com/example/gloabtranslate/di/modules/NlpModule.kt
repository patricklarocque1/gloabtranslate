package com.example.gloabtranslate.di.modules

import android.content.Context
import com.example.gloabtranslate.nlp.ModelManager
import com.example.gloabtranslate.nlp.ModelAvailabilityChecker
import com.example.gloabtranslate.nlp.ModelUpdater
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * NLP module providing natural language processing dependencies.
 */
@Module
object NlpModule {

    @Provides
    @Singleton
    fun provideModelManager(context: Context): ModelManager {
        return ModelManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideModelAvailabilityChecker(context: Context): ModelAvailabilityChecker {
        return ModelAvailabilityChecker.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideModelUpdater(context: Context): ModelUpdater {
        return ModelUpdater.getInstance(context)
    }
}

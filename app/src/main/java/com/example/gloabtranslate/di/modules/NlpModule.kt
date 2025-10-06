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
        return ModelManager(context.applicationContext)
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

    @Provides
    @Singleton
    fun provideRecognitionService(context: Context, modelManager: ModelManager): com.example.gloabtranslate.nlp.RecognitionService {
        return com.example.gloabtranslate.nlp.RecognitionService(context, modelManager)
    }
    
    @Provides
    @Singleton
    fun provideTranslationPipeline(
        context: Context, 
        modelManager: ModelManager,
        configurationManager: com.example.gloabtranslate.core.data.config.ConfigurationManager,
        historyRepository: com.example.gloabtranslate.core.data.repository.TranslationHistoryRepository,
        errorRecoverySystem: com.example.gloabtranslate.core.error.ErrorRecoverySystem,
        debugLogger: com.example.gloabtranslate.core.logging.DebugLogger
    ): com.example.gloabtranslate.nlp.TranslationPipeline {
        return com.example.gloabtranslate.nlp.TranslationPipeline(
            context,
            modelManager,
            configurationManager,
            historyRepository,
            errorRecoverySystem,
            debugLogger
        )
    }
}

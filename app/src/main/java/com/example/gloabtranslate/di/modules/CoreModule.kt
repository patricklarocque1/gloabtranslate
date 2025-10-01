package com.example.gloabtranslate.di.modules

import android.content.Context
import com.example.gloabtranslate.core.analytics.UserAnalytics
import kotlinx.coroutines.flow.map
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
import com.example.gloabtranslate.core.data.preferences.PermissionPreferences
import com.example.gloabtranslate.core.data.persistence.ServiceStateManager
import com.example.gloabtranslate.core.data.repository.TranslationHistoryRepository
import com.example.gloabtranslate.core.data.repository.TranslationRepository
import com.example.gloabtranslate.core.error.ErrorHandler
import com.example.gloabtranslate.core.error.ErrorRecovery
import com.example.gloabtranslate.core.error.ErrorReporter
import com.example.gloabtranslate.core.logging.StructuredLogger
import com.example.gloabtranslate.core.monitoring.PerformanceMonitor
import com.example.gloabtranslate.core.permissions.PermissionManager
import com.example.gloabtranslate.core.privacy.PrivacyManager
import com.example.gloabtranslate.core.resources.ResourceManager
import com.example.gloabtranslate.core.security.DataEncryption
import com.example.gloabtranslate.core.security.SecureStorage
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Core module providing core functionality dependencies.
 */
@Module
object CoreModule {

    @Provides
    @Singleton
    fun provideUserPreferencesManager(context: Context): UserPreferencesManager {
        return UserPreferencesManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePermissionPreferences(context: Context): PermissionPreferences {
        return PermissionPreferences.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideServiceStateManager(context: Context): ServiceStateManager {
        return ServiceStateManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideTranslationHistoryRepository(context: Context): TranslationHistoryRepository {
        return TranslationHistoryRepository.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideTranslationRepository(context: Context, historyRepository: TranslationHistoryRepository): TranslationRepository {
        return object : TranslationRepository {
            private val translationPipeline by lazy { com.example.gloabtranslate.nlp.TranslationPipeline(context) }
            
            override suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): com.example.gloabtranslate.core.data.models.TranslationResult {
                return try {
                    translationPipeline.processText(text, sourceLanguage, targetLanguage)
                } catch (e: Exception) {
                    com.example.gloabtranslate.core.data.models.TranslationResult(
                        success = false,
                        originalText = text,
                        translatedText = "",
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        confidence = 0.0f,
                        isPartial = false,
                        isOnDevice = false,
                        timestamp = System.currentTimeMillis(),
                        error = "Translation error: ${e.message}"
                    )
                }
            }
            
            override suspend fun identifyLanguage(text: String): String? {
                return try {
                    // Use the translation pipeline with "auto" source to detect language
                    // The pipeline will detect the language as part of the translation process
                    val result = translationPipeline.processText(text, "auto", "en", isPartial = false)
                    if (result.success) result.sourceLanguage else null
                } catch (e: Exception) {
                    null
                }
            }
            
            override suspend fun getSupportedLanguages(): List<String> {
                return translationPipeline.getSupportedLanguages().map { it.code }
            }
            
            override fun getTranslationHistory(): kotlinx.coroutines.flow.Flow<List<com.example.gloabtranslate.core.data.models.TranslationResult>> {
                return historyRepository.historyFlow.map { historyEntries ->
                    historyEntries.map { entry ->
                        com.example.gloabtranslate.core.data.models.TranslationResult(
                            success = entry.success,
                            originalText = entry.originalText,
                            translatedText = entry.translatedText,
                            sourceLanguage = entry.sourceLanguage,
                            targetLanguage = entry.targetLanguage,
                            confidence = entry.confidence ?: 0.0f,
                            isPartial = entry.isPartial,
                            isOnDevice = entry.isOnDevice,
                            timestamp = entry.timestamp,
                            error = entry.error
                        )
                    }
                }
            }
            
            override suspend fun saveTranslationResult(result: com.example.gloabtranslate.core.data.models.TranslationResult) {
                historyRepository.addTranslation(result)
            }
            
            override suspend fun clearHistory() {
                historyRepository.clearAllHistory()
            }
        }
    }

    @Provides
    @Singleton
    fun provideUserAnalytics(context: Context): UserAnalytics {
        return UserAnalytics.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideErrorHandler(context: Context): ErrorHandler {
        return ErrorHandler.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideErrorRecovery(context: Context): ErrorRecovery {
        return ErrorRecovery.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideErrorReporter(context: Context): ErrorReporter {
        return ErrorReporter.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideStructuredLogger(context: Context): StructuredLogger {
        return StructuredLogger.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePerformanceMonitor(context: Context): PerformanceMonitor {
        return PerformanceMonitor.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePermissionManager(context: Context): PermissionManager {
        return PermissionManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePrivacyManager(context: Context): PrivacyManager {
        return PrivacyManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideResourceManager(context: Context): ResourceManager {
        return ResourceManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideDataEncryption(context: Context): DataEncryption {
        return DataEncryption.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideSecureStorage(context: Context): SecureStorage {
        return SecureStorage.getInstance(context)
    }
}

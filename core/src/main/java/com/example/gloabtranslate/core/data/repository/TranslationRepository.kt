package com.example.gloabtranslate.core.data.repository

import com.example.gloabtranslate.core.data.models.TranslationResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for translation operations
 */
interface TranslationRepository {
    
    /**
     * Translates text from source language to target language
     */
    suspend fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationResult
    
    /**
     * Identifies the language of the given text
     */
    suspend fun identifyLanguage(text: String): String?
    
    /**
     * Gets the list of supported languages
     */
    suspend fun getSupportedLanguages(): List<String>
    
    /**
     * Gets translation history
     */
    fun getTranslationHistory(): Flow<List<TranslationResult>>
    
    /**
     * Saves a translation result to history
     */
    suspend fun saveTranslationResult(result: TranslationResult)
    
    /**
     * Clears translation history
     */
    suspend fun clearHistory()
}

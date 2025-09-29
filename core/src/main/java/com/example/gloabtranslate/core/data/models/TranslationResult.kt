package com.example.gloabtranslate.core.data.models

import java.io.Serializable

/**
 * Data model representing a translation result
 */
data class TranslationResult(
    val success: Boolean,
    val originalText: String? = null,
    val translatedText: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val confidence: Float? = null,
    val isPartial: Boolean = false,
    val isOnDevice: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val error: String? = null,
    val alternatives: List<String> = emptyList(),
    val isFavorite: Boolean = false
) : Serializable

/**
 * Data model representing a language pair
 */
data class LanguagePair(
    val sourceLanguage: String,
    val targetLanguage: String,
    val displayName: String
)

/**
 * Data model representing supported languages
 */
data class SupportedLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val isOnDeviceSupported: Boolean = false
)

package com.example.gloabtranslate.core.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room representation of a translation-history entry. Mirrors the in-memory
 * structure that [com.example.gloabtranslate.core.data.repository.TranslationHistoryRepository]
 * currently exposes so the repository can migrate without data-shape churn.
 */
@Entity(tableName = "translation_history")
data class TranslationHistoryEntity(
    @PrimaryKey val id: String,
    val originalText: String,
    val translatedText: String?,
    val sourceLanguage: String,
    val targetLanguage: String,
    val confidence: Float?,
    val isPartial: Boolean,
    val isOnDevice: Boolean,
    val timestamp: Long,
    val duration: Long,
    val success: Boolean,
    val error: String?,
    val metadata: Map<String, String>,
    val tags: List<String>,
    val favorite: Boolean
)

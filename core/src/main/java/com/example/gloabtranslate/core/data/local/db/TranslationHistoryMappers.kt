package com.example.gloabtranslate.core.data.local.db

import com.example.gloabtranslate.core.data.repository.TranslationHistoryRepository

fun TranslationHistoryEntity.toRepositoryModel(): TranslationHistoryRepository.TranslationHistoryEntry {
    return TranslationHistoryRepository.TranslationHistoryEntry(
        id = id,
        originalText = originalText,
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        confidence = confidence,
        isPartial = isPartial,
        isOnDevice = isOnDevice,
        timestamp = timestamp,
        duration = duration,
        success = success,
        error = error,
        metadata = metadata,
        tags = tags,
        favorite = favorite
    )
}

fun TranslationHistoryRepository.TranslationHistoryEntry.toEntity(): TranslationHistoryEntity {
    return TranslationHistoryEntity(
        id = id,
        originalText = originalText,
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        confidence = confidence,
        isPartial = isPartial,
        isOnDevice = isOnDevice,
        timestamp = timestamp,
        duration = duration,
        success = success,
        error = error,
        metadata = metadata,
        tags = tags,
        favorite = favorite
    )
}

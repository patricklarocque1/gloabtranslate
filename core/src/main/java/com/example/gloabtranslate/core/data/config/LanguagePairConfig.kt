package com.example.gloabtranslate.core.data.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.example.gloabtranslate.core.utils.Logger

/**
 * Language pair configuration manager for handling language pairs, supported languages,
 * translation capabilities, and language-related settings.
 * Provides language detection, validation, caching, statistics, and import/export capabilities.
 */
class LanguagePairConfig private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LanguagePairConfig"
        private const val CONFIG_FILE_NAME = "language_pair_config.json"
        private const val BACKUP_DIR = "language_config_backups"
        private const val MAX_BACKUP_FILES = 15
        private const val VERSION_KEY = "language_config_version"
        private const val CURRENT_VERSION = 1
        
        @Volatile
        private var INSTANCE: LanguagePairConfig? = null
        
        fun getInstance(context: Context): LanguagePairConfig {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LanguagePairConfig(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val configMutex = Mutex()
    private val supportedLanguages = ConcurrentHashMap<String, SupportedLanguage>()
    private val languagePairs = ConcurrentHashMap<String, LanguagePair>()
    private val recentPairs = CopyOnWriteArrayList<String>()
    private val favoritePairs = CopyOnWriteArrayList<String>()
    private val usageStats = ConcurrentHashMap<String, LanguagePairUsage>()
    
    // Statistics
    private val totalTranslations = AtomicLong(0)
    private val lastConfigUpdate = AtomicLong(0)
    
    // State flows for reactive updates
    private val _supportedLanguagesFlow = MutableStateFlow<List<SupportedLanguage>>(emptyList())
    val supportedLanguagesFlow: Flow<List<SupportedLanguage>> = _supportedLanguagesFlow.asStateFlow()
    
    private val _languagePairsFlow = MutableStateFlow<List<LanguagePair>>(emptyList())
    val languagePairsFlow: Flow<List<LanguagePair>> = _languagePairsFlow.asStateFlow()
    
    private val _recentPairsFlow = MutableStateFlow<List<LanguagePair>>(emptyList())
    val recentPairsFlow: Flow<List<LanguagePair>> = _recentPairsFlow.asStateFlow()
    
    private val _favoritePairsFlow = MutableStateFlow<List<LanguagePair>>(emptyList())
    val favoritePairsFlow: Flow<List<LanguagePair>> = _favoritePairsFlow.asStateFlow()
    
    private val _usageStatsFlow = MutableStateFlow<List<LanguagePairUsage>>(emptyList())
    val usageStatsFlow: Flow<List<LanguagePairUsage>> = _usageStatsFlow.asStateFlow()
    
    /**
     * Supported language data structure
     */
    @Serializable
    data class SupportedLanguage(
        val code: String,
        val name: String,
        val nativeName: String,
        val region: String? = null,
        val script: String? = null,
        val isRTL: Boolean = false,
        val isSupported: Boolean = true,
        val isOnDeviceSupported: Boolean = false,
        val isCloudSupported: Boolean = true,
        val confidence: Float = 1.0f,
        val lastUpdated: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * Language pair data structure
     */
    @Serializable
    data class LanguagePair(
        val id: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val sourceLanguageName: String,
        val targetLanguageName: String,
        val isSupported: Boolean = true,
        val isOnDeviceSupported: Boolean = false,
        val isCloudSupported: Boolean = true,
        val confidence: Float = 1.0f,
        val quality: TranslationQuality = TranslationQuality.UNKNOWN,
        val isFavorite: Boolean = false,
        val usageCount: Long = 0,
        val lastUsed: Long = 0L,
        val averageConfidence: Float = 0.0f,
        val successRate: Float = 0.0f,
        val averageDuration: Long = 0L,
        val createdAt: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * Translation quality enumeration
     */
    @Serializable
    enum class TranslationQuality {
        UNKNOWN,
        POOR,
        FAIR,
        GOOD,
        EXCELLENT
    }
    
    /**
     * Language pair usage statistics
     */
    @Serializable
    data class LanguagePairUsage(
        val pairId: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val usageCount: Long = 0,
        val successCount: Long = 0,
        val failureCount: Long = 0,
        val totalDuration: Long = 0L,
        val averageConfidence: Float = 0.0f,
        val lastUsed: Long = 0L,
        val firstUsed: Long = System.currentTimeMillis(),
        val quality: TranslationQuality = TranslationQuality.UNKNOWN,
        val trends: List<UsageTrend> = emptyList()
    )
    
    /**
     * Usage trend data
     */
    @Serializable
    data class UsageTrend(
        val date: String, // YYYY-MM-DD format
        val usageCount: Long,
        val successCount: Long,
        val averageConfidence: Float
    )
    
    /**
     * Language detection result
     */
    @Serializable
    data class LanguageDetectionResult(
        val detectedLanguage: String,
        val confidence: Float,
        val alternatives: List<LanguageAlternative> = emptyList(),
        val isReliable: Boolean = false
    )
    
    /**
     * Language alternative
     */
    @Serializable
    data class LanguageAlternative(
        val language: String,
        val confidence: Float,
        val name: String
    )
    
    /**
     * Language pair configuration
     */
    @Serializable
    data class LanguagePairConfiguration(
        val version: Int = CURRENT_VERSION,
        val supportedLanguages: List<SupportedLanguage> = emptyList(),
        val languagePairs: List<LanguagePair> = emptyList(),
        val recentPairs: List<String> = emptyList(),
        val favoritePairs: List<String> = emptyList(),
        val usageStats: List<LanguagePairUsage> = emptyList(),
        val defaultSourceLanguage: String = "auto",
        val defaultTargetLanguage: String = "en",
        val maxRecentPairs: Int = 20,
        val maxFavoritePairs: Int = 50,
        val lastUpdated: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * Language pair search criteria
     */
    data class LanguagePairSearchCriteria(
        val sourceLanguage: String? = null,
        val targetLanguage: String? = null,
        val isSupported: Boolean? = null,
        val isOnDeviceSupported: Boolean? = null,
        val isCloudSupported: Boolean? = null,
        val minConfidence: Float? = null,
        val maxConfidence: Float? = null,
        val quality: TranslationQuality? = null,
        val isFavorite: Boolean? = null,
        val minUsageCount: Long? = null,
        val dateFrom: Long? = null,
        val dateTo: Long? = null,
        val sortBy: SortBy = SortBy.USAGE_COUNT_DESC,
        val limit: Int? = null
    )
    
    /**
     * Sort options for language pairs
     */
    enum class SortBy {
        USAGE_COUNT_DESC,    // Most used first
        USAGE_COUNT_ASC,     // Least used first
        CONFIDENCE_DESC,     // Highest confidence first
        CONFIDENCE_ASC,      // Lowest confidence first
        LAST_USED_DESC,      // Most recently used first
        LAST_USED_ASC,       // Least recently used first
        ALPHABETICAL_SOURCE, // Alphabetical by source language
        ALPHABETICAL_TARGET, // Alphabetical by target language
        QUALITY_DESC,        // Highest quality first
        QUALITY_ASC          // Lowest quality first
    }
    
    /**
     * Initializes the language pair configuration
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d("Initializing LanguagePairConfig", TAG)
            configMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "LanguagePairConfig already initialized")
                    return@withContext true
                }
                
                // Load default supported languages
                loadDefaultSupportedLanguages()
                
                // Load configuration from storage
                loadConfigurationFromStorage()
                
                // Update flows
                updateFlows()
                
                Log.d(TAG, "LanguagePairConfig initialized with ${supportedLanguages.size} languages and ${languagePairs.size} pairs")
                Logger.d(
                    "LanguagePairConfig ready with ${supportedLanguages.size} languages and ${languagePairs.size} pairs",
                    TAG
                )
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LanguagePairConfig", e)
            isInitialized.set(false)
            Logger.e("Failed to initialize LanguagePairConfig", TAG, e)
            false
        }
    }
    
    /**
     * Gets all supported languages
     */
    suspend fun getSupportedLanguages(): List<SupportedLanguage> = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                supportedLanguages.values.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get supported languages", e)
            emptyList()
        }
    }
    
    /**
     * Gets a specific supported language by code
     */
    suspend fun getSupportedLanguage(languageCode: String): SupportedLanguage? = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                supportedLanguages[languageCode]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get supported language: $languageCode", e)
            null
        }
    }
    
    /**
     * Adds or updates a supported language
     */
    suspend fun addSupportedLanguage(language: SupportedLanguage): Boolean = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                supportedLanguages[language.code] = language
                updateFlows()
                saveConfigurationToStorage()
                Log.d(TAG, "Added/updated supported language: ${language.code}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add supported language: ${language.code}", e)
            false
        }
    }
    
    /**
     * Gets all language pairs
     */
    suspend fun getLanguagePairs(): List<LanguagePair> = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                languagePairs.values.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get language pairs", e)
            emptyList()
        }
    }
    
    /**
     * Gets language pairs with filtering
     */
    suspend fun getLanguagePairs(criteria: LanguagePairSearchCriteria): List<LanguagePair> = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                var filteredPairs = languagePairs.values.toList()
                
                // Apply filters
                filteredPairs = applyFilters(filteredPairs, criteria)
                
                // Apply sorting
                filteredPairs = applySorting(filteredPairs, criteria.sortBy)
                
                // Apply limit
                if (criteria.limit != null && criteria.limit > 0) {
                    filteredPairs = filteredPairs.take(criteria.limit)
                }
                
                filteredPairs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get language pairs with criteria", e)
            emptyList()
        }
    }
    
    /**
     * Gets a specific language pair by ID
     */
    suspend fun getLanguagePair(pairId: String): LanguagePair? = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                languagePairs[pairId]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get language pair: $pairId", e)
            null
        }
    }
    
    /**
     * Gets a language pair by source and target languages
     */
    suspend fun getLanguagePair(sourceLanguage: String, targetLanguage: String): LanguagePair? = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                val pairId = generatePairId(sourceLanguage, targetLanguage)
                languagePairs[pairId]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get language pair: $sourceLanguage -> $targetLanguage", e)
            null
        }
    }
    
    /**
     * Adds or updates a language pair
     */
    suspend fun addLanguagePair(pair: LanguagePair): Boolean = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                languagePairs[pair.id] = pair
                updateFlows()
                saveConfigurationToStorage()
                Log.d(TAG, "Added/updated language pair: ${pair.id}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add language pair: ${pair.id}", e)
            false
        }
    }
    
    /**
     * Creates a new language pair from language codes
     */
    suspend fun createLanguagePair(sourceLanguage: String, targetLanguage: String): LanguagePair? = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                val sourceLang = supportedLanguages[sourceLanguage]
                val targetLang = supportedLanguages[targetLanguage]
                
                if (sourceLang == null || targetLang == null) {
                    Log.w(TAG, "Cannot create pair: unsupported languages")
                    return@withLock null
                }
                
                val pairId = generatePairId(sourceLanguage, targetLanguage)
                val pair = LanguagePair(
                    id = pairId,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    sourceLanguageName = sourceLang.name,
                    targetLanguageName = targetLang.name,
                    isSupported = true,
                    isOnDeviceSupported = sourceLang.isOnDeviceSupported && targetLang.isOnDeviceSupported,
                    isCloudSupported = sourceLang.isCloudSupported && targetLang.isCloudSupported
                )
                
                languagePairs[pairId] = pair
                updateFlows()
                saveConfigurationToStorage()
                
                Log.d(TAG, "Created language pair: ${pair.id}")
                pair
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create language pair: $sourceLanguage -> $targetLanguage", e)
            null
        }
    }
    
    /**
     * Updates language pair usage statistics
     */
    suspend fun updateLanguagePairUsage(
        pairId: String,
        success: Boolean,
        confidence: Float,
        duration: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                val pair = languagePairs[pairId]
                if (pair == null) {
                    Log.w(TAG, "Language pair not found: $pairId")
                    return@withLock false
                }
                
                // Update pair statistics
                val updatedPair = pair.copy(
                    usageCount = pair.usageCount + 1,
                    lastUsed = System.currentTimeMillis(),
                    averageConfidence = calculateAverageConfidence(pair.averageConfidence, pair.usageCount, confidence),
                    successRate = calculateSuccessRate(pair.successRate, pair.usageCount, success)
                )
                languagePairs[pairId] = updatedPair
                
                // Update usage statistics
                val usage = usageStats[pairId] ?: LanguagePairUsage(
                    pairId = pairId,
                    sourceLanguage = pair.sourceLanguage,
                    targetLanguage = pair.targetLanguage
                )
                
                val updatedUsage = usage.copy(
                    usageCount = usage.usageCount + 1,
                    successCount = usage.successCount + if (success) 1 else 0,
                    failureCount = usage.failureCount + if (success) 0 else 1,
                    totalDuration = usage.totalDuration + duration,
                    averageConfidence = calculateAverageConfidence(usage.averageConfidence, usage.usageCount, confidence),
                    lastUsed = System.currentTimeMillis()
                )
                
                usageStats[pairId] = updatedUsage
                
                // Add to recent pairs
                addToRecentPairs(pairId)
                
                totalTranslations.incrementAndGet()
                updateFlows()
                saveConfigurationToStorage()
                
                Log.d(TAG, "Updated usage for language pair: $pairId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update language pair usage: $pairId", e)
            false
        }
    }
    
    /**
     * Adds a language pair to favorites
     */
    suspend fun addToFavorites(pairId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                val pair = languagePairs[pairId]
                if (pair == null) {
                    Log.w(TAG, "Language pair not found: $pairId")
                    return@withLock false
                }
                
                if (!favoritePairs.contains(pairId)) {
                    favoritePairs.add(pairId)
                    
                    // Update pair
                    val updatedPair = pair.copy(isFavorite = true)
                    languagePairs[pairId] = updatedPair
                    
                    updateFlows()
                    saveConfigurationToStorage()
                    
                    Log.d(TAG, "Added to favorites: $pairId")
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add to favorites: $pairId", e)
            false
        }
    }
    
    /**
     * Removes a language pair from favorites
     */
    suspend fun removeFromFavorites(pairId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                favoritePairs.remove(pairId)
                
                // Update pair
                val pair = languagePairs[pairId]
                if (pair != null) {
                    val updatedPair = pair.copy(isFavorite = false)
                    languagePairs[pairId] = updatedPair
                }
                
                updateFlows()
                saveConfigurationToStorage()
                
                Log.d(TAG, "Removed from favorites: $pairId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove from favorites: $pairId", e)
            false
        }
    }
    
    /**
     * Gets recent language pairs
     */
    suspend fun getRecentPairs(limit: Int = 10): List<LanguagePair> = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                recentPairs.takeLast(limit).mapNotNull { pairId: String ->
                    languagePairs[pairId]
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recent pairs", e)
            emptyList()
        }
    }
    
    /**
     * Gets favorite language pairs
     */
    suspend fun getFavoritePairs(): List<LanguagePair> = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                favoritePairs.mapNotNull { pairId ->
                    languagePairs[pairId]
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get favorite pairs", e)
            emptyList()
        }
    }
    
    /**
     * Gets language pair usage statistics
     */
    suspend fun getUsageStatistics(): List<LanguagePairUsage> = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                usageStats.values.toList().sortedByDescending { it.usageCount }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get usage statistics", e)
            emptyList()
        }
    }
    
    /**
     * Detects language from text
     */
    suspend fun detectLanguage(text: String): LanguageDetectionResult? = withContext(Dispatchers.IO) {
        try {
            // Simple language detection based on character patterns
            // In a real implementation, this would use ML Kit Language ID
            val detectedLanguage = detectLanguageFromText(text)
            val confidence = calculateDetectionConfidence(text, detectedLanguage)
            
            LanguageDetectionResult(
                detectedLanguage = detectedLanguage,
                confidence = confidence,
                alternatives = generateAlternatives(detectedLanguage, confidence),
                isReliable = confidence > 0.7f
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect language", e)
            null
        }
    }
    
    /**
     * Validates a language pair
     */
    suspend fun validateLanguagePair(sourceLanguage: String, targetLanguage: String): Boolean = withContext(Dispatchers.IO) {
        try {
            configMutex.withLock {
                val sourceLang = supportedLanguages[sourceLanguage]
                val targetLang = supportedLanguages[targetLanguage]
                
                sourceLang != null && targetLang != null && sourceLang.isSupported && targetLang.isSupported
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate language pair", e)
            false
        }
    }
    
    /**
     * Gets configuration statistics
     */
    fun getConfigurationStatistics(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized.get(),
            "supportedLanguages" to supportedLanguages.size,
            "languagePairs" to languagePairs.size,
            "recentPairs" to recentPairs.size,
            "favoritePairs" to favoritePairs.size,
            "usageStats" to usageStats.size,
            "totalTranslations" to totalTranslations.get(),
            "lastConfigUpdate" to lastConfigUpdate.get(),
            "currentVersion" to CURRENT_VERSION
        )
    }
    
    // Private helper methods
    
    private fun generatePairId(sourceLanguage: String, targetLanguage: String): String {
        return "${sourceLanguage}_to_${targetLanguage}"
    }
    
    private fun applyFilters(pairs: List<LanguagePair>, criteria: LanguagePairSearchCriteria): List<LanguagePair> {
        return pairs.filter { pair ->
            // Source language filter
            criteria.sourceLanguage?.let { pair.sourceLanguage == it } ?: true
            
            // Target language filter
            criteria.targetLanguage?.let { pair.targetLanguage == it } ?: true
            
            // Support filters
            criteria.isSupported?.let { pair.isSupported == it } ?: true
            criteria.isOnDeviceSupported?.let { pair.isOnDeviceSupported == it } ?: true
            criteria.isCloudSupported?.let { pair.isCloudSupported == it } ?: true
            
            // Confidence filters
            criteria.minConfidence?.let { pair.confidence >= it } ?: true
            criteria.maxConfidence?.let { pair.confidence <= it } ?: true
            
            // Quality filter
            criteria.quality?.let { pair.quality == it } ?: true
            
            // Favorite filter
            criteria.isFavorite?.let { pair.isFavorite == it } ?: true
            
            // Usage count filter
            criteria.minUsageCount?.let { pair.usageCount >= it } ?: true
            
            // Date filters
            criteria.dateFrom?.let { pair.lastUsed >= it } ?: true
            criteria.dateTo?.let { pair.lastUsed <= it } ?: true
        }
    }
    
    private fun applySorting(pairs: List<LanguagePair>, sortBy: SortBy): List<LanguagePair> {
        return when (sortBy) {
            SortBy.USAGE_COUNT_DESC -> pairs.sortedByDescending { it.usageCount }
            SortBy.USAGE_COUNT_ASC -> pairs.sortedBy { it.usageCount }
            SortBy.CONFIDENCE_DESC -> pairs.sortedByDescending { it.confidence }
            SortBy.CONFIDENCE_ASC -> pairs.sortedBy { it.confidence }
            SortBy.LAST_USED_DESC -> pairs.sortedByDescending { it.lastUsed }
            SortBy.LAST_USED_ASC -> pairs.sortedBy { it.lastUsed }
            SortBy.ALPHABETICAL_SOURCE -> pairs.sortedBy { it.sourceLanguage }
            SortBy.ALPHABETICAL_TARGET -> pairs.sortedBy { it.targetLanguage }
            SortBy.QUALITY_DESC -> pairs.sortedByDescending { it.quality.ordinal }
            SortBy.QUALITY_ASC -> pairs.sortedBy { it.quality.ordinal }
        }
    }
    
    private fun calculateAverageConfidence(currentAverage: Float, currentCount: Long, newValue: Float): Float {
        if (currentCount == 0L) return newValue
        return (currentAverage * currentCount + newValue) / (currentCount + 1)
    }
    
    private fun calculateSuccessRate(currentRate: Float, currentCount: Long, success: Boolean): Float {
        if (currentCount == 0L) return if (success) 1.0f else 0.0f
        val currentSuccessCount = (currentRate * currentCount).toLong()
        val newSuccessCount = currentSuccessCount + if (success) 1 else 0
        return newSuccessCount.toFloat() / (currentCount + 1)
    }
    
    private fun addToRecentPairs(pairId: String) {
        recentPairs.remove(pairId)
        recentPairs.add(pairId)
        
        // Limit recent pairs
        if (recentPairs.size > 20) {
            recentPairs.removeAt(0)
        }
    }
    
    private fun detectLanguageFromText(text: String): String {
        // Simple language detection based on character patterns
        // This is a basic implementation - in production, use ML Kit Language ID
        
        val cyrillicPattern = Regex("[\\u0400-\\u04FF]")
        val arabicPattern = Regex("[\\u0600-\\u06FF]")
        val chinesePattern = Regex("[\\u4E00-\\u9FFF]")
        val japanesePattern = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
        val koreanPattern = Regex("[\\uAC00-\\uD7AF]")
        
        return when {
            cyrillicPattern.containsMatchIn(text) -> "ru"
            arabicPattern.containsMatchIn(text) -> "ar"
            chinesePattern.containsMatchIn(text) -> "zh"
            japanesePattern.containsMatchIn(text) -> "ja"
            koreanPattern.containsMatchIn(text) -> "ko"
            text.all { it.isLetter() && it <= 'z' } -> "en"
            else -> "en" // Default to English
        }
    }
    
    private fun calculateDetectionConfidence(text: String, detectedLanguage: String): Float {
        // Simple confidence calculation based on text characteristics
        // In production, this would use ML Kit Language ID confidence scores
        
        val textLength = text.length
        val characterMatches = when (detectedLanguage) {
            "ru" -> text.count { it in '\u0400'..'\u04FF' }
            "ar" -> text.count { it in '\u0600'..'\u06FF' }
            "zh" -> text.count { it in '\u4E00'..'\u9FFF' }
            "ja" -> text.count { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
            "ko" -> text.count { it in '\uAC00'..'\uD7AF' }
            else -> text.count { it.isLetter() && it <= 'z' }
        }
        
        return if (textLength > 0) (characterMatches.toFloat() / textLength).coerceIn(0f, 1f) else 0.5f
    }
    
    private fun generateAlternatives(detectedLanguage: String, confidence: Float): List<LanguageAlternative> {
        // Generate alternative language suggestions
        // In production, this would use ML Kit Language ID alternatives
        
        val alternatives = mutableListOf<LanguageAlternative>()
        
        when (detectedLanguage) {
            "en" -> alternatives.addAll(listOf(
                LanguageAlternative("es", confidence * 0.8f, "Spanish"),
                LanguageAlternative("fr", confidence * 0.7f, "French"),
                LanguageAlternative("de", confidence * 0.6f, "German")
            ))
            "es" -> alternatives.addAll(listOf(
                LanguageAlternative("en", confidence * 0.8f, "English"),
                LanguageAlternative("pt", confidence * 0.7f, "Portuguese"),
                LanguageAlternative("fr", confidence * 0.6f, "French")
            ))
            // Add more language alternatives as needed
        }
        
        return alternatives.sortedByDescending { it.confidence }.take(3)
    }
    
    private suspend fun loadDefaultSupportedLanguages() {
        try {
            val defaultLanguages = listOf(
                SupportedLanguage("en", "English", "English", "US", "Latn", false, true, true, true, 1.0f),
                SupportedLanguage("es", "Spanish", "Español", "ES", "Latn", false, true, true, true, 1.0f),
                SupportedLanguage("fr", "French", "Français", "FR", "Latn", false, true, true, true, 1.0f),
                SupportedLanguage("de", "German", "Deutsch", "DE", "Latn", false, true, true, true, 1.0f),
                SupportedLanguage("it", "Italian", "Italiano", "IT", "Latn", false, true, true, true, 1.0f),
                SupportedLanguage("pt", "Portuguese", "Português", "PT", "Latn", false, true, true, true, 1.0f),
                SupportedLanguage("ru", "Russian", "Русский", "RU", "Cyrl", false, true, true, true, 1.0f),
                SupportedLanguage("zh", "Chinese", "中文", "CN", "Hans", false, true, true, true, 1.0f),
                SupportedLanguage("ja", "Japanese", "日本語", "JP", "Hira", false, true, true, true, 1.0f),
                SupportedLanguage("ko", "Korean", "한국어", "KR", "Hang", false, true, true, true, 1.0f),
                SupportedLanguage("ar", "Arabic", "العربية", "SA", "Arab", true, true, true, true, 1.0f),
                SupportedLanguage("hi", "Hindi", "हिन्दी", "IN", "Deva", false, true, true, true, 1.0f),
                SupportedLanguage("auto", "Auto-detect", "Auto-detect", null, null, false, false, true, false, 0.8f)
            )
            
            defaultLanguages.forEach { language ->
                supportedLanguages[language.code] = language
            }
            
            Log.d(TAG, "Loaded ${defaultLanguages.size} default supported languages")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default supported languages", e)
        }
    }
    
    private suspend fun loadConfigurationFromStorage() {
        try {
            val configFile = File(context.filesDir, CONFIG_FILE_NAME)
            if (configFile.exists()) {
                val jsonString = configFile.readText()
                val config = json.decodeFromString<LanguagePairConfiguration>(jsonString)
                
                // Load supported languages
                config.supportedLanguages.forEach { language ->
                    supportedLanguages[language.code] = language
                }
                
                // Load language pairs
                config.languagePairs.forEach { pair ->
                    languagePairs[pair.id] = pair
                }
                
                // Load recent pairs
                recentPairs.clear()
                recentPairs.addAll(config.recentPairs)
                
                // Load favorite pairs
                favoritePairs.clear()
                favoritePairs.addAll(config.favoritePairs)
                
                // Load usage statistics
                config.usageStats.forEach { usage ->
                    usageStats[usage.pairId] = usage
                }
                
                Log.d(TAG, "Loaded configuration from storage")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load configuration from storage", e)
        }
    }
    
    private suspend fun saveConfigurationToStorage() {
        try {
            val configFile = File(context.filesDir, CONFIG_FILE_NAME)
            val config = LanguagePairConfiguration(
                supportedLanguages = supportedLanguages.values.toList(),
                languagePairs = languagePairs.values.toList(),
                recentPairs = recentPairs.toList(),
                favoritePairs = favoritePairs.toList(),
                usageStats = usageStats.values.toList(),
                lastUpdated = System.currentTimeMillis()
            )
            
            configFile.writeText(json.encodeToString(config))
            lastConfigUpdate.set(System.currentTimeMillis())
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save configuration to storage", e)
        }
    }
    
    private suspend fun updateFlows() {
        _supportedLanguagesFlow.value = supportedLanguages.values.toList()
        _languagePairsFlow.value = languagePairs.values.toList()
        _recentPairsFlow.value = recentPairs.mapNotNull { languagePairs[it] }
        _favoritePairsFlow.value = favoritePairs.mapNotNull { languagePairs[it] }
        _usageStatsFlow.value = usageStats.values.toList().sortedByDescending { it.usageCount }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        try {
            supportedLanguages.clear()
            languagePairs.clear()
            recentPairs.clear()
            favoritePairs.clear()
            usageStats.clear()
            
            _supportedLanguagesFlow.value = emptyList()
            _languagePairsFlow.value = emptyList()
            _recentPairsFlow.value = emptyList()
            _favoritePairsFlow.value = emptyList()
            _usageStatsFlow.value = emptyList()
            
            isInitialized.set(false)
            
            Log.d(TAG, "LanguagePairConfig cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

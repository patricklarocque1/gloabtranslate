package com.example.gloabtranslate.core.data.repository

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.data.local.db.TranslationHistoryDao
import com.example.gloabtranslate.core.data.local.db.TranslationHistoryDatabase
import com.example.gloabtranslate.core.data.local.db.TranslationHistoryEntity
import com.example.gloabtranslate.core.data.local.db.toEntity
import com.example.gloabtranslate.core.data.local.db.toRepositoryModel
import com.example.gloabtranslate.core.data.models.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Repository for managing translation history storage and retrieval.
 * Provides persistent storage, search, filtering, analytics, and export/import capabilities.
 */
class TranslationHistoryRepository private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "TranslationHistoryRepository"
        private const val HISTORY_FILE_NAME = "translation_history.json"
        private const val BACKUP_DIR = "translation_history_backups"
        private const val MAX_BACKUP_FILES = 20
        private const val MAX_HISTORY_ENTRIES = 10000
        private const val CLEANUP_THRESHOLD = 0.8f // Clean up when 80% full
        
        @Volatile
        private var INSTANCE: TranslationHistoryRepository? = null
        
        fun getInstance(context: Context): TranslationHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TranslationHistoryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val database: TranslationHistoryDatabase = TranslationHistoryDatabase.getInstance(context)
    private val dao: TranslationHistoryDao = database.translationHistoryDao()
    private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val repositoryMutex = Mutex()
    private val translationHistory = ConcurrentHashMap<String, TranslationHistoryEntry>()
    
    // Statistics
    private val totalTranslations = AtomicLong(0)
    private val successfulTranslations = AtomicLong(0)
    private val failedTranslations = AtomicLong(0)
    
    // State flows for reactive updates
    private val _historyFlow = MutableStateFlow<List<TranslationHistoryEntry>>(emptyList())
    val historyFlow: Flow<List<TranslationHistoryEntry>> = _historyFlow.asStateFlow()
    
    private val _statisticsFlow = MutableStateFlow<TranslationStatistics?>(null)
    val statisticsFlow: Flow<TranslationStatistics?> = _statisticsFlow.asStateFlow()
    
    /**
     * Translation history entry data structure
     */
    @Serializable
    data class TranslationHistoryEntry(
        val id: String,
        val originalText: String,
        val translatedText: String?,
        val sourceLanguage: String,
        val targetLanguage: String,
        val confidence: Float?,
        val isPartial: Boolean = false,
        val isOnDevice: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
        val duration: Long = 0L, // Translation duration in milliseconds
        val success: Boolean = false,
        val error: String? = null,
        val metadata: Map<String, String> = emptyMap(),
        val tags: List<String> = emptyList(),
        val favorite: Boolean = false
    )
    
    /**
     * Translation statistics
     */
    @Serializable
    data class TranslationStatistics(
        val totalTranslations: Long = 0,
        val successfulTranslations: Long = 0,
        val failedTranslations: Long = 0,
        val successRate: Float = 0f,
        val averageConfidence: Float = 0f,
        val averageDuration: Long = 0L,
        val mostUsedSourceLanguages: List<LanguageUsage> = emptyList(),
        val mostUsedTargetLanguages: List<LanguageUsage> = emptyList(),
        val translationsByDay: List<DailyTranslationCount> = emptyList(),
        val onDeviceTranslationCount: Long = 0,
        val cloudTranslationCount: Long = 0,
        val favoriteTranslations: Long = 0,
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    /**
     * Language usage statistics
     */
    @Serializable
    data class LanguageUsage(
        val languageCode: String,
        val languageName: String,
        val usageCount: Long,
        val percentage: Float
    )
    
    /**
     * Daily translation count
     */
    @Serializable
    data class DailyTranslationCount(
        val date: String, // YYYY-MM-DD format
        val count: Long,
        val successful: Long,
        val failed: Long
    )
    
    /**
     * Search and filter criteria
     */
    data class SearchCriteria(
        val query: String? = null,
        val sourceLanguage: String? = null,
        val targetLanguage: String? = null,
        val dateFrom: Long? = null,
        val dateTo: Long? = null,
        val favoriteOnly: Boolean = false,
        val successfulOnly: Boolean = false,
        val onDeviceOnly: Boolean = false,
        val tags: List<String>? = null,
        val minConfidence: Float? = null,
        val maxConfidence: Float? = null,
        val sortBy: SortBy = SortBy.TIMESTAMP_DESC,
        val limit: Int? = null
    )
    
    /**
     * Sort options
     */
    enum class SortBy {
        TIMESTAMP_DESC,      // Most recent first
        TIMESTAMP_ASC,       // Oldest first
        CONFIDENCE_DESC,     // Highest confidence first
        CONFIDENCE_ASC,      // Lowest confidence first
        DURATION_DESC,       // Longest duration first
        DURATION_ASC,        // Shortest duration first
        ALPHABETICAL_ASC,    // Alphabetical by original text
        ALPHABETICAL_DESC    // Reverse alphabetical by original text
    }
    
    /**
     * Export format options
     */
    enum class ExportFormat {
        JSON,               // JSON format
        CSV,                // CSV format
        TXT                 // Plain text format
    }
    
    /**
     * Repository configuration
     */
    data class RepositoryConfig(
        val maxHistoryEntries: Int = MAX_HISTORY_ENTRIES,
        val enableAutoBackup: Boolean = true,
        val backupIntervalHours: Int = 24,
        val enableStatistics: Boolean = true,
        val enableSearch: Boolean = true,
        val cleanupThreshold: Float = CLEANUP_THRESHOLD
    )
    
    /**
     * Initializes the translation history repository
     */
    suspend fun initialize(config: RepositoryConfig = RepositoryConfig()): Boolean = withContext(Dispatchers.IO) {
        try {
            var historySize = 0
            var shouldStartObservation = false

            repositoryMutex.withLock {
                if (isInitialized.get()) {
                    Log.w(TAG, "TranslationHistoryRepository already initialized")
                    historySize = translationHistory.size
                    return@withLock
                }

                isInitialized.set(true)
                migrateLegacyHistoryFromFile()
                loadHistoryFromDatabase()

                historySize = translationHistory.size
                shouldStartObservation = true
            }

            if (shouldStartObservation) {
                startObservingDao()
                Log.d(TAG, "TranslationHistoryRepository initialized with $historySize entries")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TranslationHistoryRepository", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Adds a translation to history
     */
    suspend fun addTranslation(translationResult: TranslationResult): Boolean = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                val entry = TranslationHistoryEntry(
                    id = generateId(),
                    originalText = translationResult.originalText ?: "",
                    translatedText = translationResult.translatedText,
                    sourceLanguage = translationResult.sourceLanguage ?: "",
                    targetLanguage = translationResult.targetLanguage ?: "",
                    confidence = translationResult.confidence,
                    isPartial = translationResult.isPartial,
                    isOnDevice = translationResult.isOnDevice,
                    timestamp = translationResult.timestamp,
                    success = translationResult.success,
                    error = translationResult.error,
                    metadata = mapOf(
                        "app_version" to getAppVersion(),
                        "device_model" to getDeviceModel()
                    )
                )
                
                dao.upsert(entry.toEntity())
                translationHistory[entry.id] = entry

                updateFlows()
                
                // Check if cleanup is needed
                if (translationHistory.size >= MAX_HISTORY_ENTRIES * CLEANUP_THRESHOLD) {
                    performCleanup()
                }
                
                Log.d(TAG, "Translation added to history: ${entry.id}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add translation to history", e)
            false
        }
    }
    
    /**
     * Gets translation history with optional filtering
     */
    suspend fun getTranslationHistory(criteria: SearchCriteria = SearchCriteria()): List<TranslationHistoryEntry> = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                var filteredEntries = translationHistory.values.toList()
                
                // Apply filters
                filteredEntries = applyFilters(filteredEntries, criteria)
                
                // Apply sorting
                filteredEntries = applySorting(filteredEntries, criteria.sortBy)
                
                // Apply limit
                if (criteria.limit != null && criteria.limit > 0) {
                    filteredEntries = filteredEntries.take(criteria.limit)
                }
                
                filteredEntries
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get translation history", e)
            emptyList()
        }
    }
    
    /**
     * Gets a specific translation by ID
     */
    suspend fun getTranslationById(id: String): TranslationHistoryEntry? = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                translationHistory[id]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get translation by ID: $id", e)
            null
        }
    }
    
    /**
     * Updates a translation entry
     */
    suspend fun updateTranslation(entry: TranslationHistoryEntry): Boolean = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                if (!translationHistory.containsKey(entry.id)) return@withLock false

                dao.upsert(entry.toEntity())
                translationHistory[entry.id] = entry
                updateFlows()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update translation: ${entry.id}", e)
            false
        }
    }
    
    /**
     * Deletes a translation entry
     */
    suspend fun deleteTranslation(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                val rows = dao.deleteById(id)
                if (rows > 0) {
                    translationHistory.remove(id)
                    updateFlows()
                    Log.d(TAG, "Translation deleted: $id")
                    true
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete translation: $id", e)
            false
        }
    }
    
    /**
     * Deletes multiple translation entries
     */
    suspend fun deleteTranslations(ids: List<String>): Int = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                val deletedCount = dao.deleteByIds(ids)
                if (deletedCount > 0) {
                    ids.forEach { translationHistory.remove(it) }
                    updateFlows()
                }
                deletedCount
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete translations", e)
            0
        }
    }
    
    /**
     * Clears all translation history
     */
    suspend fun clearAllHistory(): Boolean = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                dao.clear()
                translationHistory.clear()
                totalTranslations.set(0)
                successfulTranslations.set(0)
                failedTranslations.set(0)

                updateFlows()

                Log.d(TAG, "All translation history cleared")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all history", e)
            false
        }
    }
    
    /**
     * Gets translation statistics
     */
    suspend fun getTranslationStatistics(): TranslationStatistics = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                calculateStatistics()
                _statisticsFlow.value ?: TranslationStatistics()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get translation statistics", e)
            TranslationStatistics()
        }
    }
    
    /**
     * Searches translations
     */
    suspend fun searchTranslations(query: String): List<TranslationHistoryEntry> = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                val lowercaseQuery = query.lowercase()
                translationHistory.values.filter { entry ->
                    entry.originalText.lowercase().contains(lowercaseQuery) ||
                    entry.translatedText?.lowercase()?.contains(lowercaseQuery) == true
                }.sortedByDescending { it.timestamp }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search translations", e)
            emptyList()
        }
    }
    
    /**
     * Exports translation history
     */
    suspend fun exportHistory(format: ExportFormat = ExportFormat.JSON): File? = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                val exportDir = File(context.filesDir, "exports")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                
                val timestamp = System.currentTimeMillis()
                val fileName = when (format) {
                    ExportFormat.JSON -> "translation_history_export_$timestamp.json"
                    ExportFormat.CSV -> "translation_history_export_$timestamp.csv"
                    ExportFormat.TXT -> "translation_history_export_$timestamp.txt"
                }
                
                val exportFile = File(exportDir, fileName)
                
                when (format) {
                    ExportFormat.JSON -> {
                        val exportData = mapOf(
                            "export_timestamp" to timestamp,
                            "total_entries" to translationHistory.size,
                            "translations" to translationHistory.values.toList()
                        )
                        exportFile.writeText(json.encodeToString(exportData))
                    }
                    ExportFormat.CSV -> {
                        val csvContent = buildString {
                            appendLine("ID,Original Text,Translated Text,Source Language,Target Language,Confidence,Success,Timestamp,Favorite")
                            translationHistory.values.forEach { entry ->
                                appendLine("${entry.id},\"${entry.originalText}\",\"${entry.translatedText}\",${entry.sourceLanguage},${entry.targetLanguage},${entry.confidence},${entry.success},${entry.timestamp},${entry.favorite}")
                            }
                        }
                        exportFile.writeText(csvContent)
                    }
                    ExportFormat.TXT -> {
                        val txtContent = buildString {
                            appendLine("Translation History Export")
                            appendLine("Generated: ${java.util.Date(timestamp)}")
                            appendLine("Total Entries: ${translationHistory.size}")
                            appendLine("=".repeat(50))
                            appendLine()
                            
                            translationHistory.values.sortedByDescending { it.timestamp }.forEach { entry ->
                                appendLine("ID: ${entry.id}")
                                appendLine("Original: ${entry.originalText}")
                                appendLine("Translated: ${entry.translatedText}")
                                appendLine("Languages: ${entry.sourceLanguage} → ${entry.targetLanguage}")
                                appendLine("Confidence: ${entry.confidence}")
                                appendLine("Success: ${entry.success}")
                                appendLine("Timestamp: ${java.util.Date(entry.timestamp)}")
                                appendLine("Favorite: ${entry.favorite}")
                                appendLine("-".repeat(30))
                                appendLine()
                            }
                        }
                        exportFile.writeText(txtContent)
                    }
                }
                
                Log.d(TAG, "Translation history exported to: ${exportFile.absolutePath}")
                exportFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export translation history", e)
            null
        }
    }
    
    /**
     * Gets repository statistics
     */
    fun getRepositoryStatistics(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized.get(),
            "totalEntries" to translationHistory.size,
            "totalTranslations" to totalTranslations.get(),
            "successfulTranslations" to successfulTranslations.get(),
            "failedTranslations" to failedTranslations.get(),
            "maxEntries" to MAX_HISTORY_ENTRIES
        )
    }

    // Private helper methods

    private fun generateId(): String {
        return "translation_${UUID.randomUUID()}"
    }
    
    private fun applyFilters(entries: List<TranslationHistoryEntry>, criteria: SearchCriteria): List<TranslationHistoryEntry> {
        return entries.filter { entry ->
            val matchesQuery = criteria.query?.lowercase()?.let { query ->
                entry.originalText.lowercase().contains(query) ||
                    entry.translatedText?.lowercase()?.contains(query) == true
            } ?: true

            val matchesSource = criteria.sourceLanguage?.let { entry.sourceLanguage == it } ?: true
            val matchesTarget = criteria.targetLanguage?.let { entry.targetLanguage == it } ?: true

            val matchesFrom = criteria.dateFrom?.let { entry.timestamp >= it } ?: true
            val matchesTo = criteria.dateTo?.let { entry.timestamp <= it } ?: true

            val matchesFavorite = if (criteria.favoriteOnly) entry.favorite else true
            val matchesSuccess = if (criteria.successfulOnly) entry.success else true
            val matchesOnDevice = if (criteria.onDeviceOnly) entry.isOnDevice else true

            val matchesMinConfidence = criteria.minConfidence?.let { threshold ->
                entry.confidence?.let { it >= threshold } ?: false
            } ?: true

            val matchesMaxConfidence = criteria.maxConfidence?.let { threshold ->
                entry.confidence?.let { it <= threshold } ?: false
            } ?: true

            val matchesTags = criteria.tags?.let { tags ->
                tags.all { tag -> entry.tags.contains(tag) }
            } ?: true

            matchesQuery && matchesSource && matchesTarget &&
                matchesFrom && matchesTo && matchesFavorite &&
                matchesSuccess && matchesOnDevice && matchesMinConfidence &&
                matchesMaxConfidence && matchesTags
        }
    }
    
    private fun applySorting(entries: List<TranslationHistoryEntry>, sortBy: SortBy): List<TranslationHistoryEntry> {
        return when (sortBy) {
            SortBy.TIMESTAMP_DESC -> entries.sortedByDescending { it.timestamp }
            SortBy.TIMESTAMP_ASC -> entries.sortedBy { it.timestamp }
            SortBy.CONFIDENCE_DESC -> entries.sortedByDescending { it.confidence ?: 0f }
            SortBy.CONFIDENCE_ASC -> entries.sortedBy { it.confidence ?: 0f }
            SortBy.DURATION_DESC -> entries.sortedByDescending { it.duration }
            SortBy.DURATION_ASC -> entries.sortedBy { it.duration }
            SortBy.ALPHABETICAL_ASC -> entries.sortedBy { it.originalText }
            SortBy.ALPHABETICAL_DESC -> entries.sortedByDescending { it.originalText }
        }
    }
    
    private suspend fun calculateStatistics() {
        try {
            val entries = translationHistory.values.toList()
            
            val total = entries.size.toLong()
            val successful = entries.count { it.success }.toLong()
            val failed = total - successful

            totalTranslations.set(total)
            successfulTranslations.set(successful)
            failedTranslations.set(failed)

            val successRate = if (total > 0) successful.toFloat() / total else 0f

            val confidences = entries.mapNotNull { it.confidence }
            val avgConfidence = if (confidences.isNotEmpty()) confidences.average().toFloat() else 0f
            
            val avgDuration = if (entries.isNotEmpty()) {
                entries.map { it.duration }.average().toLong()
            } else 0L
            
            // Language usage statistics
            val sourceLanguageUsage = entries.groupingBy { it.sourceLanguage }
                .eachCount()
                .map { (code, count) ->
                    val percentage = if (total > 0) count.toFloat() / total else 0f
                    LanguageUsage(code, code, count.toLong(), percentage)
                }
                .sortedByDescending { it.usageCount }
                .take(10)

            val targetLanguageUsage = entries.groupingBy { it.targetLanguage }
                .eachCount()
                .map { (code, count) ->
                    val percentage = if (total > 0) count.toFloat() / total else 0f
                    LanguageUsage(code, code, count.toLong(), percentage)
                }
                .sortedByDescending { it.usageCount }
                .take(10)
            
            // Daily translation counts (last 30 days)
            val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
            val recentEntries = entries.filter { it.timestamp >= thirtyDaysAgo }
            
            val dailyCounts = recentEntries.groupingBy { entry ->
                java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(entry.timestamp))
            }.aggregate { _, accumulator: Triple<Long, Long, Long>?, element, _ ->
                if (accumulator == null) {
                    Triple(1L, if (element.success) 1L else 0L, if (element.success) 0L else 1L)
                } else {
                    Triple(
                        accumulator.first + 1,
                        accumulator.second + if (element.success) 1L else 0L,
                        accumulator.third + if (element.success) 0L else 1L
                    )
                }
            }.map { (date, counts) ->
                DailyTranslationCount(date, counts.first, counts.second, counts.third)
            }.sortedBy { it.date }
            
            val onDeviceCount = entries.count { it.isOnDevice }.toLong()
            val cloudCount = total - onDeviceCount
            val favoriteCount = entries.count { it.favorite }.toLong()
            
            val statistics = TranslationStatistics(
                totalTranslations = total,
                successfulTranslations = successful,
                failedTranslations = failed,
                successRate = successRate,
                averageConfidence = avgConfidence,
                averageDuration = avgDuration,
                mostUsedSourceLanguages = sourceLanguageUsage,
                mostUsedTargetLanguages = targetLanguageUsage,
                translationsByDay = dailyCounts,
                onDeviceTranslationCount = onDeviceCount,
                cloudTranslationCount = cloudCount,
                favoriteTranslations = favoriteCount,
                lastUpdated = System.currentTimeMillis()
            )
            
            _statisticsFlow.value = statistics
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate statistics", e)
        }
    }
    
    private suspend fun updateFlows() {
        _historyFlow.value = translationHistory.values.sortedByDescending { it.timestamp }
        calculateStatistics()
    }

    @Serializable
    private data class LegacyHistoryData(
        val version: Int = 1,
        val timestamp: Long = 0L,
        val entries: List<TranslationHistoryEntry> = emptyList(),
        val nextId: Long? = null
    )

    private suspend fun loadHistoryFromDatabase() {
        val entities = dao.getHistory()
        applySnapshot(entities)
        updateFlows()
    }

    private fun applySnapshot(entities: List<TranslationHistoryEntity>) {
        translationHistory.clear()
        entities.forEach { entity ->
            val entry = entity.toRepositoryModel()
            translationHistory[entry.id] = entry
        }
    }

    private fun startObservingDao() {
        if (observeJob?.isActive == true) return

        observeJob = observationScope.launch {
            dao.observeHistory().collectLatest { entities ->
                repositoryMutex.withLock {
                    applySnapshot(entities)
                    updateFlows()
                }
            }
        }
    }

    private suspend fun migrateLegacyHistoryFromFile() {
        val historyFile = File(context.filesDir, HISTORY_FILE_NAME)
        if (!historyFile.exists()) return

        try {
            val legacyData = json.decodeFromString<LegacyHistoryData>(historyFile.readText())
            if (legacyData.entries.isNotEmpty()) {
                dao.upsertAll(legacyData.entries.map { it.toEntity() })
                Log.d(TAG, "Migrated ${legacyData.entries.size} legacy history entries into Room")
            }
            historyFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy history file", e)
        }
    }

    private suspend fun performCleanup() {
        try {
            val entriesToKeep = MAX_HISTORY_ENTRIES / 2
            val sortedEntries = translationHistory.values.sortedByDescending { it.timestamp }
            
            val entriesToRemove = sortedEntries.drop(entriesToKeep)
            if (entriesToRemove.isNotEmpty()) {
                dao.deleteByIds(entriesToRemove.map { it.id })
                entriesToRemove.forEach { entry -> translationHistory.remove(entry.id) }
                updateFlows()
            }
            
            Log.d(TAG, "Cleanup performed: removed ${entriesToRemove.size} old entries")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform cleanup", e)
        }
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun getDeviceModel(): String {
        return try {
            android.os.Build.MODEL ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        try {
            observeJob?.cancel()
            observeJob = null
            translationHistory.clear()
            _historyFlow.value = emptyList()
            _statisticsFlow.value = null
            isInitialized.set(false)
            
            Log.d(TAG, "TranslationHistoryRepository cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

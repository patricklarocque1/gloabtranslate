package com.example.gloabtranslate.core.data.repository

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.gloabtranslate.core.data.local.db.TranslationHistoryDao
import com.example.gloabtranslate.core.data.local.db.TranslationHistoryDatabase
import com.example.gloabtranslate.core.data.local.db.TranslationHistoryEntity
import com.example.gloabtranslate.core.data.local.db.toEntity
import com.example.gloabtranslate.core.data.local.db.toRepositoryModel
import com.example.gloabtranslate.core.data.models.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private const val DEFAULT_RETENTION_DAYS = 30
    private const val MAX_RETENTION_DAYS = 365
        
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
    
    // Statistics - derived from database, not cached
    private val totalTranslations = AtomicLong(0)
    private val successfulTranslations = AtomicLong(0)
    private val failedTranslations = AtomicLong(0)
    
    // State flows for reactive updates - sourced directly from Room
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
            var shouldStartRetention = false

            repositoryMutex.withLock {
                if (isInitialized.get()) {
                    Log.w(TAG, "TranslationHistoryRepository already initialized")
                    historySize = dao.count().toInt()
                    return@withLock
                }

                isInitialized.set(true)
                migrateLegacyHistoryFromFile()
                loadHistoryFromDatabase()

                historySize = dao.count().toInt()
                shouldStartObservation = true
                shouldStartRetention = true
            }

            if (shouldStartObservation) {
                startObservingDao()
                Log.d(TAG, "TranslationHistoryRepository initialized with $historySize entries")
            }

            if (shouldStartRetention) {
                scheduleRetentionJob()
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
                // No need to manually update cache - Room observer will handle this
                
                // Check if cleanup is needed based on database count
                val currentCount = dao.count()
                if (currentCount >= MAX_HISTORY_ENTRIES * CLEANUP_THRESHOLD) {
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
                val query = buildSearchQuery(criteria)
                dao.getHistoryFiltered(query).map { it.toRepositoryModel() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get translation history", e)
            emptyList<TranslationHistoryEntry>()
        }
    }
    
    /**
     * Gets a specific translation by ID
     */
    suspend fun getTranslationById(id: String): TranslationHistoryEntry? = withContext(Dispatchers.IO) {
        try {
            repositoryMutex.withLock {
                dao.getById(id)?.toRepositoryModel()
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
                val existingEntry = dao.getById(entry.id)
                if (existingEntry == null) return@withLock false

                dao.upsert(entry.toEntity())
                // No need to manually update cache - Room observer will handle this
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
                    // No need to manually update cache - Room observer will handle this
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
                // No need to manually update cache - Room observer will handle this
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
                // Reset statistics counters
                totalTranslations.set(0)
                successfulTranslations.set(0)
                failedTranslations.set(0)
                // No need to manually update flows - Room observer will handle this

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
                val criteria = SearchCriteria(query = query)
                val searchQuery = buildSearchQuery(criteria)
                dao.getHistoryFiltered(searchQuery).map { it.toRepositoryModel() }
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
                
                val allEntries = dao.getHistory().map { it.toRepositoryModel() }
                when (format) {
                    ExportFormat.JSON -> {
                        val exportData = mapOf(
                            "export_timestamp" to timestamp,
                            "total_entries" to allEntries.size,
                            "translations" to allEntries
                        )
                        exportFile.writeText(json.encodeToString(exportData))
                    }
                    ExportFormat.CSV -> {
                        val csvContent = buildString {
                            appendLine("ID,Original Text,Translated Text,Source Language,Target Language,Confidence,Success,Timestamp,Favorite")
                            allEntries.forEach { entry ->
                                appendLine("${entry.id},\"${entry.originalText}\",\"${entry.translatedText}\",${entry.sourceLanguage},${entry.targetLanguage},${entry.confidence},${entry.success},${entry.timestamp},${entry.favorite}")
                            }
                        }
                        exportFile.writeText(csvContent)
                    }
                    ExportFormat.TXT -> {
                        val txtContent = buildString {
                            appendLine("Translation History Export")
                            appendLine("Generated: ${java.util.Date(timestamp)}")
                            appendLine("Total Entries: ${allEntries.size}")
                            appendLine("=".repeat(50))
                            appendLine()
                            
                            allEntries.sortedByDescending { it.timestamp }.forEach { entry ->
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
    suspend fun getRepositoryStatistics(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized.get(),
            "totalEntries" to dao.count(),
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
    
    private fun buildSearchQuery(criteria: SearchCriteria): SimpleSQLiteQuery {
        val query = StringBuilder("SELECT * FROM translation_history")
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        criteria.query?.let {
            conditions.add("(originalText LIKE ? OR translatedText LIKE ?)")
            args.add("%${it.lowercase()}%" )
            args.add("%${it.lowercase()}%")
        }

        criteria.sourceLanguage?.let {
            conditions.add("sourceLanguage = ?")
            args.add(it)
        }

        criteria.targetLanguage?.let {
            conditions.add("targetLanguage = ?")
            args.add(it)
        }

        criteria.dateFrom?.let {
            conditions.add("timestamp >= ?")
            args.add(it)
        }

        criteria.dateTo?.let {
            conditions.add("timestamp <= ?")
            args.add(it)
        }

        if (criteria.favoriteOnly) {
            conditions.add("favorite = 1")
        }

        if (criteria.successfulOnly) {
            conditions.add("success = 1")
        }

        if (criteria.onDeviceOnly) {
            conditions.add("isOnDevice = 1")
        }

        criteria.minConfidence?.let {
            conditions.add("confidence >= ?")
            args.add(it)
        }

        criteria.maxConfidence?.let {
            conditions.add("confidence <= ?")
            args.add(it)
        }

        if (conditions.isNotEmpty()) {
            query.append(" WHERE ").append(conditions.joinToString(" AND "))
        }

        query.append(" ORDER BY ").append(when (criteria.sortBy) {
            SortBy.TIMESTAMP_DESC -> "timestamp DESC"
            SortBy.TIMESTAMP_ASC -> "timestamp ASC"
            SortBy.CONFIDENCE_DESC -> "confidence DESC"
            SortBy.CONFIDENCE_ASC -> "confidence ASC"
            SortBy.DURATION_DESC -> "duration DESC"
            SortBy.DURATION_ASC -> "duration ASC"
            SortBy.ALPHABETICAL_ASC -> "originalText ASC"
            SortBy.ALPHABETICAL_DESC -> "originalText DESC"
        })

        criteria.limit?.let {
            query.append(" LIMIT ?")
            args.add(it)
        }

        return SimpleSQLiteQuery(query.toString(), args.toTypedArray())
    }
    
    private suspend fun calculateStatistics() {
        try {
            val entries = dao.getHistory().map { it.toRepositoryModel() }
            
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
        val entries = dao.getHistory().map { it.toRepositoryModel() }
        _historyFlow.value = entries.sortedByDescending { it.timestamp }
        updateStatistics()
    }
    
    private suspend fun updateStatistics() {
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
        // Initial load will be handled by the Room observer
        // No explicit loading needed as observer will trigger on subscription
    }

    private fun startObservingDao() {
        if (observeJob?.isActive == true) return

        observeJob = observationScope.launch {
            dao.observeHistory().collectLatest { entities ->
                repositoryMutex.withLock {
                    // Direct conversion from Room entities to UI state
                    val entries = entities.map { it.toRepositoryModel() }
                    _historyFlow.value = entries
                    updateStatistics()
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
            val allEntries = dao.getHistory().map { it.toRepositoryModel() }
            val sortedEntries = allEntries.sortedByDescending { it.timestamp }
            
            val entriesToRemove = sortedEntries.drop(entriesToKeep)
            if (entriesToRemove.isNotEmpty()) {
                dao.deleteByIds(entriesToRemove.map { it.id })
                // No need to manually update cache - Room observer will handle this
            }
            
            Log.d(TAG, "Cleanup performed: removed ${entriesToRemove.size} old entries")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform cleanup", e)
        }
    }

    // Retention job management
    private var retentionJob: Job? = null

    private fun scheduleRetentionJob() {
        if (retentionJob?.isActive == true) return
        retentionJob = observationScope.launch {
            // Initial delay before first pass (avoid startup contention)
            delay(10_000L)
            while (isActive) {
                try {
                    performRetentionPass()
                } catch (t: Throwable) {
                    Log.e(TAG, "Retention pass failed", t)
                }
                val prefsMgr = com.example.gloabtranslate.core.data.preferences.UserPreferencesManager.getInstance(context)
                val intervalHours = (prefsMgr.getPreference("cleanupIntervalHours") as? Int)?.coerceIn(1,168) ?: 24
                delay(intervalHours * 60L * 60L * 1000L)
            }
        }
    }

    private suspend fun performRetentionPass() = withContext(Dispatchers.IO) {
        val prefsMgr = com.example.gloabtranslate.core.data.preferences.UserPreferencesManager.getInstance(context)
        val retentionDays = (prefsMgr.getPreference("dataRetentionDays") as? Int)
            ?.coerceIn(1, MAX_RETENTION_DAYS) ?: DEFAULT_RETENTION_DAYS
        val threshold = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
        var removed = 0
        try {
            repositoryMutex.withLock {
                removed = dao.deleteOlderThan(threshold)
            }
            if (removed > 0) {
                Log.d(TAG, "Retention removed $removed entries older than $retentionDays days")
                updateFlows()
            } else {
                Log.d(TAG, "Retention pass completed - no removals (retentionDays=$retentionDays)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Retention pass failed", e)
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
            retentionJob?.cancel()
            retentionJob = null
            // No need to clear translationHistory - it's removed
            _historyFlow.value = emptyList()
            _statisticsFlow.value = null
            isInitialized.set(false)
            
            Log.d(TAG, "TranslationHistoryRepository cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

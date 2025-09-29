package com.example.gloabtranslate.nlp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Comprehensive model update mechanism that handles automatic update checking,
 * scheduling, validation, rollback, and recovery for ML models.
 */
class ModelUpdater private constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "ModelUpdater"
        private const val PREFERENCES_NAME = "model_updater_prefs"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_UPDATE_SCHEDULE = "update_schedule"
        private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
        private const val KEY_UPDATE_NOTIFICATIONS = "update_notifications"
        private const val KEY_UPDATE_HISTORY = "update_history"
        private const val KEY_BACKUP_ENABLED = "backup_enabled"
        private const val KEY_ROLLBACK_ENABLED = "rollback_enabled"
        
        private const val DEFAULT_UPDATE_CHECK_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
        private const val MIN_UPDATE_CHECK_INTERVAL = 60 * 60 * 1000L // 1 hour
        private const val MAX_UPDATE_CHECK_INTERVAL = 7 * 24 * 60 * 60 * 1000L // 7 days
        
        private const val MAX_UPDATE_HISTORY_SIZE = 50
        private const val UPDATE_TIMEOUT_MS = 300000L // 5 minutes
        
        @Volatile
        private var INSTANCE: ModelUpdater? = null
        
        fun getInstance(context: Context): ModelUpdater {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelUpdater(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Dependencies
    private lateinit var modelManager: ModelManager
    private lateinit var availabilityChecker: ModelAvailabilityChecker
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    
    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Update tracking
    private val updateJobs = ConcurrentHashMap<String, UpdateJob>()
    private val updateHistory = mutableListOf<UpdateRecord>()
    private val modelVersions = ConcurrentHashMap<String, ModelVersionInfo>()
    private val isUpdateCheckRunning = AtomicBoolean(false)
    private val isUpdateSchedulerRunning = AtomicBoolean(false)
    
    // Event flows
    private val _updateEvents = MutableSharedFlow<UpdateEvent>()
    val updateEvents: SharedFlow<UpdateEvent> = _updateEvents.asSharedFlow()
    
    // Update configuration
    private var updateCheckInterval = DEFAULT_UPDATE_CHECK_INTERVAL
    private var autoUpdateEnabled = true
    private var updateNotificationsEnabled = true
    private var backupEnabled = true
    private var rollbackEnabled = true
    
    /**
     * Update status
     */
    enum class UpdateStatus {
        IDLE,
        CHECKING,
        AVAILABLE,
        DOWNLOADING,
        INSTALLING,
        COMPLETED,
        FAILED,
        CANCELLED,
        ROLLING_BACK,
        ROLLED_BACK
    }
    
    /**
     * Update priority
     */
    enum class UpdatePriority {
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }
    
    /**
     * Update schedule
     */
    enum class UpdateSchedule {
        IMMEDIATE,
        SCHEDULED,
        WIFI_ONLY,
        MANUAL
    }
    
    /**
     * Model version information
     */
    @Serializable
    data class ModelVersionInfo(
        val modelId: String,
        val currentVersion: String,
        val latestVersion: String,
        val isUpdateAvailable: Boolean,
        val updatePriority: UpdatePriority,
        val updateSize: Long,
        val downloadUrl: String,
        val checksum: String,
        val releaseNotes: String,
        val releaseDate: Long,
        val compatibilityInfo: CompatibilityInfo,
        val metadata: Map<String, String>
    )
    
    /**
     * Compatibility information
     */
    @Serializable
    data class CompatibilityInfo(
        val minAndroidVersion: Int,
        val maxAndroidVersion: Int?,
        val requiredFeatures: List<String>,
        val breakingChanges: Boolean,
        val migrationRequired: Boolean
    )
    
    /**
     * Update record
     */
    @Serializable
    data class UpdateRecord(
        val modelId: String,
        val fromVersion: String,
        val toVersion: String,
        val updateType: UpdateType,
        val status: UpdateStatus,
        val timestamp: Long,
        val duration: Long,
        val errorMessage: String?,
        val rollbackVersion: String?,
        val metadata: Map<String, String>
    )
    
    /**
     * Update type
     */
    enum class UpdateType {
        AUTOMATIC,
        MANUAL,
        SCHEDULED,
        EMERGENCY
    }
    
    /**
     * Update job
     */
    private data class UpdateJob(
        val modelId: String,
        val job: Job,
        val startTime: Long,
        val isCancelled: AtomicBoolean = AtomicBoolean(false)
    )
    
    /**
     * Update events
     */
    sealed class UpdateEvent {
        data class UpdateCheckStarted(val modelId: String?) : UpdateEvent()
        data class UpdateCheckCompleted(val results: List<ModelVersionInfo>) : UpdateEvent()
        data class UpdateAvailable(val modelInfo: ModelVersionInfo) : UpdateEvent()
        data class UpdateStarted(val modelId: String, val fromVersion: String, val toVersion: String) : UpdateEvent()
        data class UpdateProgress(val modelId: String, val progress: Float) : UpdateEvent()
        data class UpdateCompleted(val modelId: String, val fromVersion: String, val toVersion: String) : UpdateEvent()
        data class UpdateFailed(val modelId: String, val error: String) : UpdateEvent()
        data class UpdateCancelled(val modelId: String) : UpdateEvent()
        data class RollbackStarted(val modelId: String, val toVersion: String) : UpdateEvent()
        data class RollbackCompleted(val modelId: String, val fromVersion: String, val toVersion: String) : UpdateEvent()
        data class RollbackFailed(val modelId: String, val error: String) : UpdateEvent()
    }
    
    /**
     * Initialize the model updater
     */
    suspend fun initialize() {
        try {
            Log.d(TAG, "Initializing ModelUpdater")
            
            // Initialize dependencies
            modelManager = ModelManager.getInstance(context)
            modelManager.initialize()
            
            availabilityChecker = ModelAvailabilityChecker.getInstance(context)
            availabilityChecker.initialize()
            
            // Load configuration
            loadConfiguration()
            
            // Load update history
            loadUpdateHistory()
            
            // Start update scheduler
            startUpdateScheduler()
            
            Log.d(TAG, "ModelUpdater initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ModelUpdater", e)
            throw e
        }
    }
    
    /**
     * Check for updates for all models
     */
    suspend fun checkForUpdates(forceCheck: Boolean = false): List<ModelVersionInfo> {
        return try {
            if (isUpdateCheckRunning.get() && !forceCheck) {
                Log.w(TAG, "Update check already running")
                return emptyList()
            }
            
            isUpdateCheckRunning.set(true)
            _updateEvents.emit(UpdateEvent.UpdateCheckStarted(null))
            
            val results = mutableListOf<ModelVersionInfo>()
            
            // Get all installed models
            val installedModels = availabilityChecker.getAllAvailableModels()
                .filter { it.isInstalled }
            
            for (model in installedModels) {
                try {
                    val versionInfo = checkModelForUpdates(model.modelId)
                    if (versionInfo != null) {
                        results.add(versionInfo)
                        modelVersions[model.modelId] = versionInfo
                        
                        if (versionInfo.isUpdateAvailable) {
                            _updateEvents.emit(UpdateEvent.UpdateAvailable(versionInfo))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check updates for model: ${model.modelId}", e)
                }
            }
            
            // Update last check time
            updateLastCheckTime()
            
            _updateEvents.emit(UpdateEvent.UpdateCheckCompleted(results))
            results
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            emptyList()
        } finally {
            isUpdateCheckRunning.set(false)
        }
    }
    
    /**
     * Check for updates for a specific model
     */
    suspend fun checkModelForUpdates(modelId: String): ModelVersionInfo? {
        return try {
            val currentAvailability = availabilityChecker.checkModelAvailability(modelId)
            val currentVersion = currentAvailability.version ?: "unknown"
            
            // Simulate fetching latest version info from server
            val latestVersionInfo = fetchLatestVersionInfo(modelId)
            
            if (latestVersionInfo != null) {
                val isUpdateAvailable = latestVersionInfo.latestVersion != currentVersion
                
                ModelVersionInfo(
                    modelId = modelId,
                    currentVersion = currentVersion,
                    latestVersion = latestVersionInfo.latestVersion,
                    isUpdateAvailable = isUpdateAvailable,
                    updatePriority = latestVersionInfo.updatePriority,
                    updateSize = latestVersionInfo.updateSize,
                    downloadUrl = latestVersionInfo.downloadUrl,
                    checksum = latestVersionInfo.checksum,
                    releaseNotes = latestVersionInfo.releaseNotes,
                    releaseDate = latestVersionInfo.releaseDate,
                    compatibilityInfo = latestVersionInfo.compatibilityInfo,
                    metadata = latestVersionInfo.metadata
                )
            } else {
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check updates for model: $modelId", e)
            null
        }
    }
    
    /**
     * Update a specific model
     */
    suspend fun updateModel(
        modelId: String,
        schedule: UpdateSchedule = UpdateSchedule.IMMEDIATE,
        priority: UpdatePriority = UpdatePriority.NORMAL
    ): Boolean {
        return try {
            val versionInfo = modelVersions[modelId] ?: checkModelForUpdates(modelId)
            if (versionInfo == null || !versionInfo.isUpdateAvailable) {
                Log.w(TAG, "No update available for model: $modelId")
                return false
            }
            
            val updateJob = scope.launch {
                performModelUpdate(versionInfo, schedule, priority)
            }
            
            val job = UpdateJob(
                modelId = modelId,
                job = updateJob,
                startTime = System.currentTimeMillis()
            )
            
            updateJobs[modelId] = job
            
            Log.d(TAG, "Started update for model: $modelId")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start update for model: $modelId", e)
            _updateEvents.emit(UpdateEvent.UpdateFailed(modelId, e.message ?: "Unknown error"))
            false
        }
    }
    
    /**
     * Cancel an update
     */
    suspend fun cancelUpdate(modelId: String): Boolean {
        return try {
            val updateJob = updateJobs[modelId]
            if (updateJob != null) {
                updateJob.isCancelled.set(true)
                updateJob.job.cancel()
                updateJobs.remove(modelId)
                
                _updateEvents.emit(UpdateEvent.UpdateCancelled(modelId))
                Log.d(TAG, "Cancelled update for model: $modelId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel update for model: $modelId", e)
            false
        }
    }
    
    /**
     * Rollback a model to previous version
     */
    suspend fun rollbackModel(modelId: String, toVersion: String? = null): Boolean {
        return try {
            val updateRecord = getLatestUpdateRecord(modelId)
            if (updateRecord == null) {
                Log.e(TAG, "No update history found for rollback: $modelId")
                return false
            }
            
            val rollbackVersion = toVersion ?: updateRecord.fromVersion
            if (rollbackVersion == updateRecord.toVersion) {
                Log.w(TAG, "Already at rollback version: $modelId")
                return true
            }
            
            _updateEvents.emit(UpdateEvent.RollbackStarted(modelId, rollbackVersion))
            
            // Create backup before rollback
            if (backupEnabled) {
                createModelBackup(modelId, "rollback_${System.currentTimeMillis()}")
            }
            
            // Remove current version
            modelManager.removeModel(modelId)
            
            // Download and install previous version
            val success = downloadAndInstallVersion(modelId, rollbackVersion)
            
            if (success) {
                // Update history
                addUpdateRecord(UpdateRecord(
                    modelId = modelId,
                    fromVersion = updateRecord.toVersion,
                    toVersion = rollbackVersion,
                    updateType = UpdateType.MANUAL,
                    status = UpdateStatus.ROLLED_BACK,
                    timestamp = System.currentTimeMillis(),
                    duration = 0,
                    errorMessage = null,
                    rollbackVersion = updateRecord.fromVersion,
                    metadata = mapOf("rollback" to "true")
                ))
                
                _updateEvents.emit(UpdateEvent.RollbackCompleted(modelId, updateRecord.toVersion, rollbackVersion))
                Log.d(TAG, "Successfully rolled back model: $modelId to version: $rollbackVersion")
                true
            } else {
                _updateEvents.emit(UpdateEvent.RollbackFailed(modelId, "Failed to install rollback version"))
                Log.e(TAG, "Failed to rollback model: $modelId")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rollback model: $modelId", e)
            _updateEvents.emit(UpdateEvent.RollbackFailed(modelId, e.message ?: "Unknown error"))
            false
        }
    }
    
    /**
     * Get update history for a model
     */
    suspend fun getUpdateHistory(modelId: String): List<UpdateRecord> {
        return updateHistory.filter { it.modelId == modelId }
    }
    
    /**
     * Get all update history
     */
    suspend fun getAllUpdateHistory(): List<UpdateRecord> {
        return updateHistory.toList()
    }
    
    /**
     * Get available updates
     */
    suspend fun getAvailableUpdates(): List<ModelVersionInfo> {
        return modelVersions.values.filter { it.isUpdateAvailable }
    }
    
    /**
     * Get update status for a model
     */
    suspend fun getUpdateStatus(modelId: String): UpdateStatus {
        return when {
            updateJobs.containsKey(modelId) -> UpdateStatus.DOWNLOADING
            modelVersions[modelId]?.isUpdateAvailable == true -> UpdateStatus.AVAILABLE
            else -> UpdateStatus.IDLE
        }
    }
    
    /**
     * Configure update settings
     */
    fun configureUpdates(
        autoUpdateEnabled: Boolean? = null,
        updateCheckInterval: Long? = null,
        updateNotificationsEnabled: Boolean? = null,
        backupEnabled: Boolean? = null,
        rollbackEnabled: Boolean? = null
    ) {
        autoUpdateEnabled?.let { this.autoUpdateEnabled = it }
        updateCheckInterval?.let { 
            this.updateCheckInterval = it.coerceIn(MIN_UPDATE_CHECK_INTERVAL, MAX_UPDATE_CHECK_INTERVAL)
        }
        updateNotificationsEnabled?.let { this.updateNotificationsEnabled = it }
        backupEnabled?.let { this.backupEnabled = it }
        rollbackEnabled?.let { this.rollbackEnabled = it }
        
        saveConfiguration()
    }
    
    /**
     * Enable/disable auto updates
     */
    fun setAutoUpdateEnabled(enabled: Boolean) {
        autoUpdateEnabled = enabled
        saveConfiguration()
        
        if (enabled) {
            startUpdateScheduler()
        } else {
            stopUpdateScheduler()
        }
    }
    
    /**
     * Set update check interval
     */
    fun setUpdateCheckInterval(intervalMs: Long) {
        updateCheckInterval = intervalMs.coerceIn(MIN_UPDATE_CHECK_INTERVAL, MAX_UPDATE_CHECK_INTERVAL)
        saveConfiguration()
    }
    
    /**
     * Schedule automatic updates
     */
    suspend fun scheduleUpdates() {
        if (!autoUpdateEnabled) return
        
        val availableUpdates = getAvailableUpdates()
        for (update in availableUpdates) {
            if (update.updatePriority == UpdatePriority.CRITICAL || 
                update.updatePriority == UpdatePriority.HIGH) {
                updateModel(update.modelId, UpdateSchedule.SCHEDULED)
            }
        }
    }
    
    // Private implementation methods
    
    private suspend fun performModelUpdate(
        versionInfo: ModelVersionInfo,
        schedule: UpdateSchedule,
        priority: UpdatePriority
    ) {
        val modelId = versionInfo.modelId
        val fromVersion = versionInfo.currentVersion
        val toVersion = versionInfo.latestVersion
        
        try {
            _updateEvents.emit(UpdateEvent.UpdateStarted(modelId, fromVersion, toVersion))
            
            // Create backup before update
            if (backupEnabled) {
                createModelBackup(modelId, "update_${System.currentTimeMillis()}")
            }
            
            // Download and install new version
            val success = downloadAndInstallVersion(modelId, toVersion, versionInfo)
            
            if (success) {
                // Update history
                val duration = System.currentTimeMillis() - (updateJobs[modelId]?.startTime ?: 0)
                addUpdateRecord(UpdateRecord(
                    modelId = modelId,
                    fromVersion = fromVersion,
                    toVersion = toVersion,
                    updateType = UpdateType.AUTOMATIC,
                    status = UpdateStatus.COMPLETED,
                    timestamp = System.currentTimeMillis(),
                    duration = duration,
                    errorMessage = null,
                    rollbackVersion = fromVersion,
                    metadata = versionInfo.metadata
                ))
                
                _updateEvents.emit(UpdateEvent.UpdateCompleted(modelId, fromVersion, toVersion))
                Log.d(TAG, "Successfully updated model: $modelId from $fromVersion to $toVersion")
            } else {
                throw Exception("Failed to install new version")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update model: $modelId", e)
            
            // Update history with error
            val duration = System.currentTimeMillis() - (updateJobs[modelId]?.startTime ?: 0)
            addUpdateRecord(UpdateRecord(
                modelId = modelId,
                fromVersion = fromVersion,
                toVersion = toVersion,
                updateType = UpdateType.AUTOMATIC,
                status = UpdateStatus.FAILED,
                timestamp = System.currentTimeMillis(),
                duration = duration,
                errorMessage = e.message,
                rollbackVersion = fromVersion,
                metadata = versionInfo.metadata
            ))
            
            _updateEvents.emit(UpdateEvent.UpdateFailed(modelId, e.message ?: "Unknown error"))
        } finally {
            updateJobs.remove(modelId)
        }
    }
    
    private suspend fun downloadAndInstallVersion(
        modelId: String, 
        version: String, 
        versionInfo: ModelVersionInfo? = null
    ): Boolean {
        return try {
            // Get download info
            val downloadInfo = versionInfo ?: fetchLatestVersionInfo(modelId)
            if (downloadInfo == null) {
                throw Exception("Failed to get download information")
            }
            
            // Download model
            val downloadSuccess = modelManager.downloadModel(
                modelId = modelId,
                modelType = ModelManager.ModelType.CUSTOM, // This would be determined from model info
                downloadUrl = downloadInfo.downloadUrl,
                version = version,
                checksum = downloadInfo.checksum,
                sizeBytes = downloadInfo.updateSize,
                supportedLanguages = emptyList(), // This would be from model info
                metadata = downloadInfo.metadata
            )
            
            if (!downloadSuccess) {
                throw Exception("Failed to download model")
            }
            
            // Wait for download to complete
            while (modelManager.isModelDownloading(modelId)) {
                delay(1000)
                
                // Check for cancellation
                if (updateJobs[modelId]?.isCancelled?.get() == true) {
                    throw CancellationException("Update cancelled")
                }
            }
            
            // Install model
            val installSuccess = modelManager.installModel(modelId)
            if (!installSuccess) {
                throw Exception("Failed to install model")
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download and install version: $version for model: $modelId", e)
            false
        }
    }
    
    private suspend fun fetchLatestVersionInfo(modelId: String): ModelVersionInfo? {
        // This would typically fetch from a server/API
        // For now, we'll simulate the response
        
        return try {
            delay(1000) // Simulate network delay
            
            // Simulate version info
            ModelVersionInfo(
                modelId = modelId,
                currentVersion = "1.0.0",
                latestVersion = "1.1.0",
                isUpdateAvailable = true,
                updatePriority = UpdatePriority.NORMAL,
                updateSize = 50 * 1024 * 1024, // 50MB
                downloadUrl = "https://example.com/models/$modelId/v1.1.0.model",
                checksum = "sha256:abcdef1234567890",
                releaseNotes = "Bug fixes and performance improvements",
                releaseDate = System.currentTimeMillis() - 86400000, // 1 day ago
                compatibilityInfo = CompatibilityInfo(
                    minAndroidVersion = 21,
                    maxAndroidVersion = null,
                    requiredFeatures = emptyList(),
                    breakingChanges = false,
                    migrationRequired = false
                ),
                metadata = mapOf(
                    "release_type" to "stable",
                    "compatibility" to "backward_compatible"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch latest version info for model: $modelId", e)
            null
        }
    }
    
    private suspend fun createModelBackup(modelId: String, backupId: String) {
        try {
            // Implementation would create a backup of the current model
            Log.d(TAG, "Created backup for model: $modelId with ID: $backupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup for model: $modelId", e)
        }
    }
    
    private fun startUpdateScheduler() {
        if (isUpdateSchedulerRunning.get()) return
        
        isUpdateSchedulerRunning.set(true)
        scope.launch {
            while (isActive && autoUpdateEnabled) {
                try {
                    delay(updateCheckInterval)
                    checkForUpdates()
                    scheduleUpdates()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in update scheduler", e)
                    delay(60000) // Wait 1 minute on error
                }
            }
            isUpdateSchedulerRunning.set(false)
        }
    }
    
    private fun stopUpdateScheduler() {
        isUpdateSchedulerRunning.set(false)
    }
    
    private fun updateLastCheckTime() {
        preferences.edit()
            .putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis())
            .apply()
    }
    
    private fun getLatestUpdateRecord(modelId: String): UpdateRecord? {
        return updateHistory
            .filter { it.modelId == modelId && it.status == UpdateStatus.COMPLETED }
            .maxByOrNull { it.timestamp }
    }
    
    private fun addUpdateRecord(record: UpdateRecord) {
        updateHistory.add(0, record) // Add to beginning
        
        // Limit history size
        if (updateHistory.size > MAX_UPDATE_HISTORY_SIZE) {
            updateHistory.removeAt(updateHistory.size - 1)
        }
        
        saveUpdateHistory()
    }
    
    private fun loadConfiguration() {
        updateCheckInterval = preferences.getLong(KEY_UPDATE_SCHEDULE, DEFAULT_UPDATE_CHECK_INTERVAL)
        autoUpdateEnabled = preferences.getBoolean(KEY_AUTO_UPDATE_ENABLED, true)
        updateNotificationsEnabled = preferences.getBoolean(KEY_UPDATE_NOTIFICATIONS, true)
        backupEnabled = preferences.getBoolean(KEY_BACKUP_ENABLED, true)
        rollbackEnabled = preferences.getBoolean(KEY_ROLLBACK_ENABLED, true)
    }
    
    private fun saveConfiguration() {
        preferences.edit()
            .putLong(KEY_UPDATE_SCHEDULE, updateCheckInterval)
            .putBoolean(KEY_AUTO_UPDATE_ENABLED, autoUpdateEnabled)
            .putBoolean(KEY_UPDATE_NOTIFICATIONS, updateNotificationsEnabled)
            .putBoolean(KEY_BACKUP_ENABLED, backupEnabled)
            .putBoolean(KEY_ROLLBACK_ENABLED, rollbackEnabled)
            .apply()
    }
    
    private fun loadUpdateHistory() {
        try {
            val historyJson = preferences.getString(KEY_UPDATE_HISTORY, null)
            if (historyJson != null) {
                val records = Json.decodeFromString<List<UpdateRecord>>(historyJson)
                updateHistory.clear()
                updateHistory.addAll(records)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load update history", e)
        }
    }
    
    private fun saveUpdateHistory() {
        try {
            val historyJson = Json.encodeToString(updateHistory)
            preferences.edit()
                .putString(KEY_UPDATE_HISTORY, historyJson)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save update history", e)
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        updateJobs.clear()
        updateHistory.clear()
        modelVersions.clear()
    }
}

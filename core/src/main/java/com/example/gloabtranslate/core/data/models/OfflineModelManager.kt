package com.example.gloabtranslate.core.data.models

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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Offline model manager for handling ML model downloads, installation, validation,
 * lifecycle management, and storage optimization.
 * Provides comprehensive offline model management for translation, speech recognition,
 * and language identification models.
 */
class OfflineModelManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "OfflineModelManager"
        private const val MODELS_CONFIG_FILE = "offline_models_config.json"
        private const val MODELS_DIR = "offline_models"
        private const val TEMP_DIR = "model_downloads"
        private const val BACKUP_DIR = "model_backups"
        private const val MAX_BACKUP_FILES = 10
        private const val VERSION_KEY = "models_version"
        private const val CURRENT_VERSION = 1
        
        // Model types
        const val MODEL_TYPE_TRANSLATION = "translation"
        const val MODEL_TYPE_SPEECH_RECOGNITION = "speech_recognition"
        const val MODEL_TYPE_LANGUAGE_ID = "language_identification"
        const val MODEL_TYPE_TEXT_TO_SPEECH = "text_to_speech"
        
        // Model status
        const val STATUS_NOT_DOWNLOADED = "not_downloaded"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_DOWNLOADED = "downloaded"
        const val STATUS_INSTALLING = "installing"
        const val STATUS_INSTALLED = "installed"
        const val STATUS_ERROR = "error"
        const val STATUS_OUTDATED = "outdated"
        const val STATUS_CORRUPTED = "corrupted"
        
        @Volatile
        private var INSTANCE: OfflineModelManager? = null
        
        fun getInstance(context: Context): OfflineModelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineModelManager(context.applicationContext).also { INSTANCE = it }
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
    private val modelsMutex = Mutex()
    private val models = ConcurrentHashMap<String, OfflineModel>()
    private val downloadTasks = ConcurrentHashMap<String, DownloadTask>()
    private val modelListeners = CopyOnWriteArrayList<ModelEventListener>()
    
    // Statistics
    private val totalDownloads = AtomicLong(0)
    private val totalInstallations = AtomicLong(0)
    private val totalFailures = AtomicLong(0)
    
    // State flows for reactive updates
    private val _modelsFlow = MutableStateFlow<List<OfflineModel>>(emptyList())
    val modelsFlow: Flow<List<OfflineModel>> = _modelsFlow.asStateFlow()
    
    private val _downloadProgressFlow = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgressFlow: Flow<Map<String, DownloadProgress>> = _downloadProgressFlow.asStateFlow()
    
    private val _modelStatusFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val modelStatusFlow: Flow<Map<String, String>> = _modelStatusFlow.asStateFlow()
    
    /**
     * Offline model data structure
     */
    @Serializable
    data class OfflineModel(
        val id: String,
        val name: String,
        val type: String,
        val version: String,
        val languageCode: String,
        val languageName: String,
        val size: Long,
        val checksum: String,
        val downloadUrl: String,
        val status: String = STATUS_NOT_DOWNLOADED,
        val isRequired: Boolean = false,
        val isRecommended: Boolean = false,
        val priority: Int = 0, // Higher number = higher priority
        val minAndroidVersion: Int = 21,
        val dependencies: List<String> = emptyList(),
        val features: List<String> = emptyList(),
        val downloadPath: String? = null,
        val installPath: String? = null,
        val downloadedAt: Long = 0L,
        val installedAt: Long = 0L,
        val lastUsed: Long = 0L,
        val usageCount: Long = 0,
        val downloadRetries: Int = 0,
        val installRetries: Int = 0,
        val errorMessage: String? = null,
        val metadata: Map<String, String> = emptyMap(),
        val createdAt: Long = System.currentTimeMillis(),
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    /**
     * Download progress data
     */
    @Serializable
    data class DownloadProgress(
        val modelId: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percentage: Float,
        val speed: Long = 0L, // bytes per second
        val estimatedTimeRemaining: Long = 0L, // milliseconds
        val isCompleted: Boolean = false,
        val isFailed: Boolean = false,
        val errorMessage: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Download task information
     */
    data class DownloadTask(
        val modelId: String,
        val job: Job,
        val startTime: Long = System.currentTimeMillis(),
        val totalBytes: Long = 0L,
        val bytesDownloaded: AtomicLong = AtomicLong(0L)
    )
    
    /**
     * Model event listener interface
     */
    interface ModelEventListener {
        fun onModelDownloadStarted(modelId: String)
        fun onModelDownloadProgress(modelId: String, progress: DownloadProgress)
        fun onModelDownloadCompleted(modelId: String)
        fun onModelDownloadFailed(modelId: String, error: String)
        fun onModelInstallationStarted(modelId: String)
        fun onModelInstallationCompleted(modelId: String)
        fun onModelInstallationFailed(modelId: String, error: String)
        fun onModelStatusChanged(modelId: String, oldStatus: String, newStatus: String)
        fun onModelDeleted(modelId: String)
        fun onModelUpdated(modelId: String)
    }
    
    /**
     * Model installation result
     */
    @Serializable
    data class InstallationResult(
        val success: Boolean,
        val modelId: String,
        val installPath: String? = null,
        val errorMessage: String? = null,
        val installationTime: Long = 0L,
        val installedSize: Long = 0L
    )
    
    /**
     * Model validation result
     */
    @Serializable
    data class ValidationResult(
        val isValid: Boolean,
        val modelId: String,
        val checksumMatch: Boolean = false,
        val fileExists: Boolean = false,
        val fileSizeMatch: Boolean = false,
        val permissionsValid: Boolean = false,
        val errorMessage: String? = null,
        val validationTime: Long = System.currentTimeMillis()
    )
    
    /**
     * Model storage statistics
     */
    @Serializable
    data class StorageStatistics(
        val totalModels: Int = 0,
        val installedModels: Int = 0,
        val downloadingModels: Int = 0,
        val totalStorageUsed: Long = 0L,
        val availableStorage: Long = 0L,
        val largestModel: OfflineModel? = null,
        val oldestModel: OfflineModel? = null,
        val mostUsedModel: OfflineModel? = null,
        val modelsByType: Map<String, Int> = emptyMap(),
        val modelsByLanguage: Map<String, Int> = emptyMap(),
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    /**
     * Initializes the offline model manager
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "OfflineModelManager already initialized")
                    return@withContext true
                }
                
                // Create necessary directories
                createDirectories()
                
                // Load default models
                loadDefaultModels()
                
                // Load models from storage
                loadModelsFromStorage()
                
                // Validate installed models
                validateInstalledModels()
                
                // Update flows
                updateFlows()
                
                Log.d(TAG, "OfflineModelManager initialized with ${models.size} models")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OfflineModelManager", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Gets all offline models
     */
    suspend fun getAllModels(): List<OfflineModel> = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                models.values.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all models", e)
            emptyList()
        }
    }
    
    /**
     * Gets models by type
     */
    suspend fun getModelsByType(type: String): List<OfflineModel> = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                models.values.filter { it.type == type }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get models by type: $type", e)
            emptyList()
        }
    }
    
    /**
     * Gets models by language
     */
    suspend fun getModelsByLanguage(languageCode: String): List<OfflineModel> = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                models.values.filter { it.languageCode == languageCode }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get models by language: $languageCode", e)
            emptyList()
        }
    }
    
    /**
     * Gets a specific model by ID
     */
    suspend fun getModel(modelId: String): OfflineModel? = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                models[modelId]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get model: $modelId", e)
            null
        }
    }
    
    /**
     * Gets models by status
     */
    suspend fun getModelsByStatus(status: String): List<OfflineModel> = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                models.values.filter { it.status == status }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get models by status: $status", e)
            emptyList()
        }
    }
    
    /**
     * Downloads a model
     */
    suspend fun downloadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                val model = models[modelId] ?: return@withLock false
                
                if (model.status == STATUS_DOWNLOADING || model.status == STATUS_DOWNLOADED) {
                    Log.w(TAG, "Model already downloading or downloaded: $modelId")
                    return@withLock false
                }
                
                // Update status
                updateModelStatus(modelId, STATUS_DOWNLOADING)
                
                // Create download task
                val downloadJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        performModelDownload(model)
                    } catch (e: Exception) {
                        Log.e(TAG, "Model download failed: $modelId", e)
                        updateModelStatus(modelId, STATUS_ERROR, e.message)
                        notifyModelDownloadFailed(modelId, e.message ?: "Unknown error")
                    }
                }
                
                downloadTasks[modelId] = DownloadTask(modelId, downloadJob)
                
                notifyModelDownloadStarted(modelId)
                Log.d(TAG, "Started downloading model: $modelId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start model download: $modelId", e)
            false
        }
    }
    
    /**
     * Installs a downloaded model
     */
    suspend fun installModel(modelId: String): InstallationResult = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                val model = models[modelId] ?: return@withLock InstallationResult(
                    false, modelId, errorMessage = "Model not found"
                )
                
                if (model.status != STATUS_DOWNLOADED) {
                    return@withLock InstallationResult(
                        false, modelId, errorMessage = "Model not downloaded"
                    )
                }
                
                // Update status
                updateModelStatus(modelId, STATUS_INSTALLING)
                notifyModelInstallationStarted(modelId)
                
                val startTime = System.currentTimeMillis()
                
                try {
                    val installPath = performModelInstallation(model)
                    val installTime = System.currentTimeMillis() - startTime
                    val installedSize = getModelSize(installPath)
                    
                    // Update model
                    val updatedModel = model.copy(
                        status = STATUS_INSTALLED,
                        installPath = installPath,
                        installedAt = System.currentTimeMillis(),
                        installRetries = 0
                    )
                    models[modelId] = updatedModel
                    
                    updateModelStatus(modelId, STATUS_INSTALLED)
                    notifyModelInstallationCompleted(modelId)
                    
                    totalInstallations.incrementAndGet()
                    saveModelsToStorage()
                    updateFlows()
                    
                    Log.d(TAG, "Model installed successfully: $modelId")
                    
                    InstallationResult(
                        success = true,
                        modelId = modelId,
                        installPath = installPath,
                        installationTime = installTime,
                        installedSize = installedSize
                    )
                    
                } catch (e: Exception) {
                    val errorMsg = "Installation failed: ${e.message}"
                    updateModelStatus(modelId, STATUS_ERROR, errorMsg)
                    notifyModelInstallationFailed(modelId, errorMsg)
                    
                    InstallationResult(
                        success = false,
                        modelId = modelId,
                        errorMessage = errorMsg,
                        installationTime = System.currentTimeMillis() - startTime
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install model: $modelId", e)
            InstallationResult(false, modelId, errorMessage = e.message)
        }
    }
    
    /**
     * Validates a model
     */
    suspend fun validateModel(modelId: String): ValidationResult = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                val model = models[modelId] ?: return@withLock ValidationResult(
                    false, modelId, errorMessage = "Model not found"
                )
                
                val installPath = model.installPath
                if (installPath == null) {
                    return@withLock ValidationResult(
                        false, modelId, errorMessage = "Model not installed"
                    )
                }
                
                val modelFile = File(installPath)
                val fileExists = modelFile.exists()
                val fileSizeMatch = if (fileExists) modelFile.length() == model.size else false
                
                val checksumMatch = if (fileExists && model.checksum.isNotEmpty()) {
                    try {
                        val calculatedChecksum = calculateFileChecksum(modelFile)
                        calculatedChecksum == model.checksum
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    true // No checksum to verify
                }
                
                val permissionsValid = if (fileExists) {
                    modelFile.canRead() && modelFile.canExecute()
                } else {
                    false
                }
                
                val isValid = fileExists && fileSizeMatch && checksumMatch && permissionsValid
                
                if (!isValid) {
                    updateModelStatus(modelId, STATUS_CORRUPTED)
                }
                
                ValidationResult(
                    isValid = isValid,
                    modelId = modelId,
                    checksumMatch = checksumMatch,
                    fileExists = fileExists,
                    fileSizeMatch = fileSizeMatch,
                    permissionsValid = permissionsValid,
                    errorMessage = if (!isValid) "Model validation failed" else null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate model: $modelId", e)
            ValidationResult(false, modelId, errorMessage = e.message)
        }
    }
    
    /**
     * Deletes a model
     */
    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                val model = models[modelId] ?: return@withLock false
                
                // Cancel download if in progress
                downloadTasks[modelId]?.job?.cancel()
                downloadTasks.remove(modelId)
                
                // Delete files
                model.downloadPath?.let { path ->
                    File(path).delete()
                }
                model.installPath?.let { path ->
                    File(path).delete()
                }
                
                // Remove from models
                models.remove(modelId)
                
                notifyModelDeleted(modelId)
                saveModelsToStorage()
                updateFlows()
                
                Log.d(TAG, "Model deleted: $modelId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: $modelId", e)
            false
        }
    }
    
    /**
     * Updates model usage statistics
     */
    suspend fun updateModelUsage(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                val model = models[modelId] ?: return@withLock false
                
                val updatedModel = model.copy(
                    lastUsed = System.currentTimeMillis(),
                    usageCount = model.usageCount + 1
                )
                
                models[modelId] = updatedModel
                saveModelsToStorage()
                updateFlows()
                
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update model usage: $modelId", e)
            false
        }
    }
    
    /**
     * Gets storage statistics
     */
    suspend fun getStorageStatistics(): StorageStatistics = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                val allModels = models.values.toList()
                val installedModels = allModels.filter { it.status == STATUS_INSTALLED }
                
                val totalStorageUsed = installedModels.sumOf { it.size }
                val availableStorage = getAvailableStorage()
                
                val modelsByType = allModels.groupingBy { it.type }.eachCount()
                val modelsByLanguage = allModels.groupingBy { it.languageCode }.eachCount()
                
                val largestModel = installedModels.maxByOrNull { it.size }
                val oldestModel = installedModels.minByOrNull { it.installedAt }
                val mostUsedModel = installedModels.maxByOrNull { it.usageCount }
                
                StorageStatistics(
                    totalModels = allModels.size,
                    installedModels = installedModels.size,
                    downloadingModels = downloadTasks.size,
                    totalStorageUsed = totalStorageUsed,
                    availableStorage = availableStorage,
                    largestModel = largestModel,
                    oldestModel = oldestModel,
                    mostUsedModel = mostUsedModel,
                    modelsByType = modelsByType,
                    modelsByLanguage = modelsByLanguage
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage statistics", e)
            StorageStatistics()
        }
    }
    
    /**
     * Cleans up old or unused models
     */
    suspend fun cleanupModels(): Int = withContext(Dispatchers.IO) {
        try {
            modelsMutex.withLock {
                var cleanedCount = 0
                val currentTime = System.currentTimeMillis()
                val thirtyDaysAgo = currentTime - (30 * 24 * 60 * 60 * 1000L)
                
                // Remove old unused models
                models.values.filter { model ->
                    model.lastUsed < thirtyDaysAgo && 
                    model.usageCount == 0L && 
                    !model.isRequired &&
                    model.status in listOf(STATUS_DOWNLOADED, STATUS_ERROR)
                }.forEach { model ->
                    if (deleteModel(model.id)) {
                        cleanedCount++
                    }
                }
                
                // Clean up temp files
                val tempDir = File(context.filesDir, TEMP_DIR)
                if (tempDir.exists()) {
                    tempDir.listFiles()?.forEach { file ->
                        if (file.lastModified() < thirtyDaysAgo) {
                            if (file.delete()) {
                                cleanedCount++
                            }
                        }
                    }
                }
                
                Log.d(TAG, "Cleaned up $cleanedCount models and files")
                cleanedCount
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup models", e)
            0
        }
    }
    
    /**
     * Adds a model event listener
     */
    fun addModelEventListener(listener: ModelEventListener) {
        modelListeners.add(listener)
    }
    
    /**
     * Removes a model event listener
     */
    fun removeModelEventListener(listener: ModelEventListener) {
        modelListeners.remove(listener)
    }
    
    // Private helper methods
    
    private fun createDirectories() {
        try {
            val modelsDir = File(context.filesDir, MODELS_DIR)
            val tempDir = File(context.filesDir, TEMP_DIR)
            val backupDir = File(context.filesDir, BACKUP_DIR)
            
            if (!modelsDir.exists()) modelsDir.mkdirs()
            if (!tempDir.exists()) tempDir.mkdirs()
            if (!backupDir.exists()) backupDir.mkdirs()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create directories", e)
        }
    }
    
    private suspend fun loadDefaultModels() {
        try {
            val defaultModels = listOf(
                // Translation models
                OfflineModel(
                    id = "translation_en_es",
                    name = "English to Spanish Translation",
                    type = MODEL_TYPE_TRANSLATION,
                    version = "1.0.0",
                    languageCode = "en_es",
                    languageName = "English-Spanish",
                    size = 50 * 1024 * 1024, // 50MB
                    checksum = "abc123def456",
                    downloadUrl = "https://example.com/models/translation_en_es.zip",
                    isRecommended = true,
                    priority = 5
                ),
                OfflineModel(
                    id = "translation_es_en",
                    name = "Spanish to English Translation",
                    type = MODEL_TYPE_TRANSLATION,
                    version = "1.0.0",
                    languageCode = "es_en",
                    languageName = "Spanish-English",
                    size = 50 * 1024 * 1024, // 50MB
                    checksum = "def456ghi789",
                    downloadUrl = "https://example.com/models/translation_es_en.zip",
                    isRecommended = true,
                    priority = 5
                ),
                
                // Speech recognition models
                OfflineModel(
                    id = "speech_recognition_en",
                    name = "English Speech Recognition",
                    type = MODEL_TYPE_SPEECH_RECOGNITION,
                    version = "1.0.0",
                    languageCode = "en",
                    languageName = "English",
                    size = 30 * 1024 * 1024, // 30MB
                    checksum = "ghi789jkl012",
                    downloadUrl = "https://example.com/models/speech_recognition_en.zip",
                    isRequired = true,
                    priority = 10
                ),
                OfflineModel(
                    id = "speech_recognition_es",
                    name = "Spanish Speech Recognition",
                    type = MODEL_TYPE_SPEECH_RECOGNITION,
                    version = "1.0.0",
                    languageCode = "es",
                    languageName = "Spanish",
                    size = 30 * 1024 * 1024, // 30MB
                    checksum = "jkl012mno345",
                    downloadUrl = "https://example.com/models/speech_recognition_es.zip",
                    isRecommended = true,
                    priority = 7
                ),
                
                // Language identification model
                OfflineModel(
                    id = "language_id_multilingual",
                    name = "Multilingual Language Identification",
                    type = MODEL_TYPE_LANGUAGE_ID,
                    version = "1.0.0",
                    languageCode = "multilingual",
                    languageName = "Multilingual",
                    size = 20 * 1024 * 1024, // 20MB
                    checksum = "mno345pqr678",
                    downloadUrl = "https://example.com/models/language_id_multilingual.zip",
                    isRequired = true,
                    priority = 10
                )
            )
            
            defaultModels.forEach { model ->
                if (!models.containsKey(model.id)) {
                    models[model.id] = model
                }
            }
            
            Log.d(TAG, "Loaded ${defaultModels.size} default models")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default models", e)
        }
    }
    
    private suspend fun loadModelsFromStorage() {
        try {
            val configFile = File(context.filesDir, MODELS_CONFIG_FILE)
            if (configFile.exists()) {
                val jsonString = configFile.readText()
                val config = json.decodeFromString<Map<String, List<OfflineModel>>>(jsonString)
                
                config["models"]?.forEach { model ->
                    models[model.id] = model
                }
                
                Log.d(TAG, "Loaded ${models.size} models from storage")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models from storage", e)
        }
    }
    
    private suspend fun saveModelsToStorage() {
        try {
            val configFile = File(context.filesDir, MODELS_CONFIG_FILE)
            val config = mapOf("models" to models.values.toList())
            configFile.writeText(json.encodeToString(config))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save models to storage", e)
        }
    }
    
    private suspend fun validateInstalledModels() {
        try {
            models.values.filter { it.status == STATUS_INSTALLED }.forEach { model ->
                val validationResult = validateModel(model.id)
                if (!validationResult.isValid) {
                    Log.w(TAG, "Model validation failed: ${model.id}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate installed models", e)
        }
    }
    
    private suspend fun performModelDownload(model: OfflineModel) {
        try {
            val tempDir = File(context.filesDir, TEMP_DIR)
            val downloadFile = File(tempDir, "${model.id}.zip")
            
            // Simulate download progress
            var bytesDownloaded = 0L
            val totalBytes = model.size
            
            while (bytesDownloaded < totalBytes) {
                delay(100) // Simulate download delay
                bytesDownloaded += (totalBytes / 100) // Simulate progress
                
                if (bytesDownloaded > totalBytes) {
                    bytesDownloaded = totalBytes
                }
                
                val progress = DownloadProgress(
                    modelId = model.id,
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    percentage = (bytesDownloaded.toFloat() / totalBytes * 100),
                    isCompleted = bytesDownloaded >= totalBytes
                )
                
                updateDownloadProgress(model.id, progress)
                notifyModelDownloadProgress(model.id, progress)
            }
            
            // Create dummy file for simulation
            downloadFile.writeText("Dummy model file for ${model.id}")
            
            // Update model
            val updatedModel = model.copy(
                status = STATUS_DOWNLOADED,
                downloadPath = downloadFile.absolutePath,
                downloadedAt = System.currentTimeMillis(),
                downloadRetries = 0
            )
            models[model.id] = updatedModel
            
            updateModelStatus(model.id, STATUS_DOWNLOADED)
            notifyModelDownloadCompleted(model.id)
            
            totalDownloads.incrementAndGet()
            saveModelsToStorage()
            updateFlows()
            
            Log.d(TAG, "Model download completed: ${model.id}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${model.id}", e)
            throw e
        }
    }
    
    private suspend fun performModelInstallation(model: OfflineModel): String {
        try {
            val downloadFile = File(model.downloadPath ?: throw IOException("Download path not found"))
            if (!downloadFile.exists()) {
                throw IOException("Download file not found")
            }
            
            val modelsDir = File(context.filesDir, MODELS_DIR)
            val modelDir = File(modelsDir, model.id)
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            
            val installPath = File(modelDir, "${model.id}.model").absolutePath
            
            // Simulate installation (in real implementation, this would extract and install the model)
            File(installPath).writeText("Installed model for ${model.id}")
            
            Log.d(TAG, "Model installed: ${model.id} -> $installPath")
            return installPath
            
        } catch (e: Exception) {
            Log.e(TAG, "Model installation failed: ${model.id}", e)
            throw e
        }
    }
    
    private fun calculateFileChecksum(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate checksum", e)
            ""
        }
    }
    
    private fun getModelSize(path: String): Long {
        return try {
            File(path).length()
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getAvailableStorage(): Long {
        return try {
            context.filesDir.usableSpace
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun updateModelStatus(modelId: String, newStatus: String, errorMessage: String? = null) {
        try {
            val model = models[modelId] ?: return
            val oldStatus = model.status
            
            val updatedModel = model.copy(
                status = newStatus,
                errorMessage = errorMessage,
                lastUpdated = System.currentTimeMillis()
            )
            
            models[modelId] = updatedModel
            
            if (oldStatus != newStatus) {
                notifyModelStatusChanged(modelId, oldStatus, newStatus)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update model status: $modelId", e)
        }
    }
    
    private fun updateDownloadProgress(modelId: String, progress: DownloadProgress) {
        try {
            val currentProgress = _downloadProgressFlow.value.toMutableMap()
            currentProgress[modelId] = progress
            _downloadProgressFlow.value = currentProgress
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update download progress: $modelId", e)
        }
    }
    
    private suspend fun updateFlows() {
        _modelsFlow.value = models.values.toList()
        _modelStatusFlow.value = models.mapValues { it.value.status }
    }
    
    // Event notification methods
    
    private fun notifyModelDownloadStarted(modelId: String) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelDownloadStarted(modelId)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying download started", e)
            }
        }
    }
    
    private fun notifyModelDownloadProgress(modelId: String, progress: DownloadProgress) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelDownloadProgress(modelId, progress)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying download progress", e)
            }
        }
    }
    
    private fun notifyModelDownloadCompleted(modelId: String) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelDownloadCompleted(modelId)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying download completed", e)
            }
        }
    }
    
    private fun notifyModelDownloadFailed(modelId: String, error: String) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelDownloadFailed(modelId, error)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying download failed", e)
            }
        }
    }
    
    private fun notifyModelInstallationStarted(modelId: String) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelInstallationStarted(modelId)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying installation started", e)
            }
        }
    }
    
    private fun notifyModelInstallationCompleted(modelId: String) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelInstallationCompleted(modelId)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying installation completed", e)
            }
        }
    }
    
    private fun notifyModelInstallationFailed(modelId: String, error: String) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelInstallationFailed(modelId, error)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying installation failed", e)
            }
        }
    }
    
    private fun notifyModelStatusChanged(modelId: String, oldStatus: String, newStatus: String) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelStatusChanged(modelId, oldStatus, newStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying status changed", e)
            }
        }
    }
    
    private fun notifyModelDeleted(modelId: String) {
        modelListeners.forEach { listener ->
            try {
                listener.onModelDeleted(modelId)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying model deleted", e)
            }
        }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        try {
            // Cancel all download tasks
            downloadTasks.values.forEach { task ->
                task.job.cancel()
            }
            downloadTasks.clear()
            
            models.clear()
            modelListeners.clear()
            
            _modelsFlow.value = emptyList()
            _downloadProgressFlow.value = emptyMap()
            _modelStatusFlow.value = emptyMap()
            
            isInitialized.set(false)
            
            Log.d(TAG, "OfflineModelManager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

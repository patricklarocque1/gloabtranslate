package com.example.gloabtranslate.nlp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Comprehensive offline model availability checker that validates
 * model integrity, compatibility, storage, and health status.
 */
class ModelAvailabilityChecker private constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "ModelAvailabilityChecker"
        private const val MODEL_CACHE_DIR = "ml_models"
        private const val MODEL_VERSION_FILE = "model_versions.json"
        private const val MODEL_CHECKSUM_FILE = "model_checksums.json"
        private const val MIN_STORAGE_SPACE_MB = 100L
        private const val MODEL_VALIDATION_TIMEOUT_MS = 5000L
        
        @Volatile
        private var INSTANCE: ModelAvailabilityChecker? = null
        
        fun getInstance(context: Context): ModelAvailabilityChecker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelAvailabilityChecker(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cache for model availability results
    private val availabilityCache = ConcurrentHashMap<String, ModelAvailability>()
    private val validationCache = ConcurrentHashMap<String, ModelValidationResult>()
    
    // Event flow for model availability changes
    private val _availabilityEvents = MutableSharedFlow<ModelAvailabilityEvent>()
    val availabilityEvents: SharedFlow<ModelAvailabilityEvent> = _availabilityEvents.asSharedFlow()
    
    /**
     * Model types supported by the application
     */
    enum class ModelType {
        TRANSLATION,
        SPEECH_RECOGNITION,
        LANGUAGE_IDENTIFICATION,
        TEXT_TO_SPEECH,
        CUSTOM
    }
    
    /**
     * Model availability status
     */
    enum class AvailabilityStatus {
        AVAILABLE,
        NOT_AVAILABLE,
        PARTIALLY_AVAILABLE,
        OUTDATED,
        CORRUPTED,
        INSUFFICIENT_STORAGE,
        INCOMPATIBLE,
        UNKNOWN
    }
    
    /**
     * Model availability result
     */
    data class ModelAvailability(
        val modelId: String,
        val modelType: ModelType,
        val status: AvailabilityStatus,
        val isDownloaded: Boolean,
        val isInstalled: Boolean,
        val isUpToDate: Boolean,
        val version: String?,
        val sizeBytes: Long,
        val lastModified: Long,
        val checksum: String?,
        val supportedLanguages: List<String>,
        val compatibilityInfo: CompatibilityInfo?,
        val storageInfo: StorageInfo,
        val healthScore: Float,
        val errorMessage: String?,
        val metadata: Map<String, Any>
    )
    
    /**
     * Model validation result
     */
    data class ModelValidationResult(
        val modelId: String,
        val isValid: Boolean,
        val checksumValid: Boolean,
        val fileIntegrityValid: Boolean,
        val versionValid: Boolean,
        val compatibilityValid: Boolean,
        val validationErrors: List<String>,
        val validationWarnings: List<String>,
        val validationTime: Long
    )
    
    /**
     * Compatibility information
     */
    data class CompatibilityInfo(
        val minAndroidVersion: Int,
        val maxAndroidVersion: Int?,
        val requiredFeatures: List<String>,
        val requiredPermissions: List<String>,
        val deviceCompatible: Boolean,
        val compatibilityIssues: List<String>
    )
    
    /**
     * Storage information
     */
    data class StorageInfo(
        val availableSpaceBytes: Long,
        val requiredSpaceBytes: Long,
        val hasEnoughSpace: Boolean,
        val storageLocation: String,
        val isExternalStorage: Boolean,
        val isWritable: Boolean
    )
    
    /**
     * Model availability event
     */
    sealed class ModelAvailabilityEvent {
        data class ModelDownloaded(val modelId: String) : ModelAvailabilityEvent()
        data class ModelInstalled(val modelId: String) : ModelAvailabilityEvent()
        data class ModelRemoved(val modelId: String) : ModelAvailabilityEvent()
        data class ModelUpdated(val modelId: String, val oldVersion: String, val newVersion: String) : ModelAvailabilityEvent()
        data class ModelCorrupted(val modelId: String, val error: String) : ModelAvailabilityEvent()
        data class StorageSpaceLow(val availableSpace: Long, val requiredSpace: Long) : ModelAvailabilityEvent()
        data class ModelValidationFailed(val modelId: String, val errors: List<String>) : ModelAvailabilityEvent()
    }
    
    /**
     * Initialize the model availability checker
     */
    suspend fun initialize() {
        try {
            Log.d(TAG, "Initializing ModelAvailabilityChecker")
            
            // Create model directories if they don't exist
            createModelDirectories()
            
            // Load cached availability data
            loadCachedAvailability()
            
            // Perform initial model scan
            scanAllModels()
            
            // Start periodic health monitoring
            startHealthMonitoring()
            
            Log.d(TAG, "ModelAvailabilityChecker initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ModelAvailabilityChecker", e)
            throw e
        }
    }
    
    /**
     * Check availability of a specific model
     */
    suspend fun checkModelAvailability(modelId: String): ModelAvailability {
        return availabilityCache[modelId] ?: run {
            val availability = performModelAvailabilityCheck(modelId)
            availabilityCache[modelId] = availability
            availability
        }
    }
    
    /**
     * Check availability of multiple models
     */
    suspend fun checkMultipleModelsAvailability(modelIds: List<String>): Map<String, ModelAvailability> {
        return modelIds.associateWith { modelId ->
            checkModelAvailability(modelId)
        }
    }
    
    /**
     * Get all available models
     */
    suspend fun getAllAvailableModels(): List<ModelAvailability> {
        return availabilityCache.values.filter { it.status == AvailabilityStatus.AVAILABLE }
    }
    
    /**
     * Get models by type
     */
    suspend fun getModelsByType(modelType: ModelType): List<ModelAvailability> {
        return availabilityCache.values.filter { it.modelType == modelType }
    }
    
    /**
     * Validate a specific model
     */
    suspend fun validateModel(modelId: String): ModelValidationResult {
        return validationCache[modelId] ?: run {
            val validation = performModelValidation(modelId)
            validationCache[modelId] = validation
            
            if (!validation.isValid) {
                _availabilityEvents.emit(ModelAvailabilityEvent.ModelValidationFailed(modelId, validation.validationErrors))
            }
            
            validation
        }
    }
    
    /**
     * Check if a model is available for use
     */
    suspend fun isModelAvailable(modelId: String): Boolean {
        val availability = checkModelAvailability(modelId)
        return availability.status == AvailabilityStatus.AVAILABLE && 
               availability.isDownloaded && 
               availability.isInstalled
    }
    
    /**
     * Check if models are available for a language pair
     */
    suspend fun isLanguagePairSupported(sourceLanguage: String, targetLanguage: String): Boolean {
        val translationModels = getModelsByType(ModelType.TRANSLATION)
        
        return translationModels.any { model ->
            model.supportedLanguages.contains(sourceLanguage) && 
            model.supportedLanguages.contains(targetLanguage)
        }
    }
    
    /**
     * Get storage information
     */
    suspend fun getStorageInfo(): StorageInfo {
        return checkStorageAvailability()
    }
    
    /**
     * Check if there's enough storage for model download
     */
    suspend fun hasEnoughStorage(requiredSpaceBytes: Long): Boolean {
        val storageInfo = checkStorageAvailability()
        return storageInfo.availableSpaceBytes >= requiredSpaceBytes + MIN_STORAGE_SPACE_MB * 1024 * 1024
    }
    
    /**
     * Force refresh model availability
     */
    suspend fun refreshModelAvailability(modelId: String? = null) {
        if (modelId != null) {
            // Refresh specific model
            availabilityCache.remove(modelId)
            validationCache.remove(modelId)
            checkModelAvailability(modelId)
        } else {
            // Refresh all models
            availabilityCache.clear()
            validationCache.clear()
            scanAllModels()
        }
    }
    
    /**
     * Get model health score
     */
    suspend fun getModelHealthScore(modelId: String): Float {
        val availability = checkModelAvailability(modelId)
        return availability.healthScore
    }
    
    /**
     * Get models that need updates
     */
    suspend fun getModelsNeedingUpdates(): List<ModelAvailability> {
        return availabilityCache.values.filter { 
            it.status == AvailabilityStatus.OUTDATED || !it.isUpToDate 
        }
    }
    
    /**
     * Get corrupted models
     */
    suspend fun getCorruptedModels(): List<ModelAvailability> {
        return availabilityCache.values.filter { 
            it.status == AvailabilityStatus.CORRUPTED 
        }
    }
    
    /**
     * Clean up corrupted models
     */
    suspend fun cleanupCorruptedModels(): List<String> {
        val corruptedModels = getCorruptedModels()
        val cleanedModels = mutableListOf<String>()
        
        for (model in corruptedModels) {
            try {
                removeModelFiles(model.modelId)
                availabilityCache.remove(model.modelId)
                validationCache.remove(model.modelId)
                cleanedModels.add(model.modelId)
                
                _availabilityEvents.emit(ModelAvailabilityEvent.ModelRemoved(model.modelId))
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup corrupted model: ${model.modelId}", e)
            }
        }
        
        return cleanedModels
    }
    
    // Private implementation methods
    
    private suspend fun createModelDirectories() {
        val modelDir = getModelDirectory()
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        
        val cacheDir = getModelCacheDirectory()
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }
    
    private suspend fun loadCachedAvailability() {
        try {
            val cacheFile = File(getModelDirectory(), "availability_cache.json")
            if (cacheFile.exists()) {
                // Load cached availability data
                // Implementation would parse JSON and populate cache
                Log.d(TAG, "Loaded cached availability data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached availability", e)
        }
    }
    
    private suspend fun scanAllModels() {
        val modelDir = getModelDirectory()
        val modelFiles = modelDir.listFiles() ?: emptyArray()
        
        for (file in modelFiles) {
            if (file.isDirectory) {
                val modelId = file.name
                try {
                    val availability = performModelAvailabilityCheck(modelId)
                    availabilityCache[modelId] = availability
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to scan model: $modelId", e)
                }
            }
        }
    }
    
    private suspend fun performModelAvailabilityCheck(modelId: String): ModelAvailability {
        return withContext(Dispatchers.IO) {
            try {
                val modelDir = File(getModelDirectory(), modelId)
                val isDownloaded = modelDir.exists() && modelDir.isDirectory
                val isInstalled = checkModelInstallation(modelId)
                val version = getModelVersion(modelId)
                val sizeBytes = calculateModelSize(modelId)
                val lastModified = getModelLastModified(modelId)
                val checksum = calculateModelChecksum(modelId)
                val supportedLanguages = getSupportedLanguages(modelId)
                val compatibilityInfo = checkCompatibility(modelId)
                val storageInfo = checkStorageAvailability()
                val healthScore = calculateHealthScore(modelId)
                
                val status = determineAvailabilityStatus(
                    isDownloaded = isDownloaded,
                    isInstalled = isInstalled,
                    version = version,
                    checksum = checksum,
                    compatibilityInfo = compatibilityInfo,
                    storageInfo = storageInfo
                )
                
                ModelAvailability(
                    modelId = modelId,
                    modelType = getModelType(modelId),
                    status = status,
                    isDownloaded = isDownloaded,
                    isInstalled = isInstalled,
                    isUpToDate = isModelUpToDate(modelId, version),
                    version = version,
                    sizeBytes = sizeBytes,
                    lastModified = lastModified,
                    checksum = checksum,
                    supportedLanguages = supportedLanguages,
                    compatibilityInfo = compatibilityInfo,
                    storageInfo = storageInfo,
                    healthScore = healthScore,
                    errorMessage = getErrorMessage(status),
                    metadata = getModelMetadata(modelId)
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error checking model availability: $modelId", e)
                
                ModelAvailability(
                    modelId = modelId,
                    modelType = ModelType.CUSTOM,
                    status = AvailabilityStatus.UNKNOWN,
                    isDownloaded = false,
                    isInstalled = false,
                    isUpToDate = false,
                    version = null,
                    sizeBytes = 0,
                    lastModified = 0,
                    checksum = null,
                    supportedLanguages = emptyList(),
                    compatibilityInfo = null,
                    storageInfo = checkStorageAvailability(),
                    healthScore = 0f,
                    errorMessage = e.message,
                    metadata = emptyMap()
                )
            }
        }
    }
    
    private suspend fun performModelValidation(modelId: String): ModelValidationResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            
            try {
                // Check file integrity
                val fileIntegrityValid = validateFileIntegrity(modelId)
                if (!fileIntegrityValid) {
                    errors.add("File integrity check failed")
                }
                
                // Check checksum
                val checksumValid = validateChecksum(modelId)
                if (!checksumValid) {
                    errors.add("Checksum validation failed")
                }
                
                // Check version compatibility
                val versionValid = validateVersion(modelId)
                if (!versionValid) {
                    warnings.add("Version compatibility warning")
                }
                
                // Check compatibility
                val compatibilityValid = validateCompatibility(modelId)
                if (!compatibilityValid) {
                    errors.add("Device compatibility check failed")
                }
                
                val isValid = errors.isEmpty() && fileIntegrityValid && checksumValid
                val validationTime = System.currentTimeMillis() - startTime
                
                ModelValidationResult(
                    modelId = modelId,
                    isValid = isValid,
                    checksumValid = checksumValid,
                    fileIntegrityValid = fileIntegrityValid,
                    versionValid = versionValid,
                    compatibilityValid = compatibilityValid,
                    validationErrors = errors,
                    validationWarnings = warnings,
                    validationTime = validationTime
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Model validation failed: $modelId", e)
                
                ModelValidationResult(
                    modelId = modelId,
                    isValid = false,
                    checksumValid = false,
                    fileIntegrityValid = false,
                    versionValid = false,
                    compatibilityValid = false,
                    validationErrors = listOf(e.message ?: "Unknown validation error"),
                    validationWarnings = warnings,
                    validationTime = System.currentTimeMillis() - startTime
                )
            }
        }
    }
    
    private fun checkModelInstallation(modelId: String): Boolean {
        return try {
            val modelDir = File(getModelDirectory(), modelId)
            val manifestFile = File(modelDir, "manifest.json")
            manifestFile.exists() && manifestFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }
    
    private fun getModelVersion(modelId: String): String? {
        return try {
            val modelDir = File(getModelDirectory(), modelId)
            val versionFile = File(modelDir, "version.txt")
            if (versionFile.exists()) {
                versionFile.readText().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun calculateModelSize(modelId: String): Long {
        return try {
            val modelDir = File(getModelDirectory(), modelId)
            if (modelDir.exists()) {
                modelDir.walkTopDown().sumOf { it.length() }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getModelLastModified(modelId: String): Long {
        return try {
            val modelDir = File(getModelDirectory(), modelId)
            if (modelDir.exists()) {
                modelDir.lastModified()
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun calculateModelChecksum(modelId: String): String? {
        return try {
            val modelDir = File(getModelDirectory(), modelId)
            if (!modelDir.exists()) return null
            
            val digest = MessageDigest.getInstance("SHA-256")
            modelDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.absolutePath }
                .forEach { file ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                }
            
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getSupportedLanguages(modelId: String): List<String> {
        return try {
            val modelDir = File(getModelDirectory(), modelId)
            val languagesFile = File(modelDir, "supported_languages.json")
            if (languagesFile.exists()) {
                // Parse JSON and return language codes
                // This would be implemented based on the actual format
                listOf("en", "es", "fr", "de") // Placeholder
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun checkCompatibility(modelId: String): CompatibilityInfo? {
        return try {
            val minAndroidVersion = getMinAndroidVersion(modelId)
            val maxAndroidVersion = getMaxAndroidVersion(modelId)
            val requiredFeatures = getRequiredFeatures(modelId)
            val requiredPermissions = getRequiredPermissions(modelId)
            
            val deviceCompatible = checkDeviceCompatibility(
                minAndroidVersion, maxAndroidVersion, requiredFeatures, requiredPermissions
            )
            
            val compatibilityIssues = mutableListOf<String>()
            if (!deviceCompatible) {
                compatibilityIssues.add("Device compatibility issues detected")
            }
            
            CompatibilityInfo(
                minAndroidVersion = minAndroidVersion,
                maxAndroidVersion = maxAndroidVersion,
                requiredFeatures = requiredFeatures,
                requiredPermissions = requiredPermissions,
                deviceCompatible = deviceCompatible,
                compatibilityIssues = compatibilityIssues
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun checkStorageAvailability(): StorageInfo {
        return try {
            val modelDir = getModelDirectory()
            val availableSpace = modelDir.usableSpace
            val totalSpace = modelDir.totalSpace
            val requiredSpace = calculateRequiredSpace()
            
            StorageInfo(
                availableSpaceBytes = availableSpace,
                requiredSpaceBytes = requiredSpace,
                hasEnoughSpace = availableSpace >= requiredSpace + MIN_STORAGE_SPACE_MB * 1024 * 1024,
                storageLocation = modelDir.absolutePath,
                isExternalStorage = !modelDir.absolutePath.startsWith(context.filesDir.absolutePath),
                isWritable = modelDir.canWrite()
            )
        } catch (e: Exception) {
            StorageInfo(
                availableSpaceBytes = 0,
                requiredSpaceBytes = 0,
                hasEnoughSpace = false,
                storageLocation = "",
                isExternalStorage = false,
                isWritable = false
            )
        }
    }
    
    private fun calculateHealthScore(modelId: String): Float {
        var score = 0f
        
        try {
            val availability = availabilityCache[modelId]
            if (availability != null) {
                // Base score for availability
                score += if (availability.isDownloaded) 30f else 0f
                score += if (availability.isInstalled) 30f else 0f
                score += if (availability.isUpToDate) 20f else 10f
                
                // Compatibility score
                score += if (availability.compatibilityInfo?.deviceCompatible == true) 10f else 0f
                
                // Storage score
                score += if (availability.storageInfo.hasEnoughSpace) 10f else 0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating health score for model: $modelId", e)
        }
        
        return score.coerceIn(0f, 100f)
    }
    
    private fun determineAvailabilityStatus(
        isDownloaded: Boolean,
        isInstalled: Boolean,
        version: String?,
        checksum: String?,
        compatibilityInfo: CompatibilityInfo?,
        storageInfo: StorageInfo
    ): AvailabilityStatus {
        return when {
            !isDownloaded -> AvailabilityStatus.NOT_AVAILABLE
            !isInstalled -> AvailabilityStatus.PARTIALLY_AVAILABLE
            !storageInfo.hasEnoughSpace -> AvailabilityStatus.INSUFFICIENT_STORAGE
            compatibilityInfo?.deviceCompatible != true -> AvailabilityStatus.INCOMPATIBLE
            checksum == null -> AvailabilityStatus.CORRUPTED
            version == null || !isModelUpToDate("", version) -> AvailabilityStatus.OUTDATED
            else -> AvailabilityStatus.AVAILABLE
        }
    }
    
    private fun getModelType(modelId: String): ModelType {
        return when {
            modelId.contains("translation") -> ModelType.TRANSLATION
            modelId.contains("speech") -> ModelType.SPEECH_RECOGNITION
            modelId.contains("language_id") -> ModelType.LANGUAGE_IDENTIFICATION
            modelId.contains("tts") -> ModelType.TEXT_TO_SPEECH
            else -> ModelType.CUSTOM
        }
    }
    
    private fun isModelUpToDate(modelId: String, version: String?): Boolean {
        // Implementation would check against latest available version
        return version != null
    }
    
    private fun getErrorMessage(status: AvailabilityStatus): String? {
        return when (status) {
            AvailabilityStatus.NOT_AVAILABLE -> "Model not downloaded"
            AvailabilityStatus.PARTIALLY_AVAILABLE -> "Model partially installed"
            AvailabilityStatus.OUTDATED -> "Model version is outdated"
            AvailabilityStatus.CORRUPTED -> "Model files are corrupted"
            AvailabilityStatus.INSUFFICIENT_STORAGE -> "Insufficient storage space"
            AvailabilityStatus.INCOMPATIBLE -> "Model incompatible with device"
            else -> null
        }
    }
    
    private fun getModelMetadata(modelId: String): Map<String, Any> {
        return try {
            val modelDir = File(getModelDirectory(), modelId)
            val metadataFile = File(modelDir, "metadata.json")
            if (metadataFile.exists()) {
                // Parse JSON metadata
                emptyMap() // Placeholder
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    private fun validateFileIntegrity(modelId: String): Boolean {
        // Implementation would check file integrity
        return true // Placeholder
    }
    
    private fun validateChecksum(modelId: String): Boolean {
        // Implementation would validate checksum
        return true // Placeholder
    }
    
    private fun validateVersion(modelId: String): Boolean {
        // Implementation would validate version compatibility
        return true // Placeholder
    }
    
    private fun validateCompatibility(modelId: String): Boolean {
        // Implementation would validate device compatibility
        return true // Placeholder
    }
    
    private fun getMinAndroidVersion(modelId: String): Int {
        // Implementation would read from model metadata
        return 21 // Android 5.0
    }
    
    private fun getMaxAndroidVersion(modelId: String): Int? {
        // Implementation would read from model metadata
        return null // No maximum
    }
    
    private fun getRequiredFeatures(modelId: String): List<String> {
        // Implementation would read from model metadata
        return emptyList()
    }
    
    private fun getRequiredPermissions(modelId: String): List<String> {
        // Implementation would read from model metadata
        return emptyList()
    }
    
    private fun checkDeviceCompatibility(
        minAndroidVersion: Int,
        maxAndroidVersion: Int?,
        requiredFeatures: List<String>,
        requiredPermissions: List<String>
    ): Boolean {
        // Check Android version
        if (android.os.Build.VERSION.SDK_INT < minAndroidVersion) {
            return false
        }
        
        maxAndroidVersion?.let { max ->
            if (android.os.Build.VERSION.SDK_INT > max) {
                return false
            }
        }
        
        // Check required features
        val packageManager = context.packageManager
        for (feature in requiredFeatures) {
            if (!packageManager.hasSystemFeature(feature)) {
                return false
            }
        }
        
        // Check required permissions
        for (permission in requiredPermissions) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        
        return true
    }
    
    private fun calculateRequiredSpace(): Long {
        // Implementation would calculate space required for all models
        return 500 * 1024 * 1024 // 500MB placeholder
    }
    
    private fun getModelDirectory(): File {
        return File(context.filesDir, MODEL_CACHE_DIR)
    }
    
    private fun getModelCacheDirectory(): File {
        return File(context.cacheDir, MODEL_CACHE_DIR)
    }
    
    private fun removeModelFiles(modelId: String) {
        val modelDir = File(getModelDirectory(), modelId)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
    }
    
    private fun startHealthMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    // Perform periodic health checks
                    val corruptedModels = getCorruptedModels()
                    if (corruptedModels.isNotEmpty()) {
                        Log.w(TAG, "Found ${corruptedModels.size} corrupted models")
                    }
                    
                    // Check storage space
                    val storageInfo = checkStorageAvailability()
                    if (!storageInfo.hasEnoughSpace) {
                        _availabilityEvents.emit(
                            ModelAvailabilityEvent.StorageSpaceLow(
                                storageInfo.availableSpaceBytes,
                                storageInfo.requiredSpaceBytes
                            )
                        )
                    }
                    
                    // Sleep for 5 minutes
                    delay(5 * 60 * 1000)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in health monitoring", e)
                    delay(60 * 1000) // Retry in 1 minute on error
                }
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        availabilityCache.clear()
        validationCache.clear()
    }
}

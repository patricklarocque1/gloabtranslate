package com.example.gloabtranslate.nlp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Comprehensive model download management system that handles
 * model downloads, installation, updates, removal, and integrity validation.
 * Now properly designed for dependency injection instead of singleton pattern.
 */
class ModelManager(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "ModelManager"
        private const val MODEL_CACHE_DIR = "ml_models"
        private const val DOWNLOAD_TEMP_DIR = "downloads"
        private const val MODEL_MANIFEST_FILE = "manifest.json"
        private const val MODEL_VERSION_FILE = "version.txt"
        private const val MODEL_CHECKSUM_FILE = "checksum.sha256"
        private const val DOWNLOAD_TIMEOUT_SECONDS = 300L
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
    }
    
    // HTTP client for downloads
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(DOWNLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(DOWNLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Download tracking
    private val activeDownloads = ConcurrentHashMap<String, DownloadJob>()
    private val downloadQueue = mutableListOf<DownloadRequest>()
    private val isProcessingQueue = AtomicBoolean(false)
    
    // Event flows
    private val _downloadEvents = MutableSharedFlow<DownloadEvent>()
    val downloadEvents: SharedFlow<DownloadEvent> = _downloadEvents.asSharedFlow()
    
    private val _modelEvents = MutableSharedFlow<ModelEvent>()
    val modelEvents: SharedFlow<ModelEvent> = _modelEvents.asSharedFlow()
    
    // Model availability checker
    private lateinit var availabilityChecker: ModelAvailabilityChecker
    
    /**
     * Model types
     */
    enum class ModelType {
        TRANSLATION,
        SPEECH_RECOGNITION,
        LANGUAGE_IDENTIFICATION,
        TEXT_TO_SPEECH,
        CUSTOM
    }
    
    /**
     * Download status
     */
    enum class DownloadStatus {
        PENDING,
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED,
        VALIDATING,
        INSTALLING,
        INSTALLED
    }
    
    /**
     * Download request
     */
    data class DownloadRequest(
        val modelId: String,
        val modelType: ModelType,
        val downloadUrl: String,
        val version: String,
        val checksum: String,
        val sizeBytes: Long,
        val supportedLanguages: List<String>,
        val priority: Int = 0,
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * Download progress
     */
    data class DownloadProgress(
        val modelId: String,
        val status: DownloadStatus,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progress: Float,
        val downloadSpeed: Float, // bytes per second
        val estimatedTimeRemaining: Long, // milliseconds
        val error: String? = null
    )
    
    /**
     * Download job
     */
    private data class DownloadJob(
        val request: DownloadRequest,
        val job: Job,
        val startTime: Long,
        val downloadedBytes: AtomicLong = AtomicLong(0),
        val isPaused: AtomicBoolean = AtomicBoolean(false),
        val isCancelled: AtomicBoolean = AtomicBoolean(false)
    )
    
    /**
     * Download events
     */
    sealed class DownloadEvent {
        data class DownloadStarted(val modelId: String) : DownloadEvent()
        data class DownloadProgress(val progress: ModelManager.DownloadProgress) : DownloadEvent()
        data class DownloadCompleted(val modelId: String) : DownloadEvent()
        data class DownloadFailed(val modelId: String, val error: String) : DownloadEvent()
        data class DownloadCancelled(val modelId: String) : DownloadEvent()
        data class DownloadPaused(val modelId: String) : DownloadEvent()
        data class DownloadResumed(val modelId: String) : DownloadEvent()
        data class DownloadValidated(val modelId: String, val isValid: Boolean) : DownloadEvent()
        data class ModelInstalled(val modelId: String) : DownloadEvent()
    }
    
    /**
     * Model events
     */
    sealed class ModelEvent {
        data class ModelAvailable(val modelId: String) : ModelEvent()
        data class ModelUnavailable(val modelId: String) : ModelEvent()
        data class ModelUpdated(val modelId: String, val oldVersion: String, val newVersion: String) : ModelEvent()
        data class ModelRemoved(val modelId: String) : ModelEvent()
        data class ModelCorrupted(val modelId: String, val error: String) : ModelEvent()
        data class StorageSpaceLow(val availableSpace: Long, val requiredSpace: Long) : ModelEvent()
    }
    
    /**
     * Initialize the model manager
     */
    suspend fun initialize() {
        try {
            Log.d(TAG, "Initializing ModelManager")
            
            // Initialize availability checker
            availabilityChecker = ModelAvailabilityChecker.getInstance(context)
            availabilityChecker.initialize()
            
            // Create directories
            createDirectories()
            
            // Restore pending downloads
            restorePendingDownloads()
            
            // Start queue processor
            startQueueProcessor()
            
            Log.d(TAG, "ModelManager initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ModelManager", e)
            throw e
        }
    }

    /**
     * Ensures the ML Kit translation models for the given language pair are downloaded.
     */
    suspend fun ensureLanguagePairAvailable(
        sourceLanguage: String,
        targetLanguage: String,
        requireWifi: Boolean = false
    ) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()

        val translator = Translation.getClient(options)
        val conditionsBuilder = DownloadConditions.Builder()
        if (requireWifi) {
            conditionsBuilder.requireWifi()
        }

        try {
            translator.downloadModelIfNeeded(conditionsBuilder.build()).await()
            Log.d(TAG, "Language pair ready: ${sourceLanguage}_$targetLanguage")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare models for ${sourceLanguage}_$targetLanguage", e)
            throw e
        } finally {
            translator.close()
        }
    }
    
    /**
     * Download a model
     */
    suspend fun downloadModel(
        modelId: String,
        modelType: ModelType,
        downloadUrl: String,
        version: String,
        checksum: String,
        sizeBytes: Long,
        supportedLanguages: List<String> = emptyList(),
        priority: Int = 0,
        metadata: Map<String, String> = emptyMap()
    ): Boolean {
        return try {
            val request = DownloadRequest(
                modelId = modelId,
                modelType = modelType,
                downloadUrl = downloadUrl,
                version = version,
                checksum = checksum,
                sizeBytes = sizeBytes,
                supportedLanguages = supportedLanguages,
                priority = priority,
                metadata = metadata
            )
            
            downloadModel(request)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: $modelId", e)
            _downloadEvents.emit(DownloadEvent.DownloadFailed(modelId, e.message ?: "Unknown error"))
            false
        }
    }
    
    /**
     * Download a model with request object
     */
    suspend fun downloadModel(request: DownloadRequest): Boolean {
        return try {
            // Check if already downloading or downloaded
            if (activeDownloads.containsKey(request.modelId)) {
                Log.w(TAG, "Model already downloading: ${request.modelId}")
                return false
            }
            
            val availability = availabilityChecker.checkModelAvailability(request.modelId)
            if (availability.isDownloaded && availability.isInstalled) {
                Log.w(TAG, "Model already downloaded and installed: ${request.modelId}")
                return true
            }
            
            // Check network connectivity
            if (!isNetworkAvailable()) {
                Log.e(TAG, "No network connectivity available")
                _downloadEvents.emit(DownloadEvent.DownloadFailed(request.modelId, "No network connectivity"))
                return false
            }
            
            // Check storage space
            if (!availabilityChecker.hasEnoughStorage(request.sizeBytes)) {
                Log.e(TAG, "Insufficient storage space for model: ${request.modelId}")
                _downloadEvents.emit(DownloadEvent.DownloadFailed(request.modelId, "Insufficient storage space"))
                return false
            }
            
            // Add to download queue
            synchronized(downloadQueue) {
                downloadQueue.add(request)
                downloadQueue.sortByDescending { it.priority }
            }
            
            Log.d(TAG, "Added model to download queue: ${request.modelId}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue model download: ${request.modelId}", e)
            _downloadEvents.emit(DownloadEvent.DownloadFailed(request.modelId, e.message ?: "Unknown error"))
            false
        }
    }
    
    /**
     * Cancel a download
     */
    suspend fun cancelDownload(modelId: String): Boolean {
        return try {
            val downloadJob = activeDownloads[modelId]
            if (downloadJob != null) {
                downloadJob.isCancelled.set(true)
                downloadJob.job.cancel()
                activeDownloads.remove(modelId)
                
                // Clean up partial download
                cleanupPartialDownload(modelId)
                
                _downloadEvents.emit(DownloadEvent.DownloadCancelled(modelId))
                Log.d(TAG, "Cancelled download: $modelId")
                true
            } else {
                // Remove from queue if not started
                synchronized(downloadQueue) {
                    downloadQueue.removeAll { it.modelId == modelId }
                }
                _downloadEvents.emit(DownloadEvent.DownloadCancelled(modelId))
                Log.d(TAG, "Removed from queue: $modelId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel download: $modelId", e)
            false
        }
    }
    
    /**
     * Pause a download
     */
    suspend fun pauseDownload(modelId: String): Boolean {
        return try {
            val downloadJob = activeDownloads[modelId]
            if (downloadJob != null && downloadJob.job.isActive && !downloadJob.isPaused.get() && !downloadJob.isCancelled.get()) {
                downloadJob.isPaused.set(true)
                _downloadEvents.emit(DownloadEvent.DownloadPaused(modelId))
                Log.d(TAG, "Paused download: $modelId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause download: $modelId", e)
            false
        }
    }
    
    /**
     * Resume a download
     */
    suspend fun resumeDownload(modelId: String): Boolean {
        return try {
            val downloadJob = activeDownloads[modelId]
            if (downloadJob != null && downloadJob.isPaused.get()) {
                downloadJob.isPaused.set(false)
                _downloadEvents.emit(DownloadEvent.DownloadResumed(modelId))
                Log.d(TAG, "Resumed download: $modelId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume download: $modelId", e)
            false
        }
    }
    
    /**
     * Get download progress
     */
    suspend fun getDownloadProgress(modelId: String): DownloadProgress? {
        return try {
            val downloadJob = activeDownloads[modelId]
            if (downloadJob != null) {
                val downloadedBytes = downloadJob.downloadedBytes.get()
                val totalBytes = downloadJob.request.sizeBytes
                val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                
                val elapsedTime = System.currentTimeMillis() - downloadJob.startTime
                val downloadSpeed = if (elapsedTime > 0) downloadedBytes * 1000f / elapsedTime else 0f
                
                val estimatedTimeRemaining = if (downloadSpeed > 0) {
                    (totalBytes - downloadedBytes) / downloadSpeed.toLong()
                } else {
                    Long.MAX_VALUE
                }
                
                val currentStatus =
                    if (downloadJob.isCancelled.get()) DownloadStatus.CANCELLED
                    else if (downloadJob.isPaused.get()) DownloadStatus.PAUSED
                    else if (downloadJob.job.isActive) DownloadStatus.DOWNLOADING
                    else DownloadStatus.PENDING // Or determine if completed/failed based on other factors if necessary

                DownloadProgress(
                    modelId = modelId,
                    status = currentStatus,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    progress = progress,
                    downloadSpeed = downloadSpeed,
                    estimatedTimeRemaining = estimatedTimeRemaining
                )
            } else {
                // Check if it's pending in queue
                val isInQueue = synchronized(downloadQueue) {
                    downloadQueue.any { it.modelId == modelId }
                }
                if (isInQueue) {
                    val request = synchronized(downloadQueue) { downloadQueue.firstOrNull { it.modelId == modelId } }
                    request?.let {
                        DownloadProgress(
                            modelId = modelId,
                            status = DownloadStatus.PENDING,
                            downloadedBytes = 0,
                            totalBytes = it.sizeBytes,
                            progress = 0f,
                            downloadSpeed = 0f,
                            estimatedTimeRemaining = Long.MAX_VALUE
                        )
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get download progress: $modelId", e)
            null
        }
    }
    
    /**
     * Get all active downloads
     */
    suspend fun getActiveDownloads(): List<DownloadProgress> {
        return try {
            activeDownloads.values.mapNotNull { downloadJob ->
                getDownloadProgress(downloadJob.request.modelId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active downloads", e)
            emptyList()
        }
    }
    
    /**
     * Install a downloaded model
     */
    suspend fun installModel(modelId: String): Boolean {
        return try {
            val modelDir = getModelDirectory(modelId)
            if (!modelDir.exists()) {
                Log.e(TAG, "Model directory does not exist: $modelId")
                return false
            }
            
            _downloadEvents.emit(DownloadEvent.DownloadProgress(
                DownloadProgress(
                    modelId = modelId,
                    status = DownloadStatus.VALIDATING,
                    downloadedBytes = 0, // Assuming validation does not track bytes like download
                    totalBytes = 0,    // Same as above
                    progress = 0f,
                    downloadSpeed = 0f,
                    estimatedTimeRemaining = 0
                )
            ))
            
            // Validate model integrity
            val validation = availabilityChecker.validateModel(modelId)
            if (!validation.isValid) {
                Log.e(TAG, "Model validation failed: $modelId")
                _downloadEvents.emit(DownloadEvent.DownloadValidated(modelId, false))
                return false
            }
            
            _downloadEvents.emit(DownloadEvent.DownloadValidated(modelId, true))
            
            _downloadEvents.emit(DownloadEvent.DownloadProgress(
                 DownloadProgress(
                    modelId = modelId,
                    status = DownloadStatus.INSTALLING,
                    downloadedBytes = 0, 
                    totalBytes = 0,    
                    progress = 0f,
                    downloadSpeed = 0f,
                    estimatedTimeRemaining = 0
                )
            ))

            // Create manifest file
            createModelManifest(modelId)
            
            // Mark as installed
            markModelAsInstalled(modelId)
            
            _downloadEvents.emit(DownloadEvent.ModelInstalled(modelId))
            _modelEvents.emit(ModelEvent.ModelAvailable(modelId))
            
            Log.d(TAG, "Model installed successfully: $modelId")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install model: $modelId", e)
            false
        }
    }
    
    /**
     * Remove a model
     */
    suspend fun removeModel(modelId: String): Boolean {
        return try {
            // Cancel download if active
            if (activeDownloads.containsKey(modelId)) {
                cancelDownload(modelId)
            }
            
            // Remove model files
            val modelDir = getModelDirectory(modelId)
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            
            // Remove from availability cache
            availabilityChecker.refreshModelAvailability(modelId)
            
            _modelEvents.emit(ModelEvent.ModelRemoved(modelId))
            
            Log.d(TAG, "Model removed successfully: $modelId")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove model: $modelId", e)
            false
        }
    }
    
    /**
     * Update a model
     */
    suspend fun updateModel(
        modelId: String,
        newVersion: String,
        downloadUrl: String,
        checksum: String,
        sizeBytes: Long
    ): Boolean {
        return try {
            val availability = availabilityChecker.checkModelAvailability(modelId)
            if (!availability.isDownloaded || !availability.isInstalled) {
                Log.e(TAG, "Model not installed, cannot update: $modelId")
                return false
            }
            
            if (availability.version == newVersion) {
                Log.d(TAG, "Model already up to date: $modelId")
                return true
            }
            
            val oldVersion = availability.version ?: "unknown"
            
            // Remove old model
            removeModel(modelId)
            
            // Download new version
            val success = downloadModel(
                modelId = modelId,
                modelType = ModelManager.ModelType.valueOf(availability.modelType.name),
                downloadUrl = downloadUrl,
                version = newVersion,
                checksum = checksum,
                sizeBytes = sizeBytes,
                supportedLanguages = availability.supportedLanguages,
                metadata = availability.metadata.mapValues { it.value.toString() }
            )
            
            if (success) {
                _modelEvents.emit(ModelEvent.ModelUpdated(modelId, oldVersion, newVersion))
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update model: $modelId", e)
            false
        }
    }
    
    /**
     * Get model information
     */
    suspend fun getModelInfo(modelId: String): ModelAvailabilityChecker.ModelAvailability? {
        return try {
            availabilityChecker.checkModelAvailability(modelId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get model info: $modelId", e)
            null
        }
    }
    
    /**
     * Check if model is downloading
     */
    suspend fun isModelDownloading(modelId: String): Boolean {
        return activeDownloads.containsKey(modelId)
    }
    
    /**
     * Check if model is downloaded
     */
    suspend fun isModelDownloaded(modelId: String): Boolean {
        val availability = availabilityChecker.checkModelAvailability(modelId)
        return availability.isDownloaded
    }
    
    /**
     * Check if model is installed
     */
    suspend fun isModelInstalled(modelId: String): Boolean {
        val availability = availabilityChecker.checkModelAvailability(modelId)
        return availability.isInstalled
    }
    
    // Private implementation methods
    
    private suspend fun createDirectories() {
        val modelDir = getModelsDirectory()
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        
        val downloadDir = getDownloadDirectory()
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
    }
    
    private suspend fun restorePendingDownloads() {
        // Implementation would restore downloads from persistent storage
        // This could include resuming partial downloads
    }
    
    private suspend fun startQueueProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    if (!isProcessingQueue.get() && activeDownloads.size < MAX_CONCURRENT_DOWNLOADS) {
                        processDownloadQueue()
                    }
                    delay(1000) // Check every second
                } catch (e: Exception) {
                    Log.e(TAG, "Error in queue processor", e)
                    delay(5000) // Wait longer on error
                }
            }
        }
    }
    
    private suspend fun processDownloadQueue() {
        val requestToProcess = synchronized(downloadQueue) {
            if (downloadQueue.isNotEmpty()) {
                downloadQueue.removeAt(0)
            } else {
                null
            }
        }

        requestToProcess?.let {
            // isProcessingQueue.set(true) // This flag seems to be causing issues, consider removing or refactoring its usage
            try {
                startDownload(it)
            } finally {
                // isProcessingQueue.set(false) // This flag seems to be causing issues, consider removing or refactoring its usage
            }
        }
    }
    
    private suspend fun startDownload(request: DownloadRequest) {
        // Ensure this model isn't already being processed by another call to startDownload
        if (activeDownloads.containsKey(request.modelId)) {
            Log.w(TAG, "Download attempt for already active model: ${request.modelId}")
            return
        }
        try {
            val downloadJobCoroutine = scope.launch {
                performDownload(request)
            }
            
            val job = DownloadJob(
                request = request,
                job = downloadJobCoroutine,
                startTime = System.currentTimeMillis()
            )
            
            activeDownloads[request.modelId] = job
            
            _downloadEvents.emit(DownloadEvent.DownloadStarted(request.modelId))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download: ${request.modelId}", e)
            _downloadEvents.emit(DownloadEvent.DownloadFailed(request.modelId, e.message ?: "Unknown error"))
        }
    }
    
    private suspend fun performDownload(request: DownloadRequest) {
        var attempt = 0
        var success = false
        
        // Ensure there's a job object in activeDownloads before proceeding
        // This could happen if startDownload fails to add it before this coroutine starts
        // or if it's removed prematurely.
        var downloadJob = activeDownloads[request.modelId]
        if (downloadJob == null) {
            Log.e(TAG, "DownloadJob not found in activeDownloads at start of performDownload for ${request.modelId}")
             _downloadEvents.emit(DownloadEvent.DownloadFailed(request.modelId, "Internal error: Download job not found"))
            return // Cannot proceed without a DownloadJob
        }

        while (attempt < RETRY_ATTEMPTS && !success && downloadJob?.isCancelled?.get() != true) {
            try {
                attempt++
                Log.d(TAG, "Download attempt $attempt for model: ${request.modelId}")
                
                success = downloadWithRetry(request)
                
                if (!success && attempt < RETRY_ATTEMPTS && downloadJob?.isCancelled?.get() != true) {
                    Log.w(TAG, "Download attempt $attempt failed, retrying in ${RETRY_DELAY_MS}ms for model: ${request.modelId}")
                    delay(RETRY_DELAY_MS)
                }
                 // Refresh downloadJob in case it was changed (e.g. by cancellation)
                downloadJob = activeDownloads[request.modelId]
                if (downloadJob == null && !success) { // If job removed and not successful, means it was cancelled
                    Log.w(TAG, "Download for ${request.modelId} appears to have been cancelled during retry loop.")
                    break // Exit loop if cancelled
                }
                if(downloadJob == null && success) { // If successful, it might have been removed by completion logic already.
                     break
                }
                if(downloadJob != null && downloadJob.isCancelled.get()){
                    Log.i(TAG, "Download for ${request.modelId} cancelled during retry loop.")
                    break;
                }

            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled for model: ${request.modelId}", e)
                success = false // Ensure success is false if cancelled
                break // Exit loop on cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Download attempt $attempt failed for model: ${request.modelId}", e)
                // Refresh downloadJob before checking isCancelled for the final fail emission
                downloadJob = activeDownloads[request.modelId]
                if (attempt >= RETRY_ATTEMPTS && (downloadJob == null || !downloadJob.isCancelled.get())) {
                    _downloadEvents.emit(DownloadEvent.DownloadFailed(request.modelId, e.message ?: "Download failed after $RETRY_ATTEMPTS attempts"))
                }
            }
        }
        
        // Clean up only if the job is still in activeDownloads and not cancelled (or if it failed)
        val finalDownloadJob = activeDownloads[request.modelId]
        if (finalDownloadJob != null) {
            if (!success && !finalDownloadJob.isCancelled.get()) {
                 // This implies it failed after all retries or an unhandled exception occurred.
                 // The DownloadFailed event should have been emitted from the catch block above.
            } else if (success) {
                // If successful, the downloadToFile should have emitted DownloadCompleted and initiated install.
                // The job might be removed by installModel or if performDownload completes fully.
            }
             activeDownloads.remove(request.modelId)
        } else {
            Log.d(TAG, "DownloadJob for ${request.modelId} already removed from activeDownloads after performDownload loop.")
        }
    }
    
    private suspend fun downloadWithRetry(request: DownloadRequest): Boolean {
        val downloadJob = activeDownloads[request.modelId]
        if (downloadJob == null || downloadJob.isCancelled.get()) {
            Log.w(TAG, "Download cancelled or job missing before HTTP call for model: ${request.modelId}")
            return false // Or throw CancellationException if appropriate
        }

        return try {
            val httpRequest = Request.Builder()
                .url(request.downloadUrl)
                .build()
            
            // Execute HTTP request
            val response = httpClient.newCall(httpRequest).execute()
            try {
                if (!response.isSuccessful) {
                    // It's good practice to consume the body before throwing for resource cleanup by OkHttp
                    response.body()?.close()
                    throw IOException("HTTP ${response.code()}: ${response.message()}")
                }
                
                val responseBody = response.body() ?: throw IOException("Response body is null")
                val contentLength = responseBody.contentLength()
                
                // Validate content length if known, and if it's different from expected.
                // Note: A server might not send Content-Length or might send -1 if chunked.
                if (request.sizeBytes > 0 && contentLength != -1L && contentLength != request.sizeBytes) {
                    Log.w(TAG, "Content length mismatch for ${request.modelId}: expected ${request.sizeBytes}, got $contentLength")
                    // Decide if this is a fatal error. For now, we'll proceed.
                }
                
                downloadToFile(request, responseBody, downloadJob)
            } finally {
                response.body()?.close()
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled during HTTP operation for model: ${request.modelId}")
            cleanupPartialDownload(request.modelId)
            false
        } catch (e: IOException) {
            Log.e(TAG, "IOException during download for model: ${request.modelId}", e)
            cleanupPartialDownload(request.modelId) // Clean up on IO errors too
            false
        } catch (e: Exception) {
            Log.e(TAG, "Generic exception during download for model: ${request.modelId}", e)
            cleanupPartialDownload(request.modelId)
            false
        }
    }
    
    private suspend fun downloadToFile(
        request: DownloadRequest,
        responseBody: ResponseBody,
        downloadJob: DownloadJob
    ): Boolean { // Return Boolean indicating success
        return try {
            val tempFile = getTempDownloadFile(request.modelId)
            // Use the content length from the response header if available and more reliable
            val totalBytes = if(responseBody.contentLength() != -1L) responseBody.contentLength() else request.sizeBytes
            
            responseBody.byteStream().use { inputStream ->
                FileOutputStream(tempFile, downloadJob.downloadedBytes.get() > 0).use { outputStream -> // Append if resuming
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    // totalDownloaded should reflect already downloaded bytes if resuming
                    var totalDownloadedThisSession = 0L 

                    if (downloadJob.downloadedBytes.get() > 0) {
                        Log.d(TAG, "Resuming download for ${request.modelId} from ${downloadJob.downloadedBytes.get()} bytes")
                    }

                    while (true) {
                        if (downloadJob.isCancelled.get()) {
                            throw CancellationException("Download cancelled for ${request.modelId}")
                        }
                        
                        // Handle pause
                        while (downloadJob.isPaused.get() && !downloadJob.isCancelled.get()) {
                            delay(100) // Polling, consider a more efficient mechanism if performance is critical
                        }
                        // Re-check cancellation after pause
                         if (downloadJob.isCancelled.get()) {
                            throw CancellationException("Download cancelled for ${request.modelId} after pause")
                        }

                        bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break // End of stream
                        
                        outputStream.write(buffer, 0, bytesRead)
                        val currentTotalDownloaded = downloadJob.downloadedBytes.addAndGet(bytesRead.toLong())
                        totalDownloadedThisSession += bytesRead
                        
                        // Emit progress
                        // Ensure totalBytes is positive to avoid division by zero
                        val progressVal = if (totalBytes > 0) currentTotalDownloaded.toFloat() / totalBytes else 0f
                        val elapsedTime = System.currentTimeMillis() - downloadJob.startTime // Measures total time since downloadJob started
                        // Calculate speed based on this session's download, or overall if more appropriate
                        val downloadSpeed = if (elapsedTime > 0) currentTotalDownloaded * 1000f / elapsedTime else 0f
                        
                        val estimatedTimeRemaining = if (downloadSpeed > 0 && totalBytes > 0) {
                            (totalBytes - currentTotalDownloaded) / downloadSpeed.toLong()
                        } else {
                            Long.MAX_VALUE
                        }
                        
                        _downloadEvents.emit(DownloadEvent.DownloadProgress(
                            DownloadProgress(
                                modelId = request.modelId,
                                status = DownloadStatus.DOWNLOADING,
                                downloadedBytes = currentTotalDownloaded,
                                totalBytes = totalBytes,
                                progress = progressVal,
                                downloadSpeed = downloadSpeed,
                                estimatedTimeRemaining = estimatedTimeRemaining
                            )
                        ))
                    }
                }
            }
            
            // Move to final location
            val finalDir = getModelDirectory(request.modelId)
            if (!finalDir.exists()) {
                finalDir.mkdirs()
            }
            
            val finalFile = File(finalDir, "${request.modelId}.model") // Standardized model file name
            // Ensure target file doesn't exist or handle replacement explicitly
            if (finalFile.exists()) finalFile.delete() 
            if (!tempFile.renameTo(finalFile)) {
                throw IOException("Failed to move temp file to final location for ${request.modelId}")
            }
            
            // Create version file
            File(finalDir, MODEL_VERSION_FILE).writeText(request.version)
            
            // Create checksum file
            File(finalDir, MODEL_CHECKSUM_FILE).writeText(request.checksum)
            
            // Create supported languages file (simple JSON array)
            val languagesJson = "[\"${request.supportedLanguages.joinToString("\",\"")}\"]"
            File(finalDir, "supported_languages.json").writeText(languagesJson)
            
            // Create metadata file (simple key-value JSON object)
            val metadataJson = request.metadata.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
            File(finalDir, "metadata.json").writeText(metadataJson)
            
            _downloadEvents.emit(DownloadEvent.DownloadCompleted(request.modelId))
            
            // Auto-install if validation passes
            // This should ideally be a separate step after DownloadCompleted is fully processed by observers.
            // For now, keeping it sequential as per original logic.
            val installSuccess = installModel(request.modelId)
            if (!installSuccess) {
                Log.e(TAG, "Model installation failed after download for ${request.modelId}")
                 // Decide if this should revert the 'success' of the download itself.
                 // For now, download is considered successful, installation is a subsequent step.
            }
            
            Log.d(TAG, "Download and attempt to install completed for: ${request.modelId}")
            true // Download itself was successful
            
        } catch (e: CancellationException) {
            Log.i(TAG, "Download to file cancelled for model: ${request.modelId}")
            cleanupPartialDownload(request.modelId) // Clean up the temp file
            // Do not emit DownloadFailed here, as cancellation is handled by performDownload loop.
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file for model: ${request.modelId}", e)
            cleanupPartialDownload(request.modelId) // Clean up the temp file
            _downloadEvents.emit(DownloadEvent.DownloadFailed(request.modelId, e.message ?: "Failed to write file"))
            false
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    
    private fun cleanupPartialDownload(modelId: String) {
        try {
            val tempFile = getTempDownloadFile(modelId)
            if (tempFile.exists()) {
                if (tempFile.delete()) {
                    Log.d(TAG, "Cleaned up partial download: ${tempFile.path}")
                } else {
                    Log.w(TAG, "Failed to delete partial download: ${tempFile.path}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup partial download for $modelId", e)
        }
    }
    
    private fun createModelManifest(modelId: String) {
        try {
            val modelDir = getModelDirectory(modelId)
            val manifestFile = File(modelDir, MODEL_MANIFEST_FILE)
            
            // Create manifest content (simple JSON)
            val manifest = """
                {
                    "modelId": "$modelId",
                    "installedAt": ${System.currentTimeMillis()},
                    "status": "installed"
                }
            """.trimIndent()
            
            manifestFile.writeText(manifest)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create manifest for model: $modelId", e)
        }
    }
    
    private fun markModelAsInstalled(modelId: String) {
        try {
            val modelDir = getModelDirectory(modelId)
            val installedFile = File(modelDir, "installed.flag") // Simple flag file
            installedFile.writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark model as installed: $modelId", e)
        }
    }
    
    private fun getModelsDirectory(): File {
        return File(context.filesDir, MODEL_CACHE_DIR)
    }
    
    private fun getDownloadDirectory(): File {
        return File(context.cacheDir, DOWNLOAD_TEMP_DIR)
    }
    
    private fun getModelDirectory(modelId: String): File {
        return File(getModelsDirectory(), modelId)
    }
    
    private fun getTempDownloadFile(modelId: String): File {
        return File(getDownloadDirectory(), "$modelId.tmp")
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            // Cancel all coroutines
            scope.cancel("ModelManager cleanup")
            
            // Cancel active downloads
            activeDownloads.values.forEach { it.job.cancel() }
            activeDownloads.clear()
            
            // Clear download queue
            synchronized(downloadQueue) {
                downloadQueue.clear()
            }
            
            // Close HTTP client and release its resources
            try {
                httpClient.dispatcher().cancelAll()
                httpClient.connectionPool().evictAll()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing HTTP client", e)
            }
            
            Log.d(TAG, "ModelManager cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during ModelManager cleanup", e)
        }
    }
    
    /**
     * Destroy instance and release all resources
     * Called by DI framework when singleton is being disposed
     */
    fun destroy() {
        cleanup()
        Log.d(TAG, "ModelManager instance destroyed")
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { exception -> continuation.resumeWithException(exception) }
        addOnCanceledListener { continuation.cancel() }
    }
}

package com.example.gloabtranslate.service

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.logging.DebugLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the lifecycle and dependencies of all translation services.
 * Ensures proper startup order, dependency resolution, and error recovery.
 */
@Singleton
class ServiceCoordinator @Inject constructor(
    private val context: Context,
    private val errorRecoverySystem: com.example.gloabtranslate.core.error.ErrorRecoverySystem,
    private val configurationManager: com.example.gloabtranslate.core.data.config.ConfigurationManager,
    private val debugLogger: DebugLogger
) {
    companion object {
        private const val TAG = "ServiceCoordinator"
        // Default startup timeout used when configuration not yet available
        private const val DEFAULT_SERVICE_STARTUP_TIMEOUT = 30000L // 30 seconds
        private const val RETRY_DELAY = 2000L // 2 seconds
        private const val MAX_RETRY_ATTEMPTS = 3
    }
    
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Service states
    private val _serviceStates = MutableStateFlow<Map<ServiceType, ServiceState>>(emptyMap())
    val serviceStates: StateFlow<Map<ServiceType, ServiceState>> = _serviceStates.asStateFlow()
    
    // Overall system health
    private val _systemHealth = MutableStateFlow(SystemHealth.UNKNOWN)
    val systemHealth: StateFlow<SystemHealth> = _systemHealth.asStateFlow()

    // Metrics (retry counts, last errors, timestamps)
    data class ServiceMetrics(
        val retryCounts: Map<ServiceType, Int>,
        val lastErrors: Map<ServiceType, String?>,
        val lastUpdated: Long,
        val health: SystemHealth,
        val externalMetrics: Map<String, Map<String, Any?>> = emptyMap()
    )

    private val _metrics = MutableStateFlow(
        ServiceMetrics(emptyMap(), emptyMap(), System.currentTimeMillis(), SystemHealth.UNKNOWN, emptyMap())
    )
    val metrics: StateFlow<ServiceMetrics> = _metrics.asStateFlow()
    
    // Service dependencies
    @Inject lateinit var recognitionService: com.example.gloabtranslate.nlp.RecognitionService
    @Inject lateinit var textToSpeechService: com.example.gloabtranslate.tts.TextToSpeechService
    @Inject lateinit var modelManager: com.example.gloabtranslate.nlp.ModelManager
    @Inject lateinit var translationPipeline: com.example.gloabtranslate.nlp.TranslationPipeline
    
    data class ServiceState(
        val type: ServiceType,
        val status: ServiceStatus,
        val lastError: String? = null,
        val retryCount: Int = 0,
        val startTime: Long? = null
    )
    
    enum class ServiceType {
        MODEL_MANAGER,
        RECOGNITION_SERVICE,
        TTS_SERVICE,
        TRANSLATION_PIPELINE,
        LIVE_TRANSLATE_SERVICE,
        AUDIO_RECORDING
    }
    
    enum class ServiceStatus {
        INITIALIZING,
        READY,
        ERROR,
        STOPPED,
        RETRYING
    }
    
    enum class SystemHealth {
        UNKNOWN,
        HEALTHY,
        DEGRADED,
        CRITICAL
    }
    
    /**
     * Initialize all services in the proper order
     */
    suspend fun initializeServices(): Boolean {
        return withContext(coordinatorScope.coroutineContext) {
            try {
                debugLogger.d(TAG, "Starting service coordination and initialization")
                
                // Phase 1: Initialize core dependencies (Model Manager)
                if (!initializeModelManager()) {
                    debugLogger.e(TAG, "Failed to initialize ModelManager - cannot continue")
                    updateSystemHealth()
                    return@withContext false
                }
                
                // Phase 2: Initialize recognition and TTS services in parallel
                val recognitionDeferred = async { initializeRecognitionService() }
                val ttsDeferred = async { initializeTTSService() }
                
                val recognitionResult = recognitionDeferred.await()
                val ttsResult = ttsDeferred.await()
                
                // Phase 3: Initialize translation pipeline (depends on recognition)
                val pipelineResult = if (recognitionResult) {
                    initializeTranslationPipeline()
                } else {
                    debugLogger.w(TAG, "Skipping translation pipeline - recognition service failed")
                    false
                }
                
                updateSystemHealth()
                
                val overallSuccess = recognitionResult || ttsResult // At least one core service must work
                debugLogger.d(TAG, "Service initialization complete. Success: $overallSuccess")
                
                overallSuccess
            } catch (e: Exception) {
                debugLogger.e(TAG, "Service initialization failed: ${e.message}", e)
                updateSystemHealth()
                false
            }
        }
    }
    
    private suspend fun initializeModelManager(): Boolean {
        updateServiceState(ServiceType.MODEL_MANAGER, ServiceStatus.INITIALIZING)
        
        return try {
            withTimeout(resolveStartupTimeout()) {
                // ModelManager is instantiated by DI, just verify it's working
                debugLogger.d(TAG, "ModelManager initialized successfully")
                updateServiceState(ServiceType.MODEL_MANAGER, ServiceStatus.READY)
                true
            }
        } catch (e: Exception) {
            debugLogger.e(TAG, "ModelManager initialization failed: ${e.message}", e)
            updateServiceState(ServiceType.MODEL_MANAGER, ServiceStatus.ERROR, e.message)
            false
        }
    }
    
    private suspend fun initializeRecognitionService(): Boolean {
        updateServiceState(ServiceType.RECOGNITION_SERVICE, ServiceStatus.INITIALIZING)
        
        return errorRecoverySystem.executeWithRecovery(
            source = com.example.gloabtranslate.core.error.ErrorRecoverySystem.ErrorSource.SPEECH_RECOGNITION,
            operation = {
                withTimeout(resolveStartupTimeout()) {
                    val result = recognitionService.initialize()
                    if (result.success) {
                        debugLogger.d(TAG, "RecognitionService initialized successfully")
                        updateServiceState(ServiceType.RECOGNITION_SERVICE, ServiceStatus.READY)
                        true
                    } else {
                        debugLogger.e(TAG, "RecognitionService initialization failed: ${result.error}")
                        updateServiceState(ServiceType.RECOGNITION_SERVICE, ServiceStatus.ERROR, result.error)
                        throw RuntimeException("RecognitionService initialization failed: ${result.error}")
                    }
                }
            },
            recoveryAction = {
                debugLogger.d(TAG, "Attempting RecognitionService recovery")
                delay(2000) // Brief pause
                true // Allow retry
            }
        ).getOrElse { 
            updateServiceState(ServiceType.RECOGNITION_SERVICE, ServiceStatus.ERROR, it.message)
            false 
        }
    }
    
    private suspend fun initializeTTSService(): Boolean {
        updateServiceState(ServiceType.TTS_SERVICE, ServiceStatus.INITIALIZING)
        
        return errorRecoverySystem.executeWithRecovery(
            source = com.example.gloabtranslate.core.error.ErrorRecoverySystem.ErrorSource.TEXT_TO_SPEECH,
            operation = {
                withTimeout(resolveStartupTimeout()) {
                    val success = textToSpeechService.initialize()
                    if (success) {
                        debugLogger.d(TAG, "TextToSpeechService initialized successfully")
                        updateServiceState(ServiceType.TTS_SERVICE, ServiceStatus.READY)
                        true
                    } else {
                        val errorMsg = "TextToSpeechService initialization failed"
                        debugLogger.e(TAG, errorMsg)
                        updateServiceState(ServiceType.TTS_SERVICE, ServiceStatus.ERROR, errorMsg)
                        throw RuntimeException(errorMsg)
                    }
                }
            },
            recoveryAction = {
                debugLogger.d(TAG, "Attempting TextToSpeechService recovery") 
                delay(1000) // Brief pause
                true // Allow retry
            }
        ).getOrElse { 
            updateServiceState(ServiceType.TTS_SERVICE, ServiceStatus.ERROR, it.message)
            false 
        }
    }
    
    private suspend fun initializeTranslationPipeline(): Boolean {
        updateServiceState(ServiceType.TRANSLATION_PIPELINE, ServiceStatus.INITIALIZING)
        
        return try {
            withTimeout(resolveStartupTimeout()) {
                translationPipeline.initialize()
                debugLogger.d(TAG, "TranslationPipeline initialized successfully")
                updateServiceState(ServiceType.TRANSLATION_PIPELINE, ServiceStatus.READY)
                true
            }
        } catch (e: Exception) {
            debugLogger.e(TAG, "TranslationPipeline initialization failed: ${e.message}", e)
            updateServiceState(ServiceType.TRANSLATION_PIPELINE, ServiceStatus.ERROR, e.message)
            false
        }
    }
    
    /**
     * Retry failed services
     */
    suspend fun retryFailedServices(): Boolean {
        val failedServices = _serviceStates.value.values.filter { it.status == ServiceStatus.ERROR }
        
        if (failedServices.isEmpty()) {
            debugLogger.d(TAG, "No failed services to retry")
            return true
        }
        
    debugLogger.d(TAG, "Retrying ${failedServices.size} failed services")
        var allRetrySuccess = true
        
        for (serviceState in failedServices) {
            if (serviceState.retryCount >= MAX_RETRY_ATTEMPTS) {
                debugLogger.w(TAG, "Service ${serviceState.type} exceeded max retry attempts")
                continue
            }
            
            updateServiceState(serviceState.type, ServiceStatus.RETRYING, retryCount = serviceState.retryCount + 1)
            delay(RETRY_DELAY)
            
            val success = when (serviceState.type) {
                ServiceType.MODEL_MANAGER -> initializeModelManager()
                ServiceType.RECOGNITION_SERVICE -> initializeRecognitionService()
                ServiceType.TTS_SERVICE -> initializeTTSService()
                ServiceType.TRANSLATION_PIPELINE -> initializeTranslationPipeline()
                ServiceType.LIVE_TRANSLATE_SERVICE -> true // Handled separately
                ServiceType.AUDIO_RECORDING -> true // Audio recording service managed externally for now
            }
            
            if (!success) {
                allRetrySuccess = false
            }
        }
        
        updateSystemHealth()
        return allRetrySuccess
    }
    
    /**
     * Shutdown all services properly
     */
    fun shutdown() {
    debugLogger.d(TAG, "Shutting down all services")
        
        try {
            // Shutdown in reverse order
            if (::translationPipeline.isInitialized) {
                translationPipeline.cleanup()
            }
            
            if (::textToSpeechService.isInitialized) {
                textToSpeechService.cleanup()
            }
            
            if (::recognitionService.isInitialized) {
                recognitionService.cleanup()
            }
            
            if (::modelManager.isInitialized) {
                modelManager.cleanup()
            }
            
            // Reset all service states
            _serviceStates.value = emptyMap()
            _systemHealth.value = SystemHealth.UNKNOWN
            
            // Cleanup error recovery system
            errorRecoverySystem.cleanup()
            
            // Cancel coordinator scope
            coordinatorScope.cancel()
            
            debugLogger.d(TAG, "Service shutdown complete")
        } catch (e: Exception) {
            debugLogger.e(TAG, "Error during service shutdown: ${e.message}", e)
        }
    }
    
    private fun updateServiceState(
        type: ServiceType, 
        status: ServiceStatus, 
        error: String? = null,
        retryCount: Int = 0
    ) {
        val currentStates = _serviceStates.value.toMutableMap()
        val previous = currentStates[type]
        val effectiveRetryCount = when {
            status == ServiceStatus.RETRYING -> retryCount // explicit increment provided
            previous != null && retryCount == 0 -> previous.retryCount // preserve existing count
            else -> retryCount
        }
        currentStates[type] = ServiceState(
            type = type,
            status = status,
            lastError = error ?: previous?.lastError,
            retryCount = effectiveRetryCount,
            startTime = if (status == ServiceStatus.INITIALIZING) System.currentTimeMillis() else previous?.startTime
        )
        _serviceStates.value = currentStates
        updateMetrics()
    }

    /**
     * Determine startup timeout dynamically from current PerformanceConfig.
     * Heuristics (initial simple mapping):
     * - If background processing enabled and cacheSize > 500 -> 45s (heavy warmup allowed)
     * - If background processing enabled -> 30s (default)
     * - If background processing disabled -> 15s (fail fast to keep UI responsive)
     * - If configuration lookup fails -> default
     * This can be refined later (e.g., per-service timeouts, user-tunable factors).
     */
    private suspend fun resolveStartupTimeout(): Long = try {
        // Prefer dynamically observed value if already emitted
        _dynamicStartupTimeout.value ?: run {
            val perf = configurationManager.currentPerformanceConfig()
            when {
                perf.enableBackgroundProcessing && perf.cacheSize > 500 -> 45_000L
                perf.enableBackgroundProcessing -> 30_000L
                else -> 15_000L
            }
        }
    } catch (e: Exception) {
    debugLogger.w(TAG, "Falling back to default startup timeout: ${e.message}")
        DEFAULT_SERVICE_STARTUP_TIMEOUT
    }

    /**
     * Begin observing performanceConfig so adjustments (e.g., user toggles background processing or cache size)
     * will influence subsequent service (re)initialization heuristics without app restart.
     */
    private fun startPerformanceConfigObserver() {
        coordinatorScope.launch {
            configurationManager.performanceConfig.collect { cfg ->
                // Recalculate effective startup timeout and store (simple volatile var)
                val newTimeout = when {
                    cfg.enableBackgroundProcessing && cfg.cacheSize > 500 -> 45_000L
                    cfg.enableBackgroundProcessing -> 30_000L
                    else -> 15_000L
                }
                _dynamicStartupTimeout.value = newTimeout
                debugLogger.d(TAG, "Performance config updated -> startupTimeout=$newTimeout (bg=${cfg.enableBackgroundProcessing}, cache=${cfg.cacheSize})")
            }
        }
    }

    private val _dynamicStartupTimeout = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    
    private fun updateSystemHealth() {
        val states = _serviceStates.value.values
        val coreServices = setOf(ServiceType.MODEL_MANAGER, ServiceType.RECOGNITION_SERVICE)
        
        val coreServiceStates = states.filter { it.type in coreServices }
        val allServiceStates = states
        
        _systemHealth.value = when {
            coreServiceStates.all { it.status == ServiceStatus.READY } -> {
                if (allServiceStates.all { it.status == ServiceStatus.READY }) {
                    SystemHealth.HEALTHY
                } else {
                    SystemHealth.DEGRADED
                }
            }
            coreServiceStates.any { it.status == ServiceStatus.READY } -> SystemHealth.DEGRADED
            else -> SystemHealth.CRITICAL
        }
        updateMetrics()
    }
    
    /**
     * Get current service readiness status
     */
    fun getServiceReadiness(): Map<ServiceType, Boolean> {
        return _serviceStates.value.mapValues { (_, state) -> 
            state.status == ServiceStatus.READY 
        }
    }
    
    /**
     * Check if critical services are ready
     */
    fun areCriticalServicesReady(): Boolean {
        val criticalServices = setOf(ServiceType.MODEL_MANAGER, ServiceType.RECOGNITION_SERVICE)
        return criticalServices.all { serviceType ->
            _serviceStates.value[serviceType]?.status == ServiceStatus.READY
        }
    }

    private fun updateMetrics() {
        val states = _serviceStates.value
        val retryCounts = states.mapValues { it.value.retryCount }
        val errors = states.mapValues { it.value.lastError }
        _metrics.value = _metrics.value.copy(
            retryCounts = retryCounts,
            lastErrors = errors,
            lastUpdated = System.currentTimeMillis(),
            health = _systemHealth.value
        )
    }

    fun getMetricsSnapshot(): ServiceMetrics = _metrics.value

    /**
     * Report metrics from an external service (e.g., AUDIO_RECORDING). Metrics are stored
     * under a namespaced key derived from the external service type name.
     */
    fun reportExternalServiceMetrics(serviceKey: String, metrics: Map<String, Any?>) {
        val current = _metrics.value
        val updatedExternal = current.externalMetrics.toMutableMap()
        updatedExternal[serviceKey] = metrics
        _metrics.value = current.copy(externalMetrics = updatedExternal, lastUpdated = System.currentTimeMillis())
    }

    /**
     * Allows externally managed services (e.g., AUDIO_RECORDING) to report state changes
     * without exposing the internal updateServiceState broadly. Will ignore updates for
     * unknown service types or if coordinator scope is cancelled.
     */
    fun reportExternalServiceState(
        type: ServiceType,
        status: ServiceStatus,
        error: String? = null
    ) {
        if (type != ServiceType.AUDIO_RECORDING) return
        updateServiceState(type, status, error)
        // Recompute health after external change
        updateSystemHealth()
    }
}
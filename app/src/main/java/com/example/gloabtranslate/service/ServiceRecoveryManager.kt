package com.example.gloabtranslate.service

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.data.persistence.ServiceStateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference // Added import
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Service recovery manager for handling service failures and implementing recovery mechanisms.
 * Provides automatic service restart, health monitoring, failure detection, and recovery strategies.
 */
class ServiceRecoveryManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ServiceRecoveryManager"
        private const val HEALTH_CHECK_INTERVAL_MS = 15000L // 15 seconds
        private const val RECOVERY_TIMEOUT_MS = 30000L // 30 seconds
        private const val MAX_RECOVERY_ATTEMPTS = 5
        private const val RECOVERY_BACKOFF_MULTIPLIER = 2.0
        private const val BASE_RECOVERY_DELAY_MS = 2000L
        private const val MAX_RECOVERY_DELAY_MS = 60000L // 1 minute
        
        @Volatile
        private var INSTANCE: ServiceRecoveryManager? = null
        
        fun getInstance(context: Context): ServiceRecoveryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServiceRecoveryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val stateManager = ServiceStateManager.getInstance(context)
    private val lifecycleManager = LifecycleManager.getInstance(context)
    
    // Recovery state management
    private val isInitialized = AtomicBoolean(false)
    private val recoveryMutex = Mutex()
    private val recoveryJobs = ConcurrentHashMap<String, Job>()
    private val serviceHealthStatus = ConcurrentHashMap<String, ServiceHealthInfo>()
    private val recoveryHistory = ConcurrentHashMap<String, MutableList<RecoveryAttempt>>()
    private val recoveryListeners = CopyOnWriteArrayList<ServiceRecoveryListener>()
    
    // Statistics
    private val totalRecoveryAttempts = AtomicLong(0)
    private val successfulRecoveries = AtomicLong(0)
    private val failedRecoveries = AtomicLong(0)
    private val healthCheckJob = AtomicReference<Job?>()
    
    // Recovery scope
    private val recoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Service health information
     */
    data class ServiceHealthInfo(
        val serviceName: String,
        val isHealthy: Boolean = true,
        val lastHealthCheck: Long = System.currentTimeMillis(),
        val consecutiveFailures: Int = 0,
        val lastFailureTime: Long = 0L,
        val lastRecoveryTime: Long = 0L,
        val totalRecoveryAttempts: Int = 0,
        val healthScore: Float = 1.0f // 0.0 to 1.0
    )
    
    /**
     * Recovery attempt record
     */
    data class RecoveryAttempt(
        val serviceName: String,
        val attemptNumber: Int,
        val strategy: RecoveryStrategy,
        val startTime: Long = System.currentTimeMillis(),
        val endTime: Long? = null,
        val success: Boolean = false,
        val error: String? = null,
        val duration: Long = 0L
    )
    
    /**
     * Recovery strategy enumeration
     */
    enum class RecoveryStrategy {
        RESTART_SERVICE,          // Restart the service
        REBIND_SERVICE,           // Unbind and rebind service
        RESTART_APP,              // Restart the entire application
        GRACEFUL_SHUTDOWN,        // Gracefully shutdown and restart
        EMERGENCY_RECOVERY,       // Emergency recovery procedures
        MANUAL_INTERVENTION       // Requires manual intervention
    }
    
    /**
     * Recovery escalation level
     */
    enum class RecoveryEscalation {
        NONE,           // No escalation needed
        WARN,           // Warning level - log and monitor
        ERROR,          // Error level - attempt recovery
        CRITICAL,       // Critical level - aggressive recovery
        EMERGENCY       // Emergency level - extreme measures
    }
    
    /**
     * Service failure type
     */
    enum class FailureType {
        SERVICE_CRASH,          // Service crashed
        BINDING_FAILURE,        // Service binding failed
        HEALTH_CHECK_FAILURE,   // Health check failed
        COMMUNICATION_FAILURE,  // Communication with service failed
        TIMEOUT,               // Service operation timed out
        PERMISSION_DENIED,     // Permission denied
        RESOURCE_EXHAUSTION,   // System resource exhaustion
        UNKNOWN                // Unknown failure type
    }
    
    /**
     * Recovery configuration
     */
    data class RecoveryConfig(
        val serviceName: String,
        val enabled: Boolean = true,
        val maxAttempts: Int = MAX_RECOVERY_ATTEMPTS,
        val baseDelayMs: Long = BASE_RECOVERY_DELAY_MS,
        val maxDelayMs: Long = MAX_RECOVERY_DELAY_MS,
        val backoffMultiplier: Double = RECOVERY_BACKOFF_MULTIPLIER,
        val strategies: List<RecoveryStrategy> = listOf(
            RecoveryStrategy.RESTART_SERVICE,
            RecoveryStrategy.REBIND_SERVICE,
            RecoveryStrategy.GRACEFUL_SHUTDOWN
        ),
        val escalationThreshold: Int = 3,
        val healthCheckIntervalMs: Long = HEALTH_CHECK_INTERVAL_MS
    )
    
    /**
     * Recovery statistics
     */
    data class RecoveryStatistics(
        val totalAttempts: Long,
        val successfulRecoveries: Long,
        val failedRecoveries: Long,
        val successRate: Float,
        val averageRecoveryTime: Long,
        val servicesMonitored: Int,
        val unhealthyServices: Int,
        val lastRecoveryTime: Long
    )
    
    /**
     * Service recovery listener interface
     */
    interface ServiceRecoveryListener {
        fun onServiceFailure(serviceName: String, failureType: FailureType, error: String?)
        fun onRecoveryStarted(serviceName: String, strategy: RecoveryStrategy, attemptNumber: Int)
        fun onRecoveryCompleted(serviceName: String, strategy: RecoveryStrategy, success: Boolean, duration: Long)
        fun onRecoveryFailed(serviceName: String, strategy: RecoveryStrategy, error: String?)
        fun onHealthStatusChanged(serviceName: String, isHealthy: Boolean, healthScore: Float)
        fun onEscalationTriggered(serviceName: String, escalation: RecoveryEscalation)
    }
    
    /**
     * Initializes the service recovery manager
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            recoveryMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "ServiceRecoveryManager already initialized")
                    return@withContext true
                }
                
                // Initialize state manager
                stateManager.initialize()
                
                // Start health monitoring
                startHealthMonitoring()
                
                // Load persisted recovery state
                loadPersistedRecoveryState()
                
                Log.d(TAG, "ServiceRecoveryManager initialized successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ServiceRecoveryManager", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Registers a service for recovery monitoring
     */
    fun registerServiceForRecovery(config: RecoveryConfig) {
        try {
            val healthInfo = ServiceHealthInfo(
                serviceName = config.serviceName,
                healthScore = 1.0f
            )
            
            serviceHealthStatus[config.serviceName] = healthInfo
            
            Log.d(TAG, "Service registered for recovery: ${config.serviceName}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service for recovery: ${config.serviceName}", e)
        }
    }
    
    /**
     * Unregisters a service from recovery monitoring
     */
    fun unregisterServiceFromRecovery(serviceName: String) {
        try {
            serviceHealthStatus.remove(serviceName)
            recoveryHistory.remove(serviceName)
            recoveryJobs[serviceName]?.cancel()
            recoveryJobs.remove(serviceName)
            
            Log.d(TAG, "Service unregistered from recovery: $serviceName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister service from recovery: $serviceName", e)
        }
    }
    
    /**
     * Reports a service failure
     */
    suspend fun reportServiceFailure(
        serviceName: String,
        failureType: FailureType,
        error: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            recoveryMutex.withLock {
                val healthInfo = serviceHealthStatus[serviceName]
                if (healthInfo == null) {
                    Log.w(TAG, "Service not registered for recovery: $serviceName")
                    return@withContext
                }
                
                val updatedHealthInfo = healthInfo.copy(
                    isHealthy = false,
                    lastFailureTime = System.currentTimeMillis(),
                    consecutiveFailures = healthInfo.consecutiveFailures + 1,
                    healthScore = calculateHealthScore(healthInfo.consecutiveFailures + 1)
                )
                
                serviceHealthStatus[serviceName] = updatedHealthInfo
                
                // Notify listeners
                recoveryListeners.forEach { listener ->
                    try {
                        listener.onServiceFailure(serviceName, failureType, error)
                        listener.onHealthStatusChanged(serviceName, false, updatedHealthInfo.healthScore)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error notifying recovery listener", e)
                    }
                }
                
                // Determine escalation level
                val escalation = determineEscalationLevel(updatedHealthInfo)
                if (escalation != RecoveryEscalation.NONE) {
                    notifyEscalation(serviceName, escalation)
                }
                
                // Start recovery process
                startRecoveryProcess(serviceName, failureType)
                
                Log.d(TAG, "Service failure reported: $serviceName, type: $failureType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting service failure: $serviceName", e)
        }
    }
    
    /**
     * Reports successful service health check
     */
    fun reportServiceHealthy(serviceName: String) {
        try {
            val healthInfo = serviceHealthStatus[serviceName]
            if (healthInfo != null && !healthInfo.isHealthy) {
                val updatedHealthInfo = healthInfo.copy(
                    isHealthy = true,
                    lastHealthCheck = System.currentTimeMillis(),
                    consecutiveFailures = 0,
                    healthScore = 1.0f
                )
                
                serviceHealthStatus[serviceName] = updatedHealthInfo
                
                // Cancel any ongoing recovery
                recoveryJobs[serviceName]?.cancel()
                recoveryJobs.remove(serviceName)
                
                // Notify listeners
                recoveryListeners.forEach { listener ->
                    try {
                        listener.onHealthStatusChanged(serviceName, true, 1.0f)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error notifying recovery listener", e)
                    }
                }
                
                Log.d(TAG, "Service health restored: $serviceName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting service health: $serviceName", e)
        }
    }
    
    /**
     * Manually triggers recovery for a service
     */
    suspend fun triggerRecovery(
        serviceName: String,
        strategy: RecoveryStrategy? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val healthInfo = serviceHealthStatus[serviceName]
            if (healthInfo == null) {
                Log.w(TAG, "Service not registered for recovery: $serviceName")
                return@withContext false
            }
            
            val selectedStrategy = strategy ?: RecoveryStrategy.RESTART_SERVICE
            startRecoveryProcess(serviceName, FailureType.UNKNOWN, selectedStrategy)
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering manual recovery: $serviceName", e)
            return@withContext false
        }
    }
    
    /**
     * Gets recovery statistics
     */
    fun getRecoveryStatistics(): RecoveryStatistics {
        val totalAttempts = totalRecoveryAttempts.get()
        val successful = successfulRecoveries.get()
        val failed = failedRecoveries.get()
        val successRate = if (totalAttempts > 0) successful.toFloat() / totalAttempts else 0f
        
        val unhealthyCount = serviceHealthStatus.values.count { !it.isHealthy }
        
        // Calculate average recovery time from history
        val allAttempts = recoveryHistory.values.flatten()
        val completedAttempts = allAttempts.filter { it.endTime != null }
        val averageRecoveryTime = if (completedAttempts.isNotEmpty()) {
            completedAttempts.map { it.duration }.average().toLong()
        } else {
            0L
        }
        
        // Get last recovery time
        val lastRecoveryTime = completedAttempts.maxOfOrNull { it.endTime ?: 0L } ?: 0L
        
        return RecoveryStatistics(
            totalAttempts = totalAttempts,
            successfulRecoveries = successful,
            failedRecoveries = failed,
            successRate = successRate,
            averageRecoveryTime = averageRecoveryTime,
            servicesMonitored = serviceHealthStatus.size,
            unhealthyServices = unhealthyCount,
            lastRecoveryTime = lastRecoveryTime
        )
    }
    
    /**
     * Gets health status for all services
     */
    fun getAllServiceHealthStatus(): Map<String, ServiceHealthInfo> = serviceHealthStatus.toMap()
    
    /**
     * Gets recovery history for a service
     */
    fun getServiceRecoveryHistory(serviceName: String): List<RecoveryAttempt> {
        return recoveryHistory[serviceName]?.toList() ?: emptyList()
    }
    
    /**
     * Adds a recovery listener
     */
    fun addRecoveryListener(listener: ServiceRecoveryListener) {
        recoveryListeners.add(listener)
    }
    
    /**
     * Removes a recovery listener
     */
    fun removeRecoveryListener(listener: ServiceRecoveryListener) {
        recoveryListeners.remove(listener)
    }
    
    // Private methods
    
    private fun startHealthMonitoring() {
        healthCheckJob.set(recoveryScope.launch {
            while (isActive) {
                try {
                    performHealthChecks()
                    delay(HEALTH_CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in health monitoring", e)
                }
            }
        })
    }
    
    private suspend fun performHealthChecks() {
        serviceHealthStatus.forEach { (serviceName, healthInfo) ->
            try {
                // Check if service is still running
                val isServiceRunning = lifecycleManager.getServiceState(serviceName) != LifecycleManager.ServiceState.STOPPED
                
                if (!isServiceRunning && healthInfo.isHealthy) {
                    reportServiceFailure(serviceName, FailureType.SERVICE_CRASH, "Service stopped unexpectedly")
                } else if (isServiceRunning && !healthInfo.isHealthy) {
                    // Service is running but was previously unhealthy - check if it's actually healthy
                    // This could be extended with actual health check logic
                    reportServiceHealthy(serviceName)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Health check error for service: $serviceName", e)
            }
        }
    }
    
    private suspend fun startRecoveryProcess(
        serviceName: String,
        failureType: FailureType,
        preferredStrategy: RecoveryStrategy? = null
    ) {
        try {
            // Cancel any existing recovery job
            recoveryJobs[serviceName]?.cancel()
            
            val recoveryJob = recoveryScope.launch {
                try {
                    val healthInfo = serviceHealthStatus[serviceName] ?: return@launch
                    val config = getRecoveryConfig(serviceName)
                    
                    val strategies = preferredStrategy?.let { listOf(it) } ?: config.strategies
                    
                    for ((attemptIndex, strategy) in strategies.withIndex()) {
                        if (attemptIndex >= config.maxAttempts) {
                            Log.w(TAG, "Max recovery attempts reached for service: $serviceName")
                            break
                        }
                        
                        val attemptNumber = healthInfo.totalRecoveryAttempts + attemptIndex + 1
                        
                        // Notify recovery started
                        recoveryListeners.forEach { listener ->
                            listener.onRecoveryStarted(serviceName, strategy, attemptNumber)
                        }
                        
                        val startTime = System.currentTimeMillis()
                        val success = executeRecoveryStrategy(serviceName, strategy)
                        val duration = System.currentTimeMillis() - startTime
                        
                        // Record recovery attempt
                        val attempt = RecoveryAttempt(
                            serviceName = serviceName,
                            attemptNumber = attemptNumber,
                            strategy = strategy,
                            endTime = System.currentTimeMillis(),
                            success = success,
                            duration = duration
                        )
                        
                        recoveryHistory.computeIfAbsent(serviceName) { mutableListOf() }.add(attempt)
                        
                        totalRecoveryAttempts.incrementAndGet()
                        
                        if (success) {
                            successfulRecoveries.incrementAndGet()
                            
                            // Update health info
                            val updatedHealthInfo = healthInfo.copy(
                                isHealthy = true,
                                lastRecoveryTime = System.currentTimeMillis(),
                                totalRecoveryAttempts = attemptNumber,
                                consecutiveFailures = 0,
                                healthScore = 1.0f
                            )
                            serviceHealthStatus[serviceName] = updatedHealthInfo
                            
                            // Notify success
                            recoveryListeners.forEach { listener ->
                                listener.onRecoveryCompleted(serviceName, strategy, true, duration)
                                listener.onHealthStatusChanged(serviceName, true, 1.0f)
                            }
                            
                            Log.d(TAG, "Recovery successful for service: $serviceName using strategy: $strategy")
                            break
                        } else {
                            failedRecoveries.incrementAndGet()
                            
                            // Notify failure
                            recoveryListeners.forEach { listener ->
                                listener.onRecoveryCompleted(serviceName, strategy, false, duration)
                                listener.onRecoveryFailed(serviceName, strategy, "Recovery strategy failed")
                            }
                            
                            // Calculate delay for next attempt
                            val delay = calculateRecoveryDelay(attemptIndex, config)
                            if (delay > 0) {
                                delay(delay)
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in recovery process for service: $serviceName", e)
                    recoveryListeners.forEach { listener ->
                        listener.onRecoveryFailed(serviceName, RecoveryStrategy.RESTART_SERVICE, e.message)
                    }
                }
            }
            
            recoveryJobs[serviceName] = recoveryJob
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recovery process: $serviceName", e)
        }
    }
    
    private suspend fun executeRecoveryStrategy(
        serviceName: String,
        strategy: RecoveryStrategy
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (strategy) {
                RecoveryStrategy.RESTART_SERVICE -> {
                    lifecycleManager.stopService(serviceName)
                    delay(1000) // Wait for service to stop
                    lifecycleManager.startService(serviceName)
                    delay(2000) // Wait for service to start
                    true
                }
                
                RecoveryStrategy.REBIND_SERVICE -> {
                    lifecycleManager.unbindService(serviceName)
                    delay(1000) // Wait for unbind
                    lifecycleManager.bindService(serviceName)
                    delay(2000) // Wait for bind
                    true
                }
                
                RecoveryStrategy.GRACEFUL_SHUTDOWN -> {
                    lifecycleManager.stopService(serviceName)
                    delay(2000)
                    lifecycleManager.startService(serviceName)
                    delay(3000)
                    true
                }
                
                RecoveryStrategy.RESTART_APP -> {
                    // This would require app-level restart - implement based on requirements
                    Log.w(TAG, "App restart strategy not implemented")
                    false
                }
                
                RecoveryStrategy.EMERGENCY_RECOVERY -> {
                    // Emergency recovery procedures
                    lifecycleManager.emergencyShutdown()
                    delay(5000)
                    lifecycleManager.startService(serviceName)
                    delay(3000)
                    true
                }
                
                RecoveryStrategy.MANUAL_INTERVENTION -> {
                    Log.w(TAG, "Manual intervention required for service: $serviceName")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery strategy failed: $strategy for service: $serviceName", e)
            false
        }
    }
    
    private fun getRecoveryConfig(serviceName: String): RecoveryConfig {
        // Default configuration - could be made configurable
        return RecoveryConfig(
            serviceName = serviceName,
            maxAttempts = MAX_RECOVERY_ATTEMPTS,
            baseDelayMs = BASE_RECOVERY_DELAY_MS,
            maxDelayMs = MAX_RECOVERY_DELAY_MS,
            backoffMultiplier = RECOVERY_BACKOFF_MULTIPLIER,
            strategies = listOf(
                RecoveryStrategy.RESTART_SERVICE,
                RecoveryStrategy.REBIND_SERVICE,
                RecoveryStrategy.GRACEFUL_SHUTDOWN
            )
        )
    }
    
    private fun calculateRecoveryDelay(attemptIndex: Int, config: RecoveryConfig): Long {
        val delay = (config.baseDelayMs * Math.pow(config.backoffMultiplier, attemptIndex.toDouble())).toLong()
        return delay.coerceAtMost(config.maxDelayMs)
    }
    
    private fun calculateHealthScore(consecutiveFailures: Int): Float {
        return when {
            consecutiveFailures == 0 -> 1.0f
            consecutiveFailures <= 2 -> 0.8f
            consecutiveFailures <= 5 -> 0.5f
            consecutiveFailures <= 10 -> 0.2f
            else -> 0.0f
        }
    }
    
    private fun determineEscalationLevel(healthInfo: ServiceHealthInfo): RecoveryEscalation {
        return when {
            healthInfo.consecutiveFailures <= 1 -> RecoveryEscalation.NONE
            healthInfo.consecutiveFailures <= 3 -> RecoveryEscalation.WARN
            healthInfo.consecutiveFailures <= 5 -> RecoveryEscalation.ERROR
            healthInfo.consecutiveFailures <= 10 -> RecoveryEscalation.CRITICAL
            else -> RecoveryEscalation.EMERGENCY
        }
    }
    
    private fun notifyEscalation(serviceName: String, escalation: RecoveryEscalation) {
        recoveryListeners.forEach { listener ->
            try {
                listener.onEscalationTriggered(serviceName, escalation)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying escalation", e)
            }
        }
    }
    
    private suspend fun loadPersistedRecoveryState() {
        try {
            // Load persisted service states and restore health information
            val persistedStates = stateManager.serviceStatesFlow.first()
            
            persistedStates.forEach { (serviceName, state) ->
                val healthInfo = ServiceHealthInfo(
                    serviceName = serviceName,
                    isHealthy = state.isHealthy,
                    lastHealthCheck = state.lastHealthCheck,
                    consecutiveFailures = 0, // Reset on app restart
                    healthScore = if (state.isHealthy) 1.0f else 0.5f
                )
                
                serviceHealthStatus[serviceName] = healthInfo
            }
            
            Log.d(TAG, "Persisted recovery state loaded for ${serviceHealthStatus.size} services")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted recovery state", e)
        }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        try {
            healthCheckJob.get()?.cancel("ServiceRecoveryManager cleanup") // Add cancellation message
            recoveryJobs.values.forEach { it.cancel("ServiceRecoveryManager cleanup") } // Add cancellation message
            recoveryJobs.clear()
            serviceHealthStatus.clear()
            recoveryHistory.clear()
            recoveryListeners.clear()
            
            recoveryScope.cancel("ServiceRecoveryManager cleanup") // Cancel the scope itself

            isInitialized.set(false)
            INSTANCE = null // Allow re-initialization
            Log.d(TAG, "ServiceRecoveryManager cleaned up")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

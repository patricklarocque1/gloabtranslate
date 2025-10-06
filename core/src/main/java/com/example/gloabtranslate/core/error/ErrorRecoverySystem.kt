package com.example.gloabtranslate.core.error

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


/**
 * Comprehensive error handling and recovery system.
 * Handles error propagation, recovery strategies, and system resilience.
 */
class ErrorRecoverySystem {
    
    companion object {
        private const val TAG = "ErrorRecoverySystem"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_BASE_MS = 1000L
        private const val CIRCUIT_BREAKER_THRESHOLD = 5
        private const val CIRCUIT_BREAKER_TIMEOUT_MS = 30000L
    }
    
    private val errorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val errorCounter = AtomicLong(0)
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()
    private val _circuitBreakerStates = MutableStateFlow<Map<String, CircuitBreakerState>>(emptyMap())
    val circuitBreakerStates: StateFlow<Map<String, CircuitBreakerState>> = _circuitBreakerStates.asStateFlow()
    
    // Error tracking
    private val _errorEvents = MutableSharedFlow<ErrorEvent>()
    val errorEvents: SharedFlow<ErrorEvent> = _errorEvents.asSharedFlow()
    
    private val _systemResilience = MutableStateFlow(ResilienceLevel.HEALTHY)
    val systemResilience: StateFlow<ResilienceLevel> = _systemResilience.asStateFlow()
    
    data class ErrorEvent(
        val id: String,
        val source: ErrorSource,
        val severity: ErrorSeverity,
        val error: Throwable,
        val context: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis(),
        val recoveryAttempted: Boolean = false,
        val recoverySuccess: Boolean? = null
    )
    
    enum class ErrorSource {
        AUDIO_RECORDING,
        SPEECH_RECOGNITION,
        TRANSLATION_SERVICE,
        TEXT_TO_SPEECH,
        MODEL_MANAGEMENT,
        NETWORK,
        DATABASE,
        CONFIGURATION,
        UI,
        SERVICE_COORDINATION,
        UNKNOWN
    }
    
    enum class ErrorSeverity {
        LOW,        // Minor issues, system continues normally
        MEDIUM,     // Some functionality degraded but core features work
        HIGH,       // Major functionality affected, recovery needed
        CRITICAL    // System cannot function, immediate action required
    }
    
    enum class ResilienceLevel {
        HEALTHY,     // All systems operating normally
        DEGRADED,    // Some non-critical issues, system still functional
        UNSTABLE,    // Multiple issues, recovery in progress
        CRITICAL     // System severely compromised
    }
    
    data class CircuitBreakerState(
        val failureCount: Int = 0,
        val lastFailureTime: Long = 0L,
        val isOpen: Boolean = false
    )
    
    /**
     * Handle an error with automatic recovery attempt
     */
    suspend fun handleError(
        source: ErrorSource,
        error: Throwable,
        context: Map<String, Any> = emptyMap(),
        recoveryAction: (suspend () -> Boolean)? = null
    ): ErrorRecoveryResult {
        val errorId = generateErrorId()
        val severity = determineSeverity(source, error)
        
        Log.e(TAG, "Error in $source (${severity}): ${error.message}", error)
        
        // Create error event
        val errorEvent = ErrorEvent(
            id = errorId,
            source = source,
            severity = severity,
            error = error,
            context = context
        )
        
        // Check circuit breaker
        val circuitBreakerKey = "${source}_${error.javaClass.simpleName}"
        if (isCircuitBreakerOpen(circuitBreakerKey)) {
            Log.w(TAG, "Circuit breaker open for $circuitBreakerKey, skipping recovery")
            _errorEvents.emit(errorEvent.copy(recoveryAttempted = false))
            updateSystemResilience()
            return ErrorRecoveryResult.CIRCUIT_BREAKER_OPEN
        }
        
        // Attempt recovery if provided
        val recoveryResult = if (recoveryAction != null) {
            attemptRecovery(errorEvent, circuitBreakerKey, recoveryAction)
        } else {
            attemptDefaultRecovery(errorEvent)
        }
        
        // Update circuit breaker
        updateCircuitBreaker(circuitBreakerKey, recoveryResult.success)
        
        // Emit final error event
        _errorEvents.emit(errorEvent.copy(
            recoveryAttempted = true,
            recoverySuccess = recoveryResult.success
        ))
        
        updateSystemResilience()
        return recoveryResult
    }
    
    /**
     * Execute an operation with error handling and retry logic
     */
    suspend fun <T> executeWithRecovery(
        source: ErrorSource,
        operation: suspend () -> T,
        recoveryAction: (suspend () -> Boolean)? = null,
        maxRetries: Int = MAX_RETRY_ATTEMPTS
    ): Result<T> {
        var lastError: Throwable? = null
        
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(operation())
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Operation failed (attempt ${attempt + 1}/$maxRetries) in $source", e)
                
                if (attempt < maxRetries - 1) {
                    // Calculate exponential backoff delay
                    val delay = RETRY_DELAY_BASE_MS * (1L shl attempt)
                    delay(delay)
                    
                    // Attempt recovery if provided
                    if (recoveryAction != null) {
                        try {
                            val recoverySuccess = recoveryAction()
                            if (recoverySuccess) {
                                Log.d(TAG, "Recovery successful for $source, retrying operation")
                            } else {
                                Log.w(TAG, "Recovery failed for $source, but will retry operation")
                            }
                        } catch (recoveryError: Exception) {
                            Log.e(TAG, "Recovery action failed for $source", recoveryError)
                        }
                    }
                }
            }
        }
        
        // All retries failed
        lastError?.let { error ->
            handleError(source, error)
        }
        
        return Result.failure(lastError ?: RuntimeException("Unknown error in $source"))
    }
    
    private suspend fun attemptRecovery(
        errorEvent: ErrorEvent,
        circuitBreakerKey: String,
        recoveryAction: suspend () -> Boolean
    ): ErrorRecoveryResult {
        return try {
            Log.d(TAG, "Attempting recovery for ${errorEvent.source}")
            val success = withTimeout(30000L) { // 30 second timeout
                recoveryAction()
            }
            
            if (success) {
                Log.d(TAG, "Recovery successful for ${errorEvent.source}")
                ErrorRecoveryResult.RECOVERED
            } else {
                Log.w(TAG, "Recovery failed for ${errorEvent.source}")
                ErrorRecoveryResult.RECOVERY_FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery action threw exception for ${errorEvent.source}", e)
            ErrorRecoveryResult.RECOVERY_EXCEPTION
        }
    }
    
    private suspend fun attemptDefaultRecovery(errorEvent: ErrorEvent): ErrorRecoveryResult {
        return when (errorEvent.source) {
            ErrorSource.AUDIO_RECORDING -> {
                Log.d(TAG, "Attempting default audio recording recovery")
                // Default: brief pause then allow retry
                delay(2000)
                ErrorRecoveryResult.DEFAULT_RECOVERY_ATTEMPTED
            }
            ErrorSource.NETWORK -> {
                Log.d(TAG, "Attempting default network recovery") 
                // Default: exponential backoff
                delay(5000)
                ErrorRecoveryResult.DEFAULT_RECOVERY_ATTEMPTED
            }
            ErrorSource.DATABASE -> {
                Log.d(TAG, "Attempting default database recovery")
                // Default: brief pause to allow locks to clear
                delay(1000)
                ErrorRecoveryResult.DEFAULT_RECOVERY_ATTEMPTED
            }
            else -> {
                Log.d(TAG, "No default recovery for ${errorEvent.source}")
                ErrorRecoveryResult.NO_RECOVERY_AVAILABLE
            }
        }
    }
    
    private fun determineSeverity(source: ErrorSource, error: Throwable): ErrorSeverity {
        return when (source) {
            ErrorSource.AUDIO_RECORDING -> when {
                error is SecurityException -> ErrorSeverity.CRITICAL
                error.message?.contains("permission", ignoreCase = true) == true -> ErrorSeverity.CRITICAL
                else -> ErrorSeverity.MEDIUM
            }
            ErrorSource.SPEECH_RECOGNITION -> when {
                error.message?.contains("network", ignoreCase = true) == true -> ErrorSeverity.MEDIUM
                error.message?.contains("model", ignoreCase = true) == true -> ErrorSeverity.HIGH
                else -> ErrorSeverity.MEDIUM
            }
            ErrorSource.TRANSLATION_SERVICE -> ErrorSeverity.HIGH
            ErrorSource.TEXT_TO_SPEECH -> ErrorSeverity.MEDIUM
            ErrorSource.MODEL_MANAGEMENT -> ErrorSeverity.HIGH
            ErrorSource.NETWORK -> when {
                error.message?.contains("timeout", ignoreCase = true) == true -> ErrorSeverity.MEDIUM
                error.message?.contains("connection", ignoreCase = true) == true -> ErrorSeverity.MEDIUM
                else -> ErrorSeverity.HIGH
            }
            ErrorSource.DATABASE -> ErrorSeverity.HIGH
            ErrorSource.CONFIGURATION -> ErrorSeverity.MEDIUM
            ErrorSource.SERVICE_COORDINATION -> ErrorSeverity.CRITICAL
            ErrorSource.UI -> ErrorSeverity.LOW
            ErrorSource.UNKNOWN -> ErrorSeverity.MEDIUM
        }
    }
    
    private fun isCircuitBreakerOpen(key: String): Boolean {
        val state = circuitBreakers[key] ?: return false
        
        if (!state.isOpen) return false
        
        // Check if timeout period has passed
        val timeSinceLastFailure = System.currentTimeMillis() - state.lastFailureTime
        if (timeSinceLastFailure > CIRCUIT_BREAKER_TIMEOUT_MS) {
            // Reset circuit breaker to half-open state
            circuitBreakers[key] = state.copy(isOpen = false)
            Log.d(TAG, "Circuit breaker for $key reset to half-open")
            return false
        }
        
        return true
    }
    
    private fun updateCircuitBreaker(key: String, success: Boolean) {
        val currentState = circuitBreakers[key] ?: CircuitBreakerState()
        
        val newState = if (success) {
            // Reset on success
            CircuitBreakerState()
        } else {
            // Increment failure count
            val newFailureCount = currentState.failureCount + 1
            val shouldOpen = newFailureCount >= CIRCUIT_BREAKER_THRESHOLD
            
            if (shouldOpen && !currentState.isOpen) {
                Log.w(TAG, "Circuit breaker opened for $key after $newFailureCount failures")
            }
            
            currentState.copy(
                failureCount = newFailureCount,
                lastFailureTime = System.currentTimeMillis(),
                isOpen = shouldOpen
            )
        }
        
        circuitBreakers[key] = newState
        // Publish snapshot for observers (UI/debug screen)
        _circuitBreakerStates.value = circuitBreakers.toMap()
    }

    /** Snapshot accessor (cheap defensive copy). */
    fun getCircuitBreakerSnapshot(): Map<String, CircuitBreakerState> = circuitBreakers.toMap()
    
    private fun updateSystemResilience() {
        val recentErrors = errorCounter.get()
        val openCircuitBreakers = circuitBreakers.values.count { it.isOpen }
        
        _systemResilience.value = when {
            openCircuitBreakers >= 3 -> ResilienceLevel.CRITICAL
            openCircuitBreakers >= 2 || recentErrors > 20 -> ResilienceLevel.UNSTABLE
            openCircuitBreakers >= 1 || recentErrors > 10 -> ResilienceLevel.DEGRADED
            else -> ResilienceLevel.HEALTHY
        }
    }
    
    private fun generateErrorId(): String {
        return "error_${System.currentTimeMillis()}_${errorCounter.incrementAndGet()}"
    }
    
    /**
     * Get system health metrics
     */
    fun getHealthMetrics(): HealthMetrics {
        return HealthMetrics(
            totalErrors = errorCounter.get(),
            openCircuitBreakers = circuitBreakers.values.count { it.isOpen },
            resilience = _systemResilience.value,
            circuitBreakerStates = circuitBreakers.toMap()
        )
    }
    
    /**
     * Reset circuit breakers (use cautiously)
     */
    fun resetCircuitBreakers() {
        circuitBreakers.clear()
        Log.d(TAG, "All circuit breakers reset")
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        errorScope.cancel()
        circuitBreakers.clear()
        Log.d(TAG, "Error recovery system cleaned up")
    }
    
    enum class ErrorRecoveryResult {
        RECOVERED,
        RECOVERY_FAILED,
        RECOVERY_EXCEPTION,
        DEFAULT_RECOVERY_ATTEMPTED,
        NO_RECOVERY_AVAILABLE,
        CIRCUIT_BREAKER_OPEN;
        
        val success: Boolean
            get() = this == RECOVERED || this == DEFAULT_RECOVERY_ATTEMPTED
    }
    
    data class HealthMetrics(
        val totalErrors: Long,
        val openCircuitBreakers: Int,
        val resilience: ResilienceLevel,
        val circuitBreakerStates: Map<String, CircuitBreakerState>
    )
}
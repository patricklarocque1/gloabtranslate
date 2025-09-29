package com.example.gloabtranslate.core.error

import android.content.Context
import com.example.gloabtranslate.core.logging.CrashReporter
import com.example.gloabtranslate.core.logging.StructuredLogger
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive error handling system
 * Centralizes error handling, categorization, and recovery strategies
 */
class ErrorHandler private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ErrorHandler"
        private const val MAX_ERROR_HISTORY = 1000
        private const val ERROR_RETENTION_DAYS = 7
        
        @Volatile
        private var INSTANCE: ErrorHandler? = null
        
        fun getInstance(context: Context): ErrorHandler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ErrorHandler(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    data class ErrorInfo(
        val id: String,
        val timestamp: Long,
        val errorType: ErrorType,
        val severity: ErrorSeverity,
        val message: String,
        val throwable: Throwable?,
        val context: Map<String, Any>,
        val stackTrace: String,
        val recoveryAttempted: Boolean = false,
        val recoverySuccessful: Boolean = false,
        val userAction: String? = null
    )
    
    enum class ErrorType {
        NETWORK_ERROR,
        TRANSLATION_ERROR,
        AUDIO_ERROR,
        PERMISSION_ERROR,
        STORAGE_ERROR,
        MEMORY_ERROR,
        UI_ERROR,
        SYSTEM_ERROR,
        UNKNOWN_ERROR
    }
    
    enum class ErrorSeverity {
        LOW,        // Minor issues that don't affect core functionality
        MEDIUM,     // Issues that affect some functionality
        HIGH,       // Issues that affect core functionality
        CRITICAL    // Issues that cause app crashes or data loss
    }
    
    data class ErrorRecoveryStrategy(
        val errorType: ErrorType,
        val severity: ErrorSeverity,
        val recoveryActions: List<RecoveryAction>,
        val maxRetryAttempts: Int = 3,
        val retryDelayMs: Long = 1000L
    )
    
    enum class RecoveryAction {
        RETRY_OPERATION,
        FALLBACK_METHOD,
        CLEAR_CACHE,
        RESTART_SERVICE,
        REQUEST_PERMISSION,
        SHOW_USER_MESSAGE,
        LOGOUT_USER,
        RESET_APP_STATE
    }
    
    private val structuredLogger = StructuredLogger.getInstance(context)
    private val crashReporter = CrashReporter.getInstance(context)
    private val errorRecovery = ErrorRecovery.getInstance(context)
    private val userFriendlyErrors = UserFriendlyErrors.getInstance(context)
    private val errorReporter = ErrorReporter.getInstance(context)
    
    // Error tracking
    private val errorHistory = ConcurrentHashMap<String, ErrorInfo>()
    private val errorCounts = ConcurrentHashMap<ErrorType, AtomicLong>()
    private val isEnabled = AtomicBoolean(true)
    private val errorCounter = AtomicLong(0)
    
    // Recovery strategies
    private val recoveryStrategies = mutableMapOf<ErrorType, ErrorRecoveryStrategy>()
    
    // Coroutine scope for background operations
    private val errorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        initializeRecoveryStrategies()
    }
    
    /**
     * Handle an error with automatic recovery
     */
    suspend fun handleError(
        error: Throwable,
        errorType: ErrorType,
        context: Map<String, Any> = emptyMap(),
        userAction: String? = null
    ): ErrorHandlingResult {
        if (!isEnabled.get()) return ErrorHandlingResult.IGNORED
        
        try {
            val errorInfo = createErrorInfo(error, errorType, context, userAction)
            errorHistory[errorInfo.id] = errorInfo
            
            // Update error counts
            errorCounts.getOrPut(errorType) { AtomicLong(0) }.incrementAndGet()
            errorCounter.incrementAndGet()
            
            // Log the error
            logError(errorInfo)
            
            // Report to crash reporter if critical
            if (errorInfo.severity == ErrorSeverity.CRITICAL) {
                crashReporter.reportCrash(error, context)
            }
            
            // Attempt recovery
            val recoveryResult = attemptRecovery(errorInfo)
            
            // Report to error reporter
            errorReporter.reportError(errorInfo)
            
            return recoveryResult
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error in error handler", throwable = e)
            return ErrorHandlingResult.FAILED
        }
    }
    
    /**
     * Handle a network error
     */
    suspend fun handleNetworkError(
        error: Throwable,
        context: Map<String, Any> = emptyMap()
    ): ErrorHandlingResult {
        return handleError(error, ErrorType.NETWORK_ERROR, context, "network_operation")
    }
    
    /**
     * Handle a translation error
     */
    suspend fun handleTranslationError(
        error: Throwable,
        sourceLanguage: String,
        targetLanguage: String,
        context: Map<String, Any> = emptyMap()
    ): ErrorHandlingResult {
        val translationContext = context + mapOf(
            "source_language" to sourceLanguage,
            "target_language" to targetLanguage
        )
        return handleError(error, ErrorType.TRANSLATION_ERROR, translationContext, "translation")
    }
    
    /**
     * Handle an audio error
     */
    suspend fun handleAudioError(
        error: Throwable,
        context: Map<String, Any> = emptyMap()
    ): ErrorHandlingResult {
        return handleError(error, ErrorType.AUDIO_ERROR, context, "audio_operation")
    }
    
    /**
     * Handle a permission error
     */
    suspend fun handlePermissionError(
        error: Throwable,
        permission: String,
        context: Map<String, Any> = emptyMap()
    ): ErrorHandlingResult {
        val permissionContext = context + mapOf("permission" to permission)
        return handleError(error, ErrorType.PERMISSION_ERROR, permissionContext, "permission_request")
    }
    
    /**
     * Handle a storage error
     */
    suspend fun handleStorageError(
        error: Throwable,
        context: Map<String, Any> = emptyMap()
    ): ErrorHandlingResult {
        return handleError(error, ErrorType.STORAGE_ERROR, context, "storage_operation")
    }
    
    /**
     * Handle a memory error
     */
    suspend fun handleMemoryError(
        error: Throwable,
        context: Map<String, Any> = emptyMap()
    ): ErrorHandlingResult {
        return handleError(error, ErrorType.MEMORY_ERROR, context, "memory_operation")
    }
    
    /**
     * Handle a UI error
     */
    suspend fun handleUIError(
        error: Throwable,
        screenName: String,
        context: Map<String, Any> = emptyMap()
    ): ErrorHandlingResult {
        val uiContext = context + mapOf("screen_name" to screenName)
        return handleError(error, ErrorType.UI_ERROR, uiContext, "ui_operation")
    }
    
    /**
     * Get error statistics
     */
    fun getErrorStatistics(): ErrorStatistics {
        val totalErrors = errorCounter.get()
        val errorsByType = errorCounts.mapValues { it.value.get() }
        val errorsBySeverity = errorHistory.values.groupBy { it.severity }.mapValues { it.value.size }
        val recentErrors = errorHistory.values.sortedByDescending { it.timestamp }.take(10)
        
        return ErrorStatistics(
            totalErrors = totalErrors,
            errorsByType = errorsByType,
            errorsBySeverity = errorsBySeverity,
            recentErrors = recentErrors
        )
    }
    
    /**
     * Get errors by type
     */
    fun getErrorsByType(errorType: ErrorType): List<ErrorInfo> {
        return errorHistory.values.filter { it.errorType == errorType }
    }
    
    /**
     * Get errors by severity
     */
    fun getErrorsBySeverity(severity: ErrorSeverity): List<ErrorInfo> {
        return errorHistory.values.filter { it.severity == severity }
    }
    
    /**
     * Get recent errors
     */
    fun getRecentErrors(limit: Int = 50): List<ErrorInfo> {
        return errorHistory.values
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    /**
     * Clear error history
     */
    fun clearErrorHistory() {
        errorHistory.clear()
        errorCounts.clear()
        errorCounter.set(0)
    }
    
    /**
     * Enable/disable error handling
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
    }
    
    /**
     * Add custom recovery strategy
     */
    fun addRecoveryStrategy(strategy: ErrorRecoveryStrategy) {
        recoveryStrategies[strategy.errorType] = strategy
    }
    
    /**
     * Create error info from throwable
     */
    private fun createErrorInfo(
        error: Throwable,
        errorType: ErrorType,
        context: Map<String, Any>,
        userAction: String?
    ): ErrorInfo {
        val severity = determineSeverity(error, errorType)
        val message = error.message ?: "Unknown error occurred"
        val stackTrace = error.stackTraceToString()
        
        return ErrorInfo(
            id = generateErrorId(),
            timestamp = System.currentTimeMillis(),
            errorType = errorType,
            severity = severity,
            message = message,
            throwable = error,
            context = context,
            stackTrace = stackTrace,
            userAction = userAction
        )
    }
    
    /**
     * Determine error severity based on error type and content
     */
    private fun determineSeverity(error: Throwable, errorType: ErrorType): ErrorSeverity {
        return when (errorType) {
            ErrorType.NETWORK_ERROR -> {
                when {
                    error.message?.contains("timeout", ignoreCase = true) == true -> ErrorSeverity.MEDIUM
                    error.message?.contains("connection", ignoreCase = true) == true -> ErrorSeverity.MEDIUM
                    else -> ErrorSeverity.LOW
                }
            }
            ErrorType.TRANSLATION_ERROR -> {
                when {
                    error.message?.contains("model", ignoreCase = true) == true -> ErrorSeverity.HIGH
                    error.message?.contains("language", ignoreCase = true) == true -> ErrorSeverity.MEDIUM
                    else -> ErrorSeverity.MEDIUM
                }
            }
            ErrorType.AUDIO_ERROR -> {
                when {
                    error.message?.contains("permission", ignoreCase = true) == true -> ErrorSeverity.HIGH
                    error.message?.contains("hardware", ignoreCase = true) == true -> ErrorSeverity.HIGH
                    else -> ErrorSeverity.MEDIUM
                }
            }
            ErrorType.PERMISSION_ERROR -> ErrorSeverity.HIGH
            ErrorType.STORAGE_ERROR -> {
                when {
                    error.message?.contains("space", ignoreCase = true) == true -> ErrorSeverity.HIGH
                    error.message?.contains("access", ignoreCase = true) == true -> ErrorSeverity.HIGH
                    else -> ErrorSeverity.MEDIUM
                }
            }
            ErrorType.MEMORY_ERROR -> ErrorSeverity.HIGH
            ErrorType.UI_ERROR -> ErrorSeverity.LOW
            ErrorType.SYSTEM_ERROR -> ErrorSeverity.CRITICAL
            ErrorType.UNKNOWN_ERROR -> ErrorSeverity.MEDIUM
        }
    }
    
    /**
     * Attempt error recovery
     */
    private suspend fun attemptRecovery(errorInfo: ErrorInfo): ErrorHandlingResult {
        val strategy = recoveryStrategies[errorInfo.errorType] ?: return ErrorHandlingResult.NO_RECOVERY
        
        return try {
            val recoveryResult = errorRecovery.attemptRecovery(errorInfo, strategy)
            
            // Update error info with recovery result
            val updatedErrorInfo = errorInfo.copy(
                recoveryAttempted = true,
                recoverySuccessful = recoveryResult == ErrorHandlingResult.RECOVERED
            )
            errorHistory[errorInfo.id] = updatedErrorInfo
            
            recoveryResult
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error during recovery attempt", throwable = e)
            ErrorHandlingResult.FAILED
        }
    }
    
    /**
     * Log error information
     */
    private fun logError(errorInfo: ErrorInfo) {
        val logLevel = when (errorInfo.severity) {
            ErrorSeverity.LOW -> StructuredLogger.LogLevel.INFO
            ErrorSeverity.MEDIUM -> StructuredLogger.LogLevel.WARN
            ErrorSeverity.HIGH -> StructuredLogger.LogLevel.ERROR
            ErrorSeverity.CRITICAL -> StructuredLogger.LogLevel.FATAL
        }
        
        val metadata = errorInfo.context + mapOf(
            "error_type" to errorInfo.errorType.name,
            "severity" to errorInfo.severity.name,
            "error_id" to errorInfo.id,
            "user_action" to (errorInfo.userAction ?: "unknown")
        )
        
        structuredLogger.log(
            logLevel,
            "ERROR_HANDLER",
            "Error handled: ${errorInfo.message}",
            metadata,
            errorInfo.throwable
        )
    }
    
    /**
     * Initialize recovery strategies
     */
    private fun initializeRecoveryStrategies() {
        // Network error recovery
        recoveryStrategies[ErrorType.NETWORK_ERROR] = ErrorRecoveryStrategy(
            errorType = ErrorType.NETWORK_ERROR,
            severity = ErrorSeverity.MEDIUM,
            recoveryActions = listOf(
                RecoveryAction.RETRY_OPERATION,
                RecoveryAction.FALLBACK_METHOD,
                RecoveryAction.SHOW_USER_MESSAGE
            ),
            maxRetryAttempts = 3,
            retryDelayMs = 2000L
        )
        
        // Translation error recovery
        recoveryStrategies[ErrorType.TRANSLATION_ERROR] = ErrorRecoveryStrategy(
            errorType = ErrorType.TRANSLATION_ERROR,
            severity = ErrorSeverity.MEDIUM,
            recoveryActions = listOf(
                RecoveryAction.RETRY_OPERATION,
                RecoveryAction.FALLBACK_METHOD,
                RecoveryAction.CLEAR_CACHE,
                RecoveryAction.SHOW_USER_MESSAGE
            ),
            maxRetryAttempts = 2,
            retryDelayMs = 1000L
        )
        
        // Audio error recovery
        recoveryStrategies[ErrorType.AUDIO_ERROR] = ErrorRecoveryStrategy(
            errorType = ErrorType.AUDIO_ERROR,
            severity = ErrorSeverity.HIGH,
            recoveryActions = listOf(
                RecoveryAction.RESTART_SERVICE,
                RecoveryAction.REQUEST_PERMISSION,
                RecoveryAction.SHOW_USER_MESSAGE
            ),
            maxRetryAttempts = 2,
            retryDelayMs = 3000L
        )
        
        // Permission error recovery
        recoveryStrategies[ErrorType.PERMISSION_ERROR] = ErrorRecoveryStrategy(
            errorType = ErrorType.PERMISSION_ERROR,
            severity = ErrorSeverity.HIGH,
            recoveryActions = listOf(
                RecoveryAction.REQUEST_PERMISSION,
                RecoveryAction.SHOW_USER_MESSAGE
            ),
            maxRetryAttempts = 1,
            retryDelayMs = 0L
        )
        
        // Storage error recovery
        recoveryStrategies[ErrorType.STORAGE_ERROR] = ErrorRecoveryStrategy(
            errorType = ErrorType.STORAGE_ERROR,
            severity = ErrorSeverity.HIGH,
            recoveryActions = listOf(
                RecoveryAction.CLEAR_CACHE,
                RecoveryAction.SHOW_USER_MESSAGE
            ),
            maxRetryAttempts = 1,
            retryDelayMs = 0L
        )
        
        // Memory error recovery
        recoveryStrategies[ErrorType.MEMORY_ERROR] = ErrorRecoveryStrategy(
            errorType = ErrorType.MEMORY_ERROR,
            severity = ErrorSeverity.HIGH,
            recoveryActions = listOf(
                RecoveryAction.CLEAR_CACHE,
                RecoveryAction.RESTART_SERVICE,
                RecoveryAction.SHOW_USER_MESSAGE
            ),
            maxRetryAttempts = 1,
            retryDelayMs = 0L
        )
        
        // UI error recovery
        recoveryStrategies[ErrorType.UI_ERROR] = ErrorRecoveryStrategy(
            errorType = ErrorType.UI_ERROR,
            severity = ErrorSeverity.LOW,
            recoveryActions = listOf(
                RecoveryAction.RETRY_OPERATION,
                RecoveryAction.SHOW_USER_MESSAGE
            ),
            maxRetryAttempts = 2,
            retryDelayMs = 500L
        )
        
        // System error recovery
        recoveryStrategies[ErrorType.SYSTEM_ERROR] = ErrorRecoveryStrategy(
            errorType = ErrorType.SYSTEM_ERROR,
            severity = ErrorSeverity.CRITICAL,
            recoveryActions = listOf(
                RecoveryAction.RESET_APP_STATE,
                RecoveryAction.SHOW_USER_MESSAGE
            ),
            maxRetryAttempts = 1,
            retryDelayMs = 0L
        )
    }
    
    /**
     * Generate unique error ID
     */
    private fun generateErrorId(): String {
        return "error_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}"
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        errorScope.cancel()
    }
    
    /**
     * Data class for error statistics
     */
    data class ErrorStatistics(
        val totalErrors: Long,
        val errorsByType: Map<ErrorType, Long>,
        val errorsBySeverity: Map<ErrorSeverity, Int>,
        val recentErrors: List<ErrorInfo>
    )
    
    /**
     * Enum for error handling results
     */
    enum class ErrorHandlingResult {
        RECOVERED,      // Error was successfully recovered
        FAILED,         // Recovery attempt failed
        NO_RECOVERY,    // No recovery strategy available
        IGNORED         // Error was ignored (disabled)
    }
}

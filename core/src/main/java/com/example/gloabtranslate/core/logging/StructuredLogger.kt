package com.example.gloabtranslate.core.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.ArrayList

/**
 * Structured logging system with multiple levels and persistent storage
 * Provides comprehensive logging capabilities for debugging and monitoring
 */
class StructuredLogger private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "StructuredLogger"
        private const val MAX_LOG_ENTRIES = 10000
        private const val LOG_FILE_SIZE_LIMIT = 10 * 1024 * 1024 // 10MB
        private const val LOG_RETENTION_DAYS = 7
        
        @Volatile
        private var INSTANCE: StructuredLogger? = null
        
        fun getInstance(context: Context): StructuredLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StructuredLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    enum class LogLevel(val priority: Int, val tag: String) {
        VERBOSE(0, "V"),
        DEBUG(1, "D"),
        INFO(2, "I"),
        WARN(3, "W"),
        ERROR(4, "E"),
        FATAL(5, "F")
    }
    
    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val tag: String,
        val message: String,
        val metadata: Map<String, Any> = emptyMap(),
        val throwable: Throwable? = null,
        val threadName: String = Thread.currentThread().name
    )
    
    private val logEntries = ConcurrentHashMap<String, ArrayList<LogEntry>>()
    private val logFile = File(context.filesDir, "structured_logs.json")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logCounter = AtomicLong(0)
    private val isEnabled = AtomicLong(1) // 1 = enabled, 0 = disabled
    
    // Coroutine scope for background operations
    private val loggerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Clean up old logs on initialization
        loggerScope.launch {
            cleanupOldLogs()
        }
    }
    
    /**
     * Log a message with structured data
     */
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        metadata: Map<String, Any> = emptyMap(),
        throwable: Throwable? = null
    ) {
        if (isEnabled.get() == 0L) return
        
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            metadata = metadata,
            throwable = throwable
        )
        
        // Add to memory
        addToMemory(entry)
        
        // Log to Android system
        logToSystem(entry)
        
        // Write to file asynchronously
        loggerScope.launch {
            writeToFile(entry)
        }
        
        // Check if cleanup is needed
        if (logCounter.get() % 100 == 0L) {
            loggerScope.launch {
                performMaintenance()
            }
        }
    }
    
    /**
     * Log verbose message
     */
    fun v(tag: String, message: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        log(LogLevel.VERBOSE, tag, message, metadata, throwable)
    }
    
    /**
     * Log debug message
     */
    fun d(tag: String, message: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        log(LogLevel.DEBUG, tag, message, metadata, throwable)
    }
    
    /**
     * Log info message
     */
    fun i(tag: String, message: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        log(LogLevel.INFO, tag, message, metadata, throwable)
    }
    
    /**
     * Log warning message
     */
    fun w(tag: String, message: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        log(LogLevel.WARN, tag, message, metadata, throwable)
    }
    
    /**
     * Log error message
     */
    fun e(tag: String, message: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, metadata, throwable)
    }
    
    /**
     * Log fatal message
     */
    fun f(tag: String, message: String, metadata: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        log(LogLevel.FATAL, tag, message, metadata, throwable)
    }
    
    /**
     * Log performance metrics
     */
    fun logPerformance(
        operation: String,
        durationMs: Long,
        metadata: Map<String, Any> = emptyMap()
    ) {
        val perfMetadata = metadata + mapOf(
            "operation" to operation,
            "duration_ms" to durationMs,
            "performance_metric" to true
        )
        
        val level = when {
            durationMs > 5000 -> LogLevel.WARN
            durationMs > 1000 -> LogLevel.INFO
            else -> LogLevel.DEBUG
        }
        
        log(level, "PERFORMANCE", "Operation completed: $operation", perfMetadata)
    }
    
    /**
     * Log user action
     */
    fun logUserAction(
        action: String,
        screen: String,
        metadata: Map<String, Any> = emptyMap()
    ) {
        val actionMetadata = metadata + mapOf(
            "action" to action,
            "screen" to screen,
            "user_action" to true,
            "timestamp" to System.currentTimeMillis()
        )
        
        log(LogLevel.INFO, "USER_ACTION", "User action: $action", actionMetadata)
    }
    
    /**
     * Log API call
     */
    fun logApiCall(
        endpoint: String,
        method: String,
        statusCode: Int,
        durationMs: Long,
        metadata: Map<String, Any> = emptyMap()
    ) {
        val apiMetadata = metadata + mapOf(
            "endpoint" to endpoint,
            "method" to method,
            "status_code" to statusCode,
            "duration_ms" to durationMs,
            "api_call" to true
        )
        
        val level = when {
            statusCode >= 500 -> LogLevel.ERROR
            statusCode >= 400 -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        
        log(level, "API_CALL", "API call: $method $endpoint", apiMetadata)
    }
    
    /**
     * Log translation operation
     */
    fun logTranslation(
        sourceLanguage: String,
        targetLanguage: String,
        textLength: Int,
        isOnDevice: Boolean,
        success: Boolean,
        durationMs: Long,
        metadata: Map<String, Any> = emptyMap()
    ) {
        val translationMetadata = metadata + mapOf(
            "source_language" to sourceLanguage,
            "target_language" to targetLanguage,
            "text_length" to textLength,
            "is_on_device" to isOnDevice,
            "success" to success,
            "duration_ms" to durationMs,
            "translation" to true
        )
        
        val level = if (success) LogLevel.INFO else LogLevel.ERROR
        log(level, "TRANSLATION", "Translation: $sourceLanguage -> $targetLanguage", translationMetadata)
    }
    
    /**
     * Get logs for a specific tag
     */
    fun getLogs(tag: String, limit: Int = 100): List<LogEntry> {
        return logEntries[tag]?.takeLast(limit) ?: emptyList()
    }
    
    /**
     * Get logs by level
     */
    fun getLogsByLevel(level: LogLevel, limit: Int = 100): List<LogEntry> {
        return logEntries.values
            .flatten()
            .filter { it.level == level }
            .sortedBy { it.timestamp }
            .takeLast(limit)
    }
    
    /**
     * Get all logs
     */
    fun getAllLogs(limit: Int = 1000): List<LogEntry> {
        return logEntries.values
            .flatten()
            .sortedBy { it.timestamp }
            .takeLast(limit)
    }
    
    /**
     * Clear all logs
     */
    fun clearLogs() {
        logEntries.clear()
        logCounter.set(0)
        
        // Clear log file
        loggerScope.launch {
            try {
                if (logFile.exists()) {
                    logFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing log file", e)
            }
        }
    }
    
    /**
     * Enable/disable logging
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(if (enabled) 1L else 0L)
    }
    
    /**
     * Export logs to file
     */
    suspend fun exportLogs(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val allLogs = getAllLogs(MAX_LOG_ENTRIES)
            val jsonArray = org.json.JSONArray()
            
            allLogs.forEach { entry ->
                val jsonObject = JSONObject().apply {
                    put("timestamp", entry.timestamp)
                    put("level", entry.level.name)
                    put("tag", entry.tag)
                    put("message", entry.message)
                    put("thread", entry.threadName)
                    
                    if (entry.metadata.isNotEmpty()) {
                        val metadataJson = JSONObject()
                        entry.metadata.forEach { (key, value) ->
                            metadataJson.put(key, value.toString())
                        }
                        put("metadata", metadataJson)
                    }
                    
                    if (entry.throwable != null) {
                        put("exception", entry.throwable.stackTraceToString())
                    }
                }
                jsonArray.put(jsonObject)
            }
            
            outputFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting logs", e)
            false
        }
    }
    
    /**
     * Add log entry to memory
     */
    private fun addToMemory(entry: LogEntry) {
        val tag = entry.tag
        if (!logEntries.containsKey(tag)) {
            logEntries[tag] = ArrayList()
        }
        
        logEntries[tag]?.add(entry)
        logCounter.incrementAndGet()
        
        // Limit memory usage
        if (logEntries[tag]?.size ?: 0 > MAX_LOG_ENTRIES / 10) {
            logEntries[tag]?.removeAt(0)
        }
    }
    
    /**
     * Log to Android system
     */
    private fun logToSystem(entry: LogEntry) {
        val message = buildString {
            append("[${dateFormat.format(Date(entry.timestamp))}] ")
            append("${entry.level.tag}/$TAG: ")
            append(entry.message)
            
            if (entry.metadata.isNotEmpty()) {
                append(" | Metadata: ${entry.metadata}")
            }
            
            if (entry.throwable != null) {
                append(" | Exception: ${entry.throwable.message}")
            }
        }
        
        when (entry.level) {
            LogLevel.VERBOSE -> Log.v(TAG, message)
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
            LogLevel.FATAL -> Log.wtf(TAG, message)
        }
    }
    
    /**
     * Write log entry to file
     */
    private suspend fun writeToFile(entry: LogEntry) = withContext(Dispatchers.IO) {
        try {
            val jsonObject = JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("level", entry.level.name)
                put("tag", entry.tag)
                put("message", entry.message)
                put("thread", entry.threadName)
                
                if (entry.metadata.isNotEmpty()) {
                    val metadataJson = JSONObject()
                    entry.metadata.forEach { (key, value) ->
                        metadataJson.put(key, value.toString())
                    }
                    put("metadata", metadataJson)
                }
                
                if (entry.throwable != null) {
                    put("exception", entry.throwable.stackTraceToString())
                }
            }
            
            val logLine = jsonObject.toString() + "\n"
            
            if (!logFile.exists()) {
                logFile.createNewFile()
            }
            
            FileWriter(logFile, true).use { writer ->
                writer.write(logLine)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing log to file", e)
        }
    }
    
    /**
     * Perform maintenance tasks
     */
    private suspend fun performMaintenance() = withContext(Dispatchers.IO) {
        try {
            // Clean up old logs
            cleanupOldLogs()
            
            // Check file size
            if (logFile.exists() && logFile.length() > LOG_FILE_SIZE_LIMIT) {
                rotateLogFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during maintenance", e)
        }
    }
    
    /**
     * Clean up old logs
     */
    private suspend fun cleanupOldLogs() = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
            
            logEntries.values.forEach { entries ->
                entries.removeAll { it.timestamp < cutoffTime }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old logs", e)
        }
    }
    
    /**
     * Rotate log file when it gets too large
     */
    private suspend fun rotateLogFile() = withContext(Dispatchers.IO) {
        try {
            val backupFile = File(logFile.parent, "structured_logs_backup.json")
            if (backupFile.exists()) {
                backupFile.delete()
            }
            
            logFile.renameTo(backupFile)
            logFile.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating log file", e)
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        loggerScope.cancel()
    }
}

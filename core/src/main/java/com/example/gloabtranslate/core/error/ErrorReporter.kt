package com.example.gloabtranslate.core.error

import android.content.Context
import com.example.gloabtranslate.core.logging.CrashReporter
import com.example.gloabtranslate.core.logging.StructuredLogger
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Error reporting system for developers
 * Collects, analyzes, and reports errors to development team
 */
class ErrorReporter private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ErrorReporter"
        private const val ERROR_REPORT_DIR = "error_reports"
        private const val MAX_ERROR_REPORTS = 100
        private const val ERROR_REPORT_RETENTION_DAYS = 30
        private const val BATCH_SIZE = 10
        private const val REPORT_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        
        @Volatile
        private var INSTANCE: ErrorReporter? = null
        
        fun getInstance(context: Context): ErrorReporter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ErrorReporter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    data class ErrorReport(
        val id: String,
        val timestamp: Long,
        val errorInfo: ErrorHandler.ErrorInfo,
        val appVersion: String,
        val deviceInfo: DeviceInfo,
        val userInfo: UserInfo?,
        val sessionInfo: SessionInfo,
        val additionalContext: Map<String, Any>
    )
    
    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val screenResolution: String,
        val memoryInfo: MemoryInfo,
        val isEmulator: Boolean
    )
    
    data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long,
        val memoryUsagePercent: Float
    )
    
    data class UserInfo(
        val userId: String?,
        val sessionId: String,
        val language: String,
        val timezone: String,
        val appUsageTime: Long
    )
    
    data class SessionInfo(
        val sessionId: String,
        val sessionStartTime: Long,
        val sessionDuration: Long,
        val screenViews: List<String>,
        val userActions: List<String>,
        val errorCount: Int
    )
    
    data class ErrorSummary(
        val totalErrors: Int,
        val errorsByType: Map<ErrorHandler.ErrorType, Int>,
        val errorsBySeverity: Map<ErrorHandler.ErrorSeverity, Int>,
        val mostCommonError: ErrorHandler.ErrorType?,
        val errorTrend: ErrorTrend,
        val criticalErrors: List<ErrorReport>
    )
    
    enum class ErrorTrend {
        INCREASING,
        DECREASING,
        STABLE
    }
    
    private val structuredLogger = StructuredLogger.getInstance(context)
    private val crashReporter = CrashReporter.getInstance(context)
    private val errorReportDir = File(context.filesDir, ERROR_REPORT_DIR)
    
    // Error reporting state
    private val isEnabled = AtomicBoolean(true)
    private val isReporting = AtomicBoolean(false)
    private val errorReports = ConcurrentHashMap<String, ErrorReport>()
    private val pendingReports = mutableListOf<ErrorReport>()
    private val reportCounter = AtomicLong(0)
    
    // Coroutine scope for background operations
    private val reporterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Create error report directory
        if (!errorReportDir.exists()) {
            errorReportDir.mkdirs()
        }
        
        // Load existing error reports
        reporterScope.launch {
            loadExistingErrorReports()
        }
        
        // Start periodic reporting
        startPeriodicReporting()
    }
    
    /**
     * Report an error to developers
     */
    fun reportError(errorInfo: ErrorHandler.ErrorInfo) {
        if (!isEnabled.get()) return
        
        try {
            val errorReport = createErrorReport(errorInfo)
            errorReports[errorReport.id] = errorReport
            pendingReports.add(errorReport)
            
            // Log the error report
            structuredLogger.e(
                "ERROR_REPORT",
                "Error reported to developers: ${errorInfo.id}",
                mapOf(
                    "error_report_id" to errorReport.id,
                    "error_type" to errorInfo.errorType.name,
                    "severity" to errorInfo.severity.name
                )
            )
            
            // Save to file asynchronously
            reporterScope.launch {
                saveErrorReport(errorReport)
            }
            
            // Check if batch reporting is needed
            if (pendingReports.size >= BATCH_SIZE) {
                reporterScope.launch {
                    processPendingReports()
                }
            }
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error creating error report", throwable = e)
        }
    }
    
    /**
     * Report multiple errors at once
     */
    fun reportErrors(errorInfos: List<ErrorHandler.ErrorInfo>) {
        if (!isEnabled.get()) return
        
        errorInfos.forEach { errorInfo ->
            reportError(errorInfo)
        }
    }
    
    /**
     * Get error summary
     */
    fun getErrorSummary(): ErrorSummary {
        val allReports = errorReports.values.toList()
        val totalErrors = allReports.size
        
        val errorsByType = allReports.groupBy { it.errorInfo.errorType }
            .mapValues { it.value.size }
        
        val errorsBySeverity = allReports.groupBy { it.errorInfo.severity }
            .mapValues { it.value.size }
        
        val mostCommonError = errorsByType.maxByOrNull { it.value }?.key
        
        val errorTrend = calculateErrorTrend(allReports)
        
        val criticalErrors = allReports.filter { it.errorInfo.severity == ErrorHandler.ErrorSeverity.CRITICAL }
            .sortedByDescending { it.timestamp }
            .take(10)
        
        return ErrorSummary(
            totalErrors = totalErrors,
            errorsByType = errorsByType,
            errorsBySeverity = errorsBySeverity,
            mostCommonError = mostCommonError,
            errorTrend = errorTrend,
            criticalErrors = criticalErrors
        )
    }
    
    /**
     * Get error reports by type
     */
    fun getErrorReportsByType(errorType: ErrorHandler.ErrorType): List<ErrorReport> {
        return errorReports.values.filter { it.errorInfo.errorType == errorType }
    }
    
    /**
     * Get error reports by severity
     */
    fun getErrorReportsBySeverity(severity: ErrorHandler.ErrorSeverity): List<ErrorReport> {
        return errorReports.values.filter { it.errorInfo.severity == severity }
    }
    
    /**
     * Get recent error reports
     */
    fun getRecentErrorReports(limit: Int = 50): List<ErrorReport> {
        return errorReports.values
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    /**
     * Get error reports for time range
     */
    fun getErrorReportsForTimeRange(startTime: Long, endTime: Long): List<ErrorReport> {
        return errorReports.values.filter { it.timestamp in startTime..endTime }
    }
    
    /**
     * Export error reports to file
     */
    suspend fun exportErrorReports(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val allReports = errorReports.values.toList()
            val jsonArray = org.json.JSONArray()
            
            allReports.forEach { report ->
                val jsonObject = JSONObject().apply {
                    put("id", report.id)
                    put("timestamp", report.timestamp)
                    put("app_version", report.appVersion)
                    
                    // Error info
                    val errorJson = JSONObject().apply {
                        put("id", report.errorInfo.id)
                        put("error_type", report.errorInfo.errorType.name)
                        put("severity", report.errorInfo.severity.name)
                        put("message", report.errorInfo.message)
                        put("stack_trace", report.errorInfo.stackTrace)
                        put("user_action", report.errorInfo.userAction)
                    }
                    put("error_info", errorJson)
                    
                    // Device info
                    val deviceJson = JSONObject().apply {
                        put("manufacturer", report.deviceInfo.manufacturer)
                        put("model", report.deviceInfo.model)
                        put("android_version", report.deviceInfo.androidVersion)
                        put("api_level", report.deviceInfo.apiLevel)
                        put("screen_resolution", report.deviceInfo.screenResolution)
                        put("is_emulator", report.deviceInfo.isEmulator)
                        
                        val memoryJson = JSONObject().apply {
                            put("total_memory", report.deviceInfo.memoryInfo.totalMemory)
                            put("available_memory", report.deviceInfo.memoryInfo.availableMemory)
                            put("memory_usage_percent", report.deviceInfo.memoryInfo.memoryUsagePercent)
                        }
                        put("memory_info", memoryJson)
                    }
                    put("device_info", deviceJson)
                    
                    // User info
                    report.userInfo?.let { user ->
                        val userJson = JSONObject().apply {
                            put("user_id", user.userId)
                            put("session_id", user.sessionId)
                            put("language", user.language)
                            put("timezone", user.timezone)
                            put("app_usage_time", user.appUsageTime)
                        }
                        put("user_info", userJson)
                    }
                    
                    // Session info
                    val sessionJson = JSONObject().apply {
                        put("session_id", report.sessionInfo.sessionId)
                        put("session_start_time", report.sessionInfo.sessionStartTime)
                        put("session_duration", report.sessionInfo.sessionDuration)
                        put("error_count", report.sessionInfo.errorCount)
                        
                        val screenViewsArray = org.json.JSONArray()
                        report.sessionInfo.screenViews.forEach { screenView ->
                            screenViewsArray.put(screenView)
                        }
                        put("screen_views", screenViewsArray)
                        
                        val userActionsArray = org.json.JSONArray()
                        report.sessionInfo.userActions.forEach { userAction ->
                            userActionsArray.put(userAction)
                        }
                        put("user_actions", userActionsArray)
                    }
                    put("session_info", sessionJson)
                    
                    // Additional context
                    if (report.additionalContext.isNotEmpty()) {
                        val contextJson = JSONObject()
                        report.additionalContext.forEach { (key, value) ->
                            contextJson.put(key, value.toString())
                        }
                        put("additional_context", contextJson)
                    }
                }
                jsonArray.put(jsonObject)
            }
            
            outputFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error exporting error reports", throwable = e)
            false
        }
    }
    
    /**
     * Clear all error reports
     */
    fun clearErrorReports() {
        errorReports.clear()
        pendingReports.clear()
        reportCounter.set(0)
        
        reporterScope.launch {
            try {
                errorReportDir.listFiles()?.forEach { it.delete() }
            } catch (e: Exception) {
                structuredLogger.e(TAG, "Error clearing error reports", throwable = e)
            }
        }
    }
    
    /**
     * Enable/disable error reporting
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
    }
    
    /**
     * Force process pending reports
     */
    suspend fun processPendingReports() = withContext(Dispatchers.IO) {
        if (pendingReports.isEmpty()) return@withContext
        
        try {
            isReporting.set(true)
            
            // Process reports in batches
            val reportsToProcess = pendingReports.take(BATCH_SIZE)
            pendingReports.removeAll(reportsToProcess)
            
            // Send reports to development team
            sendReportsToDevelopers(reportsToProcess)
            
            structuredLogger.i(TAG, "Processed ${reportsToProcess.size} error reports")
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error processing pending reports", throwable = e)
        } finally {
            isReporting.set(false)
        }
    }
    
    /**
     * Create error report from error info
     */
    private fun createErrorReport(errorInfo: ErrorHandler.ErrorInfo): ErrorReport {
        val appVersion = getAppVersion()
        val deviceInfo = getDeviceInfo()
        val userInfo = getCurrentUserInfo()
        val sessionInfo = getCurrentSessionInfo()
        
        return ErrorReport(
            id = generateReportId(),
            timestamp = System.currentTimeMillis(),
            errorInfo = errorInfo,
            appVersion = appVersion,
            deviceInfo = deviceInfo,
            userInfo = userInfo,
            sessionInfo = sessionInfo,
            additionalContext = mapOf(
                "report_generated_at" to System.currentTimeMillis(),
                "report_version" to "1.0"
            )
        )
    }
    
    /**
     * Get app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Get device information
     */
    private fun getDeviceInfo(): DeviceInfo {
        val runtime = Runtime.getRuntime()
        val displayMetrics = context.resources.displayMetrics
        
        return DeviceInfo(
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            apiLevel = android.os.Build.VERSION.SDK_INT,
            screenResolution = "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}",
            memoryInfo = MemoryInfo(
                totalMemory = runtime.totalMemory(),
                availableMemory = runtime.freeMemory(),
                memoryUsagePercent = ((runtime.totalMemory() - runtime.freeMemory()).toFloat() / runtime.totalMemory())
            ),
            isEmulator = isEmulator()
        )
    }
    
    /**
     * Get current user information
     */
    private fun getCurrentUserInfo(): UserInfo? {
        return UserInfo(
            userId = null, // Implement user ID retrieval
            sessionId = "session_${System.currentTimeMillis()}",
            language = Locale.getDefault().language,
            timezone = TimeZone.getDefault().id,
            appUsageTime = 0L // Implement app usage time tracking
        )
    }
    
    /**
     * Get current session information
     */
    private fun getCurrentSessionInfo(): SessionInfo {
        return SessionInfo(
            sessionId = "session_${System.currentTimeMillis()}",
            sessionStartTime = System.currentTimeMillis() - (30 * 60 * 1000L), // 30 minutes ago
            sessionDuration = 30 * 60 * 1000L, // 30 minutes
            screenViews = listOf("main", "translation", "settings"),
            userActions = listOf("translate", "change_language", "open_settings"),
            errorCount = 1
        )
    }
    
    /**
     * Check if running on emulator
     */
    private fun isEmulator(): Boolean {
        return android.os.Build.FINGERPRINT.startsWith("generic") ||
                android.os.Build.FINGERPRINT.startsWith("unknown") ||
                android.os.Build.MODEL.contains("google_sdk") ||
                android.os.Build.MODEL.contains("Emulator") ||
                android.os.Build.MODEL.contains("Android SDK built for x86") ||
                android.os.Build.MANUFACTURER.contains("Genymotion") ||
                (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic")) ||
                "google_sdk" == android.os.Build.PRODUCT
    }
    
    /**
     * Calculate error trend
     */
    private fun calculateErrorTrend(reports: List<ErrorReport>): ErrorTrend {
        if (reports.size < 2) return ErrorTrend.STABLE
        
        val sortedReports = reports.sortedBy { it.timestamp }
        val midPoint = sortedReports.size / 2
        
        val firstHalf = sortedReports.take(midPoint).size
        val secondHalf = sortedReports.drop(midPoint).size
        
        return when {
            secondHalf > firstHalf * 1.2 -> ErrorTrend.INCREASING
            firstHalf > secondHalf * 1.2 -> ErrorTrend.DECREASING
            else -> ErrorTrend.STABLE
        }
    }
    
    /**
     * Send reports to development team
     */
    private suspend fun sendReportsToDevelopers(reports: List<ErrorReport>) = withContext(Dispatchers.IO) {
        try {
            // In a real implementation, this would send reports to:
            // - Crash reporting service (Firebase Crashlytics, Bugsnag, etc.)
            // - Analytics service (Google Analytics, Mixpanel, etc.)
            // - Custom error tracking endpoint
            // - Email notifications for critical errors
            
            structuredLogger.i(TAG, "Sending ${reports.size} error reports to developers")
            
            // Simulate sending reports
            delay(1000)
            
            // Log critical errors
            reports.filter { it.errorInfo.severity == ErrorHandler.ErrorSeverity.CRITICAL }
                .forEach { report ->
                    structuredLogger.f(
                        "CRITICAL_ERROR_REPORT",
                        "Critical error reported: ${report.errorInfo.message}",
                        mapOf(
                            "error_report_id" to report.id,
                            "error_type" to report.errorInfo.errorType.name,
                            "device_model" to report.deviceInfo.model,
                            "android_version" to report.deviceInfo.androidVersion
                        )
                    )
                }
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error sending reports to developers", throwable = e)
        }
    }
    
    /**
     * Start periodic reporting
     */
    private fun startPeriodicReporting() {
        reporterScope.launch {
            while (isEnabled.get()) {
                try {
                    if (pendingReports.isNotEmpty()) {
                        processPendingReports()
                    }
                } catch (e: Exception) {
                    structuredLogger.e(TAG, "Error in periodic reporting", throwable = e)
                }
                
                delay(REPORT_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Save error report to file
     */
    private suspend fun saveErrorReport(report: ErrorReport) = withContext(Dispatchers.IO) {
        try {
            val fileName = "error_report_${report.id}.json"
            val file = File(errorReportDir, fileName)
            
            val jsonObject = JSONObject().apply {
                put("id", report.id)
                put("timestamp", report.timestamp)
                put("app_version", report.appVersion)
                
                // Error info
                val errorJson = JSONObject().apply {
                    put("id", report.errorInfo.id)
                    put("error_type", report.errorInfo.errorType.name)
                    put("severity", report.errorInfo.severity.name)
                    put("message", report.errorInfo.message)
                    put("stack_trace", report.errorInfo.stackTrace)
                    put("user_action", report.errorInfo.userAction)
                }
                put("error_info", errorJson)
                
                // Device info
                val deviceJson = JSONObject().apply {
                    put("manufacturer", report.deviceInfo.manufacturer)
                    put("model", report.deviceInfo.model)
                    put("android_version", report.deviceInfo.androidVersion)
                    put("api_level", report.deviceInfo.apiLevel)
                    put("screen_resolution", report.deviceInfo.screenResolution)
                    put("is_emulator", report.deviceInfo.isEmulator)
                    
                    val memoryJson = JSONObject().apply {
                        put("total_memory", report.deviceInfo.memoryInfo.totalMemory)
                        put("available_memory", report.deviceInfo.memoryInfo.availableMemory)
                        put("memory_usage_percent", report.deviceInfo.memoryInfo.memoryUsagePercent)
                    }
                    put("memory_info", memoryJson)
                }
                put("device_info", deviceJson)
                
                // User info
                report.userInfo?.let { user ->
                    val userJson = JSONObject().apply {
                        put("user_id", user.userId)
                        put("session_id", user.sessionId)
                        put("language", user.language)
                        put("timezone", user.timezone)
                        put("app_usage_time", user.appUsageTime)
                    }
                    put("user_info", userJson)
                }
                
                // Session info
                val sessionJson = JSONObject().apply {
                    put("session_id", report.sessionInfo.sessionId)
                    put("session_start_time", report.sessionInfo.sessionStartTime)
                    put("session_duration", report.sessionInfo.sessionDuration)
                    put("error_count", report.sessionInfo.errorCount)
                    
                    val screenViewsArray = org.json.JSONArray()
                    report.sessionInfo.screenViews.forEach { screenView ->
                        screenViewsArray.put(screenView)
                    }
                    put("screen_views", screenViewsArray)
                    
                    val userActionsArray = org.json.JSONArray()
                    report.sessionInfo.userActions.forEach { userAction ->
                        userActionsArray.put(userAction)
                    }
                    put("user_actions", userActionsArray)
                }
                put("session_info", sessionJson)
                
                // Additional context
                if (report.additionalContext.isNotEmpty()) {
                    val contextJson = JSONObject()
                    report.additionalContext.forEach { (key, value) ->
                        contextJson.put(key, value.toString())
                    }
                    put("additional_context", contextJson)
                }
            }
            
            file.writeText(jsonObject.toString(2))
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error saving error report", throwable = e)
        }
    }
    
    /**
     * Load existing error reports from files
     */
    private suspend fun loadExistingErrorReports() = withContext(Dispatchers.IO) {
        try {
            errorReportDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("error_report_") && file.name.endsWith(".json")) {
                    try {
                        val jsonString = file.readText()
                        val jsonObject = JSONObject(jsonString)
                        
                        // Create a simplified error report for loading
                        val errorInfo = ErrorHandler.ErrorInfo(
                            id = jsonObject.getJSONObject("error_info").getString("id"),
                            timestamp = jsonObject.getLong("timestamp"),
                            errorType = ErrorHandler.ErrorType.valueOf(jsonObject.getJSONObject("error_info").getString("error_type")),
                            severity = ErrorHandler.ErrorSeverity.valueOf(jsonObject.getJSONObject("error_info").getString("severity")),
                            message = jsonObject.getJSONObject("error_info").getString("message"),
                            throwable = null,
                            context = emptyMap(),
                            stackTrace = jsonObject.getJSONObject("error_info").getString("stack_trace"),
                            userAction = jsonObject.getJSONObject("error_info").optString("user_action").takeIf { it.isNotEmpty() }
                        )
                        
                        val report = ErrorReport(
                            id = jsonObject.getString("id"),
                            timestamp = jsonObject.getLong("timestamp"),
                            errorInfo = errorInfo,
                            appVersion = jsonObject.getString("app_version"),
                            deviceInfo = getDeviceInfo(), // Simplified for loading
                            userInfo = null, // Simplified for loading
                            sessionInfo = getCurrentSessionInfo(), // Simplified for loading
                            additionalContext = emptyMap()
                        )
                        
                        errorReports[report.id] = report
                    } catch (e: Exception) {
                        structuredLogger.e(TAG, "Error loading error report from file: ${file.name}", throwable = e)
                    }
                }
            }
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error loading existing error reports", throwable = e)
        }
    }
    
    /**
     * Generate unique report ID
     */
    private fun generateReportId(): String {
        return "report_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}"
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        reporterScope.cancel()
    }
}

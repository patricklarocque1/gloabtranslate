package com.example.gloabtranslate.core.logging

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import com.example.gloabtranslate.core.BuildConfig

/**
 * Crash reporting and error tracking system
 * Captures and reports application crashes and critical errors
 */
class CrashReporter private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "CrashReporter"
        private const val CRASH_REPORT_DIR = "crash_reports"
        private const val MAX_CRASH_REPORTS = 50
        private const val CRASH_REPORT_RETENTION_DAYS = 30
        
        @Volatile
        private var INSTANCE: CrashReporter? = null
        
        fun getInstance(context: Context): CrashReporter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CrashReporter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    data class CrashReport(
        val id: String,
        val timestamp: Long,
        val exception: Throwable,
        val context: Map<String, Any>,
        val deviceInfo: DeviceInfo,
        val appInfo: AppInfo,
        val userInfo: UserInfo? = null
    )
    
    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val totalMemory: Long,
        val availableMemory: Long,
        val isEmulator: Boolean,
        val screenResolution: String,
        val density: Float
    )
    
    data class AppInfo(
        val versionName: String,
        val versionCode: Long,
        val packageName: String,
        val buildType: String,
        val installTime: Long,
        val lastUpdateTime: Long
    )
    
    data class UserInfo(
        val userId: String?,
        val sessionId: String?,
        val language: String,
        val timezone: String
    )
    
    private val structuredLogger = StructuredLogger.getInstance(context)
    private val crashReportDir = File(context.filesDir, CRASH_REPORT_DIR)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val crashReports = mutableListOf<CrashReport>()
    private val isEnabled = AtomicBoolean(true)
    
    // Coroutine scope for background operations
    private val reporterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Create crash report directory
        if (!crashReportDir.exists()) {
            crashReportDir.mkdirs()
        }
        
        // Load existing crash reports
        reporterScope.launch {
            loadExistingCrashReports()
        }
    }
    
    /**
     * Report a crash
     */
    fun reportCrash(
        exception: Throwable,
        context: Map<String, Any> = emptyMap(),
        userInfo: UserInfo? = null
    ) {
        if (!isEnabled.get()) return
        
        try {
            val crashReport = CrashReport(
                id = generateCrashId(),
                timestamp = System.currentTimeMillis(),
                exception = exception,
                context = context,
                deviceInfo = getDeviceInfo(),
                appInfo = getAppInfo(),
                userInfo = userInfo ?: getCurrentUserInfo()
            )
            
            // Add to memory
            crashReports.add(crashReport)
            
            // Log the crash
            structuredLogger.f(
                "CRASH_REPORT",
                "Crash reported: ${exception.javaClass.simpleName}",
                mapOf(
                    "crash_id" to crashReport.id,
                    "exception_message" to (exception.message ?: "Unknown error"),
                    "stack_trace" to exception.stackTraceToString()
                ),
                exception
            )
            
            // Save to file asynchronously
            reporterScope.launch {
                saveCrashReport(crashReport)
            }
            
            // Clean up old reports
            if (crashReports.size > MAX_CRASH_REPORTS) {
                reporterScope.launch {
                    cleanupOldCrashReports()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting crash", e)
        }
    }
    
    /**
     * Report a non-fatal error
     */
    fun reportError(
        error: String,
        context: Map<String, Any> = emptyMap(),
        userInfo: UserInfo? = null
    ) {
        if (!isEnabled.get()) return
        
        try {
            val exception = RuntimeException(error)
            reportCrash(exception, context, userInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting non-fatal error", e)
        }
    }
    
    /**
     * Report a performance issue
     */
    fun reportPerformanceIssue(
        operation: String,
        durationMs: Long,
        thresholdMs: Long,
        context: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get()) return
        
        try {
            val performanceContext = context + mapOf(
                "operation" to operation,
                "duration_ms" to durationMs,
                "threshold_ms" to thresholdMs,
                "performance_issue" to true
            )
            
            val error = "Performance issue: $operation took ${durationMs}ms (threshold: ${thresholdMs}ms)"
            reportError(error, performanceContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting performance issue", e)
        }
    }
    
    /**
     * Report a memory issue
     */
    fun reportMemoryIssue(
        currentMemory: Long,
        maxMemory: Long,
        context: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get()) return
        
        try {
            val memoryContext = context + mapOf(
                "current_memory" to currentMemory,
                "max_memory" to maxMemory,
                "memory_usage_percent" to ((currentMemory.toFloat() / maxMemory) * 100),
                "memory_issue" to true
            )
            
            val error = "Memory issue: ${currentMemory}MB used of ${maxMemory}MB"
            reportError(error, memoryContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting memory issue", e)
        }
    }
    
    /**
     * Get all crash reports
     */
    fun getCrashReports(): List<CrashReport> {
        return crashReports.toList()
    }
    
    /**
     * Get crash reports by date range
     */
    fun getCrashReports(startTime: Long, endTime: Long): List<CrashReport> {
        return crashReports.filter { it.timestamp in startTime..endTime }
    }
    
    /**
     * Get crash reports by exception type
     */
    fun getCrashReportsByException(exceptionClass: Class<out Throwable>): List<CrashReport> {
        return crashReports.filter { it.exception.javaClass == exceptionClass }
    }
    
    /**
     * Clear all crash reports
     */
    fun clearCrashReports() {
        crashReports.clear()
        
        reporterScope.launch {
            try {
                crashReportDir.listFiles()?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing crash reports", e)
            }
        }
    }
    
    /**
     * Export crash reports to file
     */
    suspend fun exportCrashReports(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonArray = org.json.JSONArray()
            
            crashReports.forEach { report ->
                val jsonObject = JSONObject().apply {
                    put("id", report.id)
                    put("timestamp", report.timestamp)
                    put("exception_class", report.exception.javaClass.simpleName)
                    put("exception_message", report.exception.message ?: "Unknown error")
                    put("stack_trace", report.exception.stackTraceToString())
                    
                    // Device info
                    val deviceJson = JSONObject().apply {
                        put("manufacturer", report.deviceInfo.manufacturer)
                        put("model", report.deviceInfo.model)
                        put("android_version", report.deviceInfo.androidVersion)
                        put("api_level", report.deviceInfo.apiLevel)
                        put("total_memory", report.deviceInfo.totalMemory)
                        put("available_memory", report.deviceInfo.availableMemory)
                        put("is_emulator", report.deviceInfo.isEmulator)
                        put("screen_resolution", report.deviceInfo.screenResolution)
                        put("density", report.deviceInfo.density)
                    }
                    put("device_info", deviceJson)
                    
                    // App info
                    val appJson = JSONObject().apply {
                        put("version_name", report.appInfo.versionName)
                        put("version_code", report.appInfo.versionCode)
                        put("package_name", report.appInfo.packageName)
                        put("build_type", report.appInfo.buildType)
                        put("install_time", report.appInfo.installTime)
                        put("last_update_time", report.appInfo.lastUpdateTime)
                    }
                    put("app_info", appJson)
                    
                    // User info
                    report.userInfo?.let { user ->
                        val userJson = JSONObject().apply {
                            put("user_id", user.userId)
                            put("session_id", user.sessionId)
                            put("language", user.language)
                            put("timezone", user.timezone)
                        }
                        put("user_info", userJson)
                    }
                    
                    // Context
                    if (report.context.isNotEmpty()) {
                        val contextJson = JSONObject()
                        report.context.forEach { (key, value) ->
                            contextJson.put(key, value.toString())
                        }
                        put("context", contextJson)
                    }
                }
                jsonArray.put(jsonObject)
            }
            
            outputFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting crash reports", e)
            false
        }
    }
    
    /**
     * Enable/disable crash reporting
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
    }
    
    /**
     * Get device information
     */
    private fun getDeviceInfo(): DeviceInfo {
        val runtime = Runtime.getRuntime()
        val displayMetrics = context.resources.displayMetrics
        
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            totalMemory = runtime.totalMemory(),
            availableMemory = runtime.freeMemory(),
            isEmulator = isEmulator(),
            screenResolution = "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}",
            density = displayMetrics.density
        )
    }
    
    /**
     * Get application information
     */
    private fun getAppInfo(): AppInfo {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            AppInfo(
                versionName = packageInfo.versionName ?: "Unknown",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                },
                packageName = context.packageName,
                buildType = if (BuildConfig.DEBUG) "debug" else "release",
                installTime = packageInfo.firstInstallTime,
                lastUpdateTime = packageInfo.lastUpdateTime
            )
        } catch (e: PackageManager.NameNotFoundException) {
            AppInfo(
                versionName = "Unknown",
                versionCode = 0,
                packageName = context.packageName,
                buildType = "unknown",
                installTime = 0,
                lastUpdateTime = 0
            )
        }
    }
    
    /**
     * Get current user information
     */
    private fun getCurrentUserInfo(): UserInfo {
        return UserInfo(
            userId = null, // Implement user ID retrieval
            sessionId = null, // Implement session ID retrieval
            language = Locale.getDefault().language,
            timezone = TimeZone.getDefault().id
        )
    }
    
    /**
     * Check if running on emulator
     */
    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                "google_sdk" == Build.PRODUCT
    }
    
    /**
     * Generate unique crash ID
     */
    private fun generateCrashId(): String {
        return "crash_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}"
    }
    
    /**
     * Save crash report to file
     */
    private suspend fun saveCrashReport(crashReport: CrashReport) = withContext(Dispatchers.IO) {
        try {
            val fileName = "crash_${crashReport.id}.json"
            val file = File(crashReportDir, fileName)
            
            val jsonObject = JSONObject().apply {
                put("id", crashReport.id)
                put("timestamp", crashReport.timestamp)
                put("exception_class", crashReport.exception.javaClass.simpleName)
                put("exception_message", crashReport.exception.message ?: "Unknown error")
                put("stack_trace", crashReport.exception.stackTraceToString())
                
                // Device info
                val deviceJson = JSONObject().apply {
                    put("manufacturer", crashReport.deviceInfo.manufacturer)
                    put("model", crashReport.deviceInfo.model)
                    put("android_version", crashReport.deviceInfo.androidVersion)
                    put("api_level", crashReport.deviceInfo.apiLevel)
                    put("total_memory", crashReport.deviceInfo.totalMemory)
                    put("available_memory", crashReport.deviceInfo.availableMemory)
                    put("is_emulator", crashReport.deviceInfo.isEmulator)
                    put("screen_resolution", crashReport.deviceInfo.screenResolution)
                    put("density", crashReport.deviceInfo.density)
                }
                put("device_info", deviceJson)
                
                // App info
                val appJson = JSONObject().apply {
                    put("version_name", crashReport.appInfo.versionName)
                    put("version_code", crashReport.appInfo.versionCode)
                    put("package_name", crashReport.appInfo.packageName)
                    put("build_type", crashReport.appInfo.buildType)
                    put("install_time", crashReport.appInfo.installTime)
                    put("last_update_time", crashReport.appInfo.lastUpdateTime)
                }
                put("app_info", appJson)
                
                // User info
                crashReport.userInfo?.let { user ->
                    val userJson = JSONObject().apply {
                        put("user_id", user.userId)
                        put("session_id", user.sessionId)
                        put("language", user.language)
                        put("timezone", user.timezone)
                    }
                    put("user_info", userJson)
                }
                
                // Context
                if (crashReport.context.isNotEmpty()) {
                    val contextJson = JSONObject()
                    crashReport.context.forEach { (key, value) ->
                        contextJson.put(key, value.toString())
                    }
                    put("context", contextJson)
                }
            }
            
            file.writeText(jsonObject.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving crash report", e)
        }
    }
    
    /**
     * Load existing crash reports from files
     */
    private suspend fun loadExistingCrashReports() = withContext(Dispatchers.IO) {
        try {
            crashReportDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("crash_") && file.name.endsWith(".json")) {
                    try {
                        val jsonString = file.readText()
                        val jsonObject = JSONObject(jsonString)
                        
                        // Create a mock exception for loading
                        val exception = RuntimeException(jsonObject.optString("exception_message", "Unknown error"))
                        
                        val crashReport = CrashReport(
                            id = jsonObject.getString("id"),
                            timestamp = jsonObject.getLong("timestamp"),
                            exception = exception,
                            context = emptyMap(), // Simplified for loading
                            deviceInfo = getDeviceInfo(), // Simplified for loading
                            appInfo = getAppInfo(), // Simplified for loading
                            userInfo = null
                        )
                        
                        crashReports.add(crashReport)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading crash report from file: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading existing crash reports", e)
        }
    }
    
    /**
     * Clean up old crash reports
     */
    private suspend fun cleanupOldCrashReports() = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (CRASH_REPORT_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
            
            // Remove old reports from memory
            crashReports.removeAll { it.timestamp < cutoffTime }
            
            // Remove old report files
            crashReportDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old crash reports", e)
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        reporterScope.cancel()
    }
}

package com.example.gloabtranslate.core.monitoring

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.gloabtranslate.core.logging.StructuredLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Performance monitoring system
 * Tracks app performance metrics, memory usage, and performance issues
 */
class PerformanceMonitor private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val MONITORING_INTERVAL_MS = 5000L // 5 seconds
        private const val MEMORY_WARNING_THRESHOLD = 0.8f // 80% memory usage
        private const val MEMORY_CRITICAL_THRESHOLD = 0.9f // 90% memory usage
        private const val CPU_WARNING_THRESHOLD = 0.8f // 80% CPU usage
        private const val PERFORMANCE_WARNING_THRESHOLD_MS = 1000L // 1 second
        private const val PERFORMANCE_CRITICAL_THRESHOLD_MS = 5000L // 5 seconds
        
        @Volatile
        private var INSTANCE: PerformanceMonitor? = null
        
        fun getInstance(context: Context): PerformanceMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PerformanceMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    data class PerformanceMetrics(
        val timestamp: Long,
        val memoryUsage: MemoryUsage,
        val cpuUsage: CpuUsage,
        val frameRate: FrameRate,
        val networkLatency: NetworkLatency?,
        val batteryLevel: Int,
        val thermalState: Int
    )
    
    data class MemoryUsage(
        val totalMemory: Long,
        val usedMemory: Long,
        val freeMemory: Long,
        val memoryUsagePercent: Float,
        val heapSize: Long,
        val heapUsed: Long,
        val heapFree: Long,
        val nativeHeapSize: Long,
        val nativeHeapUsed: Long,
        val nativeHeapFree: Long
    )
    
    data class CpuUsage(
        val cpuUsagePercent: Float,
        val cpuCores: Int,
        val cpuFrequency: Long,
        val loadAverage: Float
    )
    
    data class FrameRate(
        val currentFps: Float,
        val averageFps: Float,
        val minFps: Float,
        val maxFps: Float,
        val droppedFrames: Int
    )
    
    data class NetworkLatency(
        val averageLatency: Long,
        val minLatency: Long,
        val maxLatency: Long,
        val packetLoss: Float
    )
    
    data class PerformanceIssue(
        val type: IssueType,
        val severity: Severity,
        val timestamp: Long,
        val description: String,
        val metrics: PerformanceMetrics,
        val context: Map<String, Any>
    )
    
    enum class IssueType {
        MEMORY_WARNING,
        MEMORY_CRITICAL,
        CPU_HIGH,
        FRAME_RATE_LOW,
        NETWORK_LATENCY_HIGH,
        BATTERY_LOW,
        THERMAL_THROTTLING
    }
    
    enum class Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
    
    private val structuredLogger = StructuredLogger.getInstance(context)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val handler = Handler(Looper.getMainLooper())
    
    // Monitoring state
    private val isMonitoring = AtomicBoolean(false)
    private val monitoringJob: Job? = null
    private val performanceMetrics = ConcurrentHashMap<Long, PerformanceMetrics>()
    private val performanceIssues = mutableListOf<PerformanceIssue>()
    
    // Performance tracking
    private val operationDurations = ConcurrentHashMap<String, MutableList<Long>>()
    private val frameRateHistory = mutableListOf<Float>()
    private val networkLatencyHistory = mutableListOf<Long>()
    
    // Coroutine scope for background operations
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Start performance monitoring
     */
    fun startMonitoring() {
        if (isMonitoring.get()) return
        
        isMonitoring.set(true)
        structuredLogger.i(TAG, "Starting performance monitoring")
        
        monitorScope.launch {
            while (isMonitoring.get()) {
                try {
                    val metrics = collectPerformanceMetrics()
                    performanceMetrics[metrics.timestamp] = metrics
                    
                    // Check for performance issues
                    checkPerformanceIssues(metrics)
                    
                    // Log performance metrics
                    logPerformanceMetrics(metrics)
                    
                    // Clean up old metrics
                    cleanupOldMetrics()
                    
                } catch (e: Exception) {
                    structuredLogger.e(TAG, "Error collecting performance metrics", mapOf<String, Any>(
                        "error" to (e.message ?: "Unknown error"),
                        "exception" to e.javaClass.simpleName
                    ))
                }
                
                delay(MONITORING_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop performance monitoring
     */
    fun stopMonitoring() {
        if (!isMonitoring.get()) return
        
        isMonitoring.set(false)
        structuredLogger.i(TAG, "Stopping performance monitoring")
    }
    
    /**
     * Track operation performance
     */
    fun trackOperation(operationName: String, durationMs: Long) {
        operationDurations.getOrPut(operationName) { mutableListOf() }.add(durationMs)
        
        // Log performance
        structuredLogger.logPerformance(operationName, durationMs)
        
        // Check for performance issues
        if (durationMs > PERFORMANCE_WARNING_THRESHOLD_MS) {
            val severity = if (durationMs > PERFORMANCE_CRITICAL_THRESHOLD_MS) {
                Severity.HIGH
            } else {
                Severity.MEDIUM
            }
            
            reportPerformanceIssue(
                IssueType.CPU_HIGH,
                severity,
                "Operation $operationName took ${durationMs}ms",
                mapOf("operation" to operationName, "duration_ms" to durationMs)
            )
        }
    }
    
    /**
     * Track frame rate
     */
    fun trackFrameRate(fps: Float) {
        frameRateHistory.add(fps)
        
        // Keep only last 100 frame rate measurements
        if (frameRateHistory.size > 100) {
            frameRateHistory.removeAt(0)
        }
        
        // Check for low frame rate
        if (fps < 30f) {
            reportPerformanceIssue(
                IssueType.FRAME_RATE_LOW,
                Severity.MEDIUM,
                "Low frame rate detected: ${fps}fps",
                mapOf("fps" to fps)
            )
        }
    }
    
    /**
     * Track network latency
     */
    fun trackNetworkLatency(latencyMs: Long) {
        networkLatencyHistory.add(latencyMs)
        
        // Keep only last 100 latency measurements
        if (networkLatencyHistory.size > 100) {
            networkLatencyHistory.removeAt(0)
        }
        
        // Check for high latency
        if (latencyMs > 1000) { // 1 second
            reportPerformanceIssue(
                IssueType.NETWORK_LATENCY_HIGH,
                Severity.MEDIUM,
                "High network latency detected: ${latencyMs}ms",
                mapOf("latency_ms" to latencyMs)
            )
        }
    }
    
    /**
     * Get current performance metrics
     */
    fun getCurrentMetrics(): PerformanceMetrics? {
        return performanceMetrics.values.maxByOrNull { it.timestamp }
    }
    
    /**
     * Get performance metrics for time range
     */
    fun getMetricsForTimeRange(startTime: Long, endTime: Long): List<PerformanceMetrics> {
        return performanceMetrics.values.filter { it.timestamp in startTime..endTime }
    }
    
    /**
     * Get performance issues
     */
    fun getPerformanceIssues(): List<PerformanceIssue> {
        return performanceIssues.toList()
    }
    
    /**
     * Get performance issues by type
     */
    fun getPerformanceIssuesByType(type: IssueType): List<PerformanceIssue> {
        return performanceIssues.filter { it.type == type }
    }
    
    /**
     * Get operation performance statistics
     */
    fun getOperationStats(operationName: String): OperationStats? {
        val durations = operationDurations[operationName] ?: return null
        
        return OperationStats(
            operationName = operationName,
            totalCalls = durations.size,
            averageDuration = durations.average(),
            minDuration = durations.minOrNull() ?: 0L,
            maxDuration = durations.maxOrNull() ?: 0L,
            medianDuration = durations.sorted()[durations.size / 2]
        )
    }
    
    /**
     * Clear performance data
     */
    fun clearPerformanceData() {
        performanceMetrics.clear()
        performanceIssues.clear()
        operationDurations.clear()
        frameRateHistory.clear()
        networkLatencyHistory.clear()
    }
    
    /**
     * Collect current performance metrics
     */
    private suspend fun collectPerformanceMetrics(): PerformanceMetrics = withContext(Dispatchers.IO) {
        val memoryUsage = getMemoryUsage()
        val cpuUsage = getCpuUsage()
        val frameRate = getFrameRate()
        val networkLatency = getNetworkLatency()
        val batteryLevel = getBatteryLevel()
        val thermalState = getThermalState()
        
        PerformanceMetrics(
            timestamp = System.currentTimeMillis(),
            memoryUsage = memoryUsage,
            cpuUsage = cpuUsage,
            frameRate = frameRate,
            networkLatency = networkLatency,
            batteryLevel = batteryLevel,
            thermalState = thermalState
        )
    }
    
    /**
     * Get memory usage information
     */
    private fun getMemoryUsage(): MemoryUsage {
        val runtime = Runtime.getRuntime()
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalMemory = memoryInfo.totalMem
        val usedMemory = totalMemory - memoryInfo.availMem
        val freeMemory = memoryInfo.availMem
        val memoryUsagePercent = (usedMemory.toFloat() / totalMemory)
        
        val heapSize = runtime.totalMemory()
        val heapUsed = heapSize - runtime.freeMemory()
        val heapFree = runtime.freeMemory()
        
        val nativeHeapSize = Debug.getNativeHeapSize()
        val nativeHeapUsed = Debug.getNativeHeapAllocatedSize()
        val nativeHeapFree = nativeHeapSize - nativeHeapUsed
        
        return MemoryUsage(
            totalMemory = totalMemory,
            usedMemory = usedMemory,
            freeMemory = freeMemory,
            memoryUsagePercent = memoryUsagePercent,
            heapSize = heapSize,
            heapUsed = heapUsed,
            heapFree = heapFree,
            nativeHeapSize = nativeHeapSize,
            nativeHeapUsed = nativeHeapUsed,
            nativeHeapFree = nativeHeapFree
        )
    }
    
    /**
     * Get CPU usage information
     */
    private fun getCpuUsage(): CpuUsage {
        // Simplified CPU usage calculation
        // In a real implementation, you would use more sophisticated methods
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val cpuFrequency = 0L // Would need to read from /proc/cpuinfo
        val loadAverage = 0.0f // Would need to read from /proc/loadavg
        val cpuUsagePercent = 0.0f // Would need to calculate from /proc/stat
        
        return CpuUsage(
            cpuUsagePercent = cpuUsagePercent,
            cpuCores = cpuCores,
            cpuFrequency = cpuFrequency,
            loadAverage = loadAverage
        )
    }
    
    /**
     * Get frame rate information
     */
    private fun getFrameRate(): FrameRate {
        val currentFps = frameRateHistory.lastOrNull() ?: 0f
        val averageFps = if (frameRateHistory.isNotEmpty()) {
            frameRateHistory.average().toFloat()
        } else 0f
        val minFps = frameRateHistory.minOrNull() ?: 0f
        val maxFps = frameRateHistory.maxOrNull() ?: 0f
        val droppedFrames = 0 // Would need to track dropped frames
        
        return FrameRate(
            currentFps = currentFps,
            averageFps = averageFps,
            minFps = minFps,
            maxFps = maxFps,
            droppedFrames = droppedFrames
        )
    }
    
    /**
     * Get network latency information
     */
    private fun getNetworkLatency(): NetworkLatency? {
        if (networkLatencyHistory.isEmpty()) return null
        
        val averageLatency = networkLatencyHistory.average().toLong()
        val minLatency = networkLatencyHistory.minOrNull() ?: 0L
        val maxLatency = networkLatencyHistory.maxOrNull() ?: 0L
        val packetLoss = 0.0f // Would need to track packet loss
        
        return NetworkLatency(
            averageLatency = averageLatency,
            minLatency = minLatency,
            maxLatency = maxLatency,
            packetLoss = packetLoss
        )
    }
    
    /**
     * Get battery level
     */
    private fun getBatteryLevel(): Int {
        // Would need to implement battery level monitoring
        return 100
    }
    
    /**
     * Get thermal state
     */
    private fun getThermalState(): Int {
        // Would need to implement thermal state monitoring
        return 0
    }
    
    /**
     * Check for performance issues
     */
    private fun checkPerformanceIssues(metrics: PerformanceMetrics) {
        // Check memory usage
        if (metrics.memoryUsage.memoryUsagePercent > MEMORY_CRITICAL_THRESHOLD) {
            reportPerformanceIssue(
                IssueType.MEMORY_CRITICAL,
                Severity.CRITICAL,
                "Critical memory usage: ${(metrics.memoryUsage.memoryUsagePercent * 100).toInt()}%",
                mapOf("memory_usage_percent" to metrics.memoryUsage.memoryUsagePercent)
            )
        } else if (metrics.memoryUsage.memoryUsagePercent > MEMORY_WARNING_THRESHOLD) {
            reportPerformanceIssue(
                IssueType.MEMORY_WARNING,
                Severity.MEDIUM,
                "High memory usage: ${(metrics.memoryUsage.memoryUsagePercent * 100).toInt()}%",
                mapOf("memory_usage_percent" to metrics.memoryUsage.memoryUsagePercent)
            )
        }
        
        // Check CPU usage
        if (metrics.cpuUsage.cpuUsagePercent > CPU_WARNING_THRESHOLD) {
            reportPerformanceIssue(
                IssueType.CPU_HIGH,
                Severity.MEDIUM,
                "High CPU usage: ${(metrics.cpuUsage.cpuUsagePercent * 100).toInt()}%",
                mapOf("cpu_usage_percent" to metrics.cpuUsage.cpuUsagePercent)
            )
        }
        
        // Check frame rate
        if (metrics.frameRate.currentFps < 30f && metrics.frameRate.currentFps > 0f) {
            reportPerformanceIssue(
                IssueType.FRAME_RATE_LOW,
                Severity.MEDIUM,
                "Low frame rate: ${metrics.frameRate.currentFps}fps",
                mapOf("fps" to metrics.frameRate.currentFps)
            )
        }
        
        // Check battery level
        if (metrics.batteryLevel < 20) {
            reportPerformanceIssue(
                IssueType.BATTERY_LOW,
                Severity.LOW,
                "Low battery level: ${metrics.batteryLevel}%",
                mapOf("battery_level" to metrics.batteryLevel)
            )
        }
        
        // Check thermal state
        if (metrics.thermalState > 2) {
            reportPerformanceIssue(
                IssueType.THERMAL_THROTTLING,
                Severity.HIGH,
                "Thermal throttling detected",
                mapOf("thermal_state" to metrics.thermalState)
            )
        }
    }
    
    /**
     * Report a performance issue
     */
    private fun reportPerformanceIssue(
        type: IssueType,
        severity: Severity,
        description: String,
        context: Map<String, Any>
    ) {
        val issue = PerformanceIssue(
            type = type,
            severity = severity,
            timestamp = System.currentTimeMillis(),
            description = description,
            metrics = getCurrentMetrics() ?: return,
            context = context
        )
        
        performanceIssues.add(issue)
        
        // Log the issue
        val logLevel = when (severity) {
            Severity.LOW -> StructuredLogger.LogLevel.INFO
            Severity.MEDIUM -> StructuredLogger.LogLevel.WARN
            Severity.HIGH -> StructuredLogger.LogLevel.ERROR
            Severity.CRITICAL -> StructuredLogger.LogLevel.FATAL
        }
        
        structuredLogger.log(
            logLevel,
            "PERFORMANCE_ISSUE",
            description,
            context + mapOf("issue_type" to type.name, "severity" to severity.name)
        )
    }
    
    /**
     * Log performance metrics
     */
    private fun logPerformanceMetrics(metrics: PerformanceMetrics) {
        val metadata = mapOf(
            "memory_usage_percent" to metrics.memoryUsage.memoryUsagePercent,
            "cpu_usage_percent" to metrics.cpuUsage.cpuUsagePercent,
            "fps" to metrics.frameRate.currentFps,
            "battery_level" to metrics.batteryLevel,
            "thermal_state" to metrics.thermalState
        )
        
        structuredLogger.log(
            StructuredLogger.LogLevel.DEBUG,
            "PERFORMANCE_METRICS",
            "Performance metrics collected",
            metadata
        )
    }
    
    /**
     * Clean up old metrics
     */
    private fun cleanupOldMetrics() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L) // 24 hours
        
        // Remove old metrics
        performanceMetrics.keys.removeAll { it < cutoffTime }
        
        // Remove old issues
        performanceIssues.removeAll { it.timestamp < cutoffTime }
        
        // Clean up operation durations (keep only last 1000 entries per operation)
        operationDurations.values.forEach { durations ->
            if (durations.size > 1000) {
                durations.removeAll { true }
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopMonitoring()
        monitorScope.cancel()
    }
    
    /**
     * Data class for operation statistics
     */
    data class OperationStats(
        val operationName: String,
        val totalCalls: Int,
        val averageDuration: Double,
        val minDuration: Long,
        val maxDuration: Long,
        val medianDuration: Long
    )
}

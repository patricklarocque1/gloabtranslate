package com.example.gloabtranslate.core.resources

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

/**
 * Advanced resource monitoring and logging system for Android applications.
 * Tracks resource usage, performance metrics, and provides detailed logging.
 */
class ResourceMonitor private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ResourceMonitor"
        
        // Monitoring intervals
        private const val MONITORING_INTERVAL_MS = 5000L // 5 seconds
        private const val DETAILED_MONITORING_INTERVAL_MS = 30000L // 30 seconds
        private const val LOGGING_INTERVAL_MS = 60000L // 1 minute
        
        // Resource thresholds
        private const val MEMORY_WARNING_THRESHOLD = 0.7f
        private const val MEMORY_CRITICAL_THRESHOLD = 0.9f
        private const val CPU_WARNING_THRESHOLD = 70.0
        private const val CPU_CRITICAL_THRESHOLD = 90.0
        private const val BATTERY_WARNING_THRESHOLD = 20
        private const val BATTERY_CRITICAL_THRESHOLD = 10
        
        // Logging levels
        private const val LOG_LEVEL_DEBUG = 0
        private const val LOG_LEVEL_INFO = 1
        private const val LOG_LEVEL_WARN = 2
        private const val LOG_LEVEL_ERROR = 3
        
        @Volatile
        private var INSTANCE: ResourceMonitor? = null
        
        fun getInstance(context: Context): ResourceMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResourceMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    // Monitoring state
    private val isMonitoring = AtomicBoolean(false)
    private val monitoringStartTime = AtomicLong(0L)
    private val totalSamples = AtomicLong(0L)
    
    // Resource metrics
    private val memoryUsage = AtomicLong(0L)
    private val peakMemoryUsage = AtomicLong(0L)
    private val cpuUsage = AtomicReference(0.0)
    private val peakCpuUsage = AtomicReference(0.0)
    private val batteryLevel = AtomicInteger(100)
    
    // Performance metrics
    private val averageResponseTime = AtomicLong(0L)
    private val peakResponseTime = AtomicLong(0L)
    private val totalRequests = AtomicLong(0L)
    private val failedRequests = AtomicLong(0L)
    
    // Resource tracking
    private val resourceUsage = ConcurrentHashMap<String, ResourceUsageData>()
    private val performanceMetrics = ConcurrentHashMap<String, PerformanceMetric>()
    private val alertHistory = CopyOnWriteArrayList<ResourceAlert>()
    
    // Monitoring scope
    private val monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringJob: Job? = null
    private var detailedMonitoringJob: Job? = null
    private var loggingJob: Job? = null
    
    // State flows
    private val _resourceStateFlow = MutableStateFlow(ResourceState.NORMAL)
    val resourceStateFlow: Flow<ResourceState> = _resourceStateFlow.asStateFlow()
    
    private val _memoryUsageFlow = MutableStateFlow(0L)
    val memoryUsageFlow: Flow<Long> = _memoryUsageFlow.asStateFlow()
    
    private val _cpuUsageFlow = MutableStateFlow(0.0)
    val cpuUsageFlow: Flow<Double> = _cpuUsageFlow.asStateFlow()
    
    private val _batteryLevelFlow = MutableStateFlow(100)
    val batteryLevelFlow: Flow<Int> = _batteryLevelFlow.asStateFlow()
    
    /**
     * Resource state enumeration
     */
    enum class ResourceState {
        NORMAL,         // All resources within normal limits
        WARNING,        // Some resources approaching limits
        CRITICAL,       // Resources at critical levels
        EMERGENCY       // Emergency resource situation
    }
    
    /**
     * Alert severity levels
     */
    enum class AlertSeverity {
        INFO,           // Informational
        WARNING,        // Warning level
        ERROR,          // Error level
        CRITICAL        // Critical level
    }
    
    /**
     * Resource usage data
     */
    data class ResourceUsageData(
        val resourceName: String,
        val currentUsage: Long,
        val peakUsage: Long,
        val averageUsage: Long,
        val totalUsage: Long,
        val usageCount: Int,
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    /**
     * Performance metric data
     */
    data class PerformanceMetric(
        val metricName: String,
        val value: Double,
        val unit: String,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Resource alert data
     */
    data class ResourceAlert(
        val id: String,
        val severity: AlertSeverity,
        val resourceName: String,
        val message: String,
        val currentValue: Any,
        val threshold: Any,
        val timestamp: Long = System.currentTimeMillis(),
        val isResolved: Boolean = false
    )
    
    /**
     * Monitoring configuration
     */
    data class MonitoringConfig(
        val enableMemoryMonitoring: Boolean = true,
        val enableCpuMonitoring: Boolean = true,
        val enableBatteryMonitoring: Boolean = true,
        val enablePerformanceMonitoring: Boolean = true,
        val enableDetailedLogging: Boolean = true,
        val logLevel: Int = LOG_LEVEL_INFO,
        val alertThresholds: Map<String, Double> = emptyMap(),
        val monitoringIntervalMs: Long = MONITORING_INTERVAL_MS
    )
    
    /**
     * Monitoring statistics
     */
    data class MonitoringStatistics(
        val monitoringDuration: Long,
        val totalSamples: Long,
        val currentMemoryUsage: Long,
        val peakMemoryUsage: Long,
        val currentCpuUsage: Double,
        val peakCpuUsage: Double,
        val currentBatteryLevel: Int,
        val averageResponseTime: Long,
        val peakResponseTime: Long,
        val totalRequests: Long,
        val failedRequests: Long,
        val resourceState: ResourceState,
        val activeAlerts: Int,
        val totalAlerts: Int
    )
    
    init {
        startMonitoring()
    }
    
    /**
     * Starts resource monitoring
     */
    fun startMonitoring(config: MonitoringConfig = MonitoringConfig()) {
        if (isMonitoring.compareAndSet(false, true)) {
            monitoringStartTime.set(System.currentTimeMillis())
            startBasicMonitoring(config)
            startDetailedMonitoring(config)
            startLogging(config)
            Log.d(TAG, "Resource monitoring started")
        }
    }
    
    /**
     * Stops resource monitoring
     */
    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            monitoringJob?.cancel()
            detailedMonitoringJob?.cancel()
            loggingJob?.cancel()
            Log.d(TAG, "Resource monitoring stopped")
        }
    }
    
    /**
     * Records a resource usage event
     */
    fun recordResourceUsage(
        resourceName: String,
        usage: Long,
        metadata: Map<String, Any> = emptyMap()
    ) {
        try {
            val currentTime = System.currentTimeMillis()
            val existingData = resourceUsage[resourceName]
            
            val newData = if (existingData != null) {
                val newTotalUsage = existingData.totalUsage + usage
                val newUsageCount = existingData.usageCount + 1
                val newAverageUsage = newTotalUsage / newUsageCount
                
                existingData.copy(
                    currentUsage = usage,
                    peakUsage = maxOf(existingData.peakUsage, usage),
                    averageUsage = newAverageUsage,
                    totalUsage = newTotalUsage,
                    usageCount = newUsageCount,
                    lastUpdated = currentTime
                )
            } else {
                ResourceUsageData(
                    resourceName = resourceName,
                    currentUsage = usage,
                    peakUsage = usage,
                    averageUsage = usage,
                    totalUsage = usage,
                    usageCount = 1,
                    lastUpdated = currentTime
                )
            }
            
            resourceUsage[resourceName] = newData
            
            // Check for alerts
            checkResourceAlerts(resourceName, usage, metadata)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording resource usage for $resourceName", e)
        }
    }
    
    /**
     * Records a performance metric
     */
    fun recordPerformanceMetric(
        metricName: String,
        value: Double,
        unit: String = "",
        metadata: Map<String, Any> = emptyMap()
    ) {
        try {
            val metric = PerformanceMetric(
                metricName = metricName,
                value = value,
                unit = unit,
                metadata = metadata
            )
            
            performanceMetrics[metricName] = metric
            
            // Update response time if it's a response time metric
            if (metricName.contains("response_time", ignoreCase = true)) {
                updateResponseTime(value.toLong())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording performance metric $metricName", e)
        }
    }
    
    /**
     * Records a request (success or failure)
     */
    fun recordRequest(success: Boolean, responseTime: Long = 0L) {
        totalRequests.incrementAndGet()
        if (!success) {
            failedRequests.incrementAndGet()
        }
        if (responseTime > 0) {
            updateResponseTime(responseTime)
        }
    }
    
    /**
     * Gets current monitoring statistics
     */
    fun getMonitoringStatistics(): MonitoringStatistics {
        val monitoringDuration = System.currentTimeMillis() - monitoringStartTime.get()
        val activeAlerts = alertHistory.count { !it.isResolved }
        
        return MonitoringStatistics(
            monitoringDuration = monitoringDuration,
            totalSamples = totalSamples.get(),
            currentMemoryUsage = memoryUsage.get(),
            peakMemoryUsage = peakMemoryUsage.get(),
            currentCpuUsage = cpuUsage.get(),
            peakCpuUsage = peakCpuUsage.get(),
            currentBatteryLevel = batteryLevel.get(),
            averageResponseTime = averageResponseTime.get(),
            peakResponseTime = peakResponseTime.get(),
            totalRequests = totalRequests.get(),
            failedRequests = failedRequests.get(),
            resourceState = _resourceStateFlow.value,
            activeAlerts = activeAlerts,
            totalAlerts = alertHistory.size
        )
    }
    
    /**
     * Gets resource usage data
     */
    fun getResourceUsageData(): Map<String, ResourceUsageData> = resourceUsage.toMap()
    
    /**
     * Gets performance metrics
     */
    fun getPerformanceMetrics(): Map<String, PerformanceMetric> = performanceMetrics.toMap()
    
    /**
     * Gets active alerts
     */
    fun getActiveAlerts(): List<ResourceAlert> = alertHistory.filter { !it.isResolved }
    
    /**
     * Gets all alerts
     */
    fun getAllAlerts(): List<ResourceAlert> = alertHistory.toList()
    
    /**
     * Resolves an alert
     */
    fun resolveAlert(alertId: String): Boolean {
        val alert = alertHistory.find { it.id == alertId }
        return if (alert != null) {
            val updatedAlert = alert.copy(isResolved = true)
            alertHistory[alertHistory.indexOf(alert)] = updatedAlert
            true
        } else {
            false
        }
    }
    
    /**
     * Clears all alerts
     */
    fun clearAlerts() {
        alertHistory.clear()
    }
    
    /**
     * Exports monitoring data
     */
    fun exportMonitoringData(): String {
        val statistics = getMonitoringStatistics()
        val resourceData = getResourceUsageData()
        val performanceData = getPerformanceMetrics()
        val alerts = getAllAlerts()
        
        return buildString {
            appendLine("=== Resource Monitoring Report ===")
            appendLine("Generated: ${System.currentTimeMillis()}")
            appendLine("Monitoring Duration: ${statistics.monitoringDuration}ms")
            appendLine()
            
            appendLine("=== System Statistics ===")
            appendLine("Memory Usage: ${statistics.currentMemoryUsage} bytes (Peak: ${statistics.peakMemoryUsage})")
            appendLine("CPU Usage: ${statistics.currentCpuUsage}% (Peak: ${statistics.peakCpuUsage})")
            appendLine("Battery Level: ${statistics.currentBatteryLevel}%")
            appendLine("Resource State: ${statistics.resourceState}")
            appendLine()
            
            appendLine("=== Performance Metrics ===")
            appendLine("Average Response Time: ${statistics.averageResponseTime}ms")
            appendLine("Peak Response Time: ${statistics.peakResponseTime}ms")
            appendLine("Total Requests: ${statistics.totalRequests}")
            appendLine("Failed Requests: ${statistics.failedRequests}")
            appendLine()
            
            appendLine("=== Resource Usage ===")
            resourceData.forEach { (name, data) ->
                appendLine("$name: ${data.currentUsage} bytes (Avg: ${data.averageUsage}, Peak: ${data.peakUsage})")
            }
            appendLine()
            
            appendLine("=== Performance Data ===")
            performanceData.forEach { (name, metric) ->
                appendLine("$name: ${metric.value} ${metric.unit}")
            }
            appendLine()
            
            appendLine("=== Alerts ===")
            alerts.forEach { alert ->
                appendLine("${alert.severity}: ${alert.message} (${alert.resourceName})")
            }
        }
    }
    
    /**
     * Destroys the resource monitor
     */
    fun destroy() {
        stopMonitoring()
        monitoringScope.cancel()
    }
    
    // Private helper methods
    
    private fun startBasicMonitoring(config: MonitoringConfig) {
        monitoringJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                if (config.enableMemoryMonitoring) {
                    updateMemoryUsage()
                }
                if (config.enableCpuMonitoring) {
                    updateCpuUsage()
                }
                if (config.enableBatteryMonitoring) {
                    updateBatteryLevel()
                }
                
                updateResourceState()
                totalSamples.incrementAndGet()
                
                delay(config.monitoringIntervalMs)
            }
        }
    }
    
    private fun startDetailedMonitoring(config: MonitoringConfig) {
        detailedMonitoringJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                if (config.enablePerformanceMonitoring) {
                    updatePerformanceMetrics()
                }
                
                delay(DETAILED_MONITORING_INTERVAL_MS)
            }
        }
    }
    
    private fun startLogging(config: MonitoringConfig) {
        loggingJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                if (config.enableDetailedLogging) {
                    logMonitoringData(config.logLevel)
                }
                
                delay(LOGGING_INTERVAL_MS)
            }
        }
    }
    
    private fun updateMemoryUsage() {
        try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            val currentUsage = memInfo.totalMem - memInfo.availMem
            memoryUsage.set(currentUsage)
            peakMemoryUsage.updateAndGet { maxOf(it, currentUsage) }
            
            _memoryUsageFlow.value = currentUsage
            
            // Record memory usage
            recordResourceUsage("memory", currentUsage)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating memory usage", e)
        }
    }
    
    private fun updateCpuUsage() {
        try {
            // Simplified CPU usage calculation
            val cpuUsage = calculateCpuUsage()
            this.cpuUsage.set(cpuUsage)
            peakCpuUsage.updateAndGet { maxOf(it, cpuUsage) }
            
            _cpuUsageFlow.value = cpuUsage
            
            // Record CPU usage
            recordResourceUsage("cpu", cpuUsage.toLong())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating CPU usage", e)
        }
    }
    
    private fun updateBatteryLevel() {
        try {
            // Simplified battery level calculation
            val batteryLevel = getBatteryLevel()
            this.batteryLevel.set(batteryLevel)
            
            _batteryLevelFlow.value = batteryLevel
            
            // Record battery usage
            recordResourceUsage("battery", batteryLevel.toLong())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating battery level", e)
        }
    }
    
    private fun updatePerformanceMetrics() {
        try {
            // Update response time metrics
            recordPerformanceMetric("average_response_time", averageResponseTime.get().toDouble(), "ms")
            recordPerformanceMetric("peak_response_time", peakResponseTime.get().toDouble(), "ms")
            
            // Update request metrics
            val successRate = if (totalRequests.get() > 0) {
                ((totalRequests.get() - failedRequests.get()).toDouble() / totalRequests.get()) * 100.0
            } else 100.0
            
            recordPerformanceMetric("success_rate", successRate, "%")
            recordPerformanceMetric("total_requests", totalRequests.get().toDouble(), "count")
            recordPerformanceMetric("failed_requests", failedRequests.get().toDouble(), "count")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating performance metrics", e)
        }
    }
    
    private fun updateResourceState() {
        val memoryUsage = this.memoryUsage.get()
        val cpuUsage = this.cpuUsage.get()
        val batteryLevel = this.batteryLevel.get()
        
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val memoryPressure = 1.0f - (memInfo.availMem.toFloat() / memInfo.totalMem.toFloat())
        
        val newState = when {
            memoryPressure > MEMORY_CRITICAL_THRESHOLD || 
            cpuUsage > CPU_CRITICAL_THRESHOLD || 
            batteryLevel < BATTERY_CRITICAL_THRESHOLD -> ResourceState.EMERGENCY
            
            memoryPressure > MEMORY_WARNING_THRESHOLD || 
            cpuUsage > CPU_WARNING_THRESHOLD || 
            batteryLevel < BATTERY_WARNING_THRESHOLD -> ResourceState.CRITICAL
            
            memoryPressure > 0.5f || cpuUsage > 50.0 || batteryLevel < 50 -> ResourceState.WARNING
            
            else -> ResourceState.NORMAL
        }
        
        if (newState != _resourceStateFlow.value) {
            _resourceStateFlow.value = newState
        }
    }
    
    private fun updateResponseTime(responseTime: Long) {
        averageResponseTime.updateAndGet { current ->
            if (current == 0L) responseTime else (current + responseTime) / 2
        }
        peakResponseTime.updateAndGet { maxOf(it, responseTime) }
    }
    
    private fun checkResourceAlerts(resourceName: String, usage: Long, metadata: Map<String, Any>) {
        val thresholds = mapOf(
            "memory" to (MEMORY_WARNING_THRESHOLD * 1000 * 1000 * 1000).toLong(), // 1GB
            "cpu" to CPU_WARNING_THRESHOLD.toLong(),
            "battery" to BATTERY_WARNING_THRESHOLD.toLong()
        )
        
        val threshold = thresholds[resourceName] ?: return
        
        if (usage > threshold) {
            val severity = when {
                usage > threshold * 1.5 -> AlertSeverity.CRITICAL
                usage > threshold * 1.2 -> AlertSeverity.ERROR
                else -> AlertSeverity.WARNING
            }
            
            val alert = ResourceAlert(
                id = generateAlertId(),
                severity = severity,
                resourceName = resourceName,
                message = "$resourceName usage is high: $usage (threshold: $threshold)",
                currentValue = usage,
                threshold = threshold
            )
            
            alertHistory.add(alert)
            Log.w(TAG, "Resource alert: ${alert.message}")
        }
    }
    
    private fun calculateCpuUsage(): Double {
        // Simplified CPU usage calculation
        // In a real implementation, you would use more sophisticated methods
        return 50.0 // Placeholder
    }
    
    private fun getBatteryLevel(): Int {
        // Simplified battery level calculation
        // In a real implementation, you would use BatteryManager
        return 85 // Placeholder
    }
    
    private fun logMonitoringData(logLevel: Int) {
        if (logLevel <= LOG_LEVEL_INFO) {
            val statistics = getMonitoringStatistics()
            Log.i(TAG, "Memory: ${statistics.currentMemoryUsage} bytes, " +
                    "CPU: ${statistics.currentCpuUsage}%, " +
                    "Battery: ${statistics.currentBatteryLevel}%, " +
                    "State: ${statistics.resourceState}")
        }
    }
    
    private fun generateAlertId(): String {
        return "alert_${System.currentTimeMillis()}_${alertHistory.size}"
    }
}

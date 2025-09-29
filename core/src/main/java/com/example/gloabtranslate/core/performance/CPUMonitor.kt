package com.example.gloabtranslate.core.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

/**
 * Advanced CPU usage monitoring system for Android applications.
 * Tracks CPU usage, performance metrics, and provides optimization recommendations.
 */
class CPUMonitor private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "CPUMonitor"
        
        // Monitoring intervals
        private const val CPU_CHECK_INTERVAL_MS = 1000L // 1 second
        private const val DETAILED_MONITORING_INTERVAL_MS = 5000L // 5 seconds
        private const val STATISTICS_UPDATE_INTERVAL_MS = 30000L // 30 seconds
        
        // CPU usage thresholds
        private const val LOW_CPU_USAGE = 25.0
        private const val MEDIUM_CPU_USAGE = 50.0
        private const val HIGH_CPU_USAGE = 75.0
        private const val CRITICAL_CPU_USAGE = 90.0
        
        // Performance thresholds
        private const val LOW_MEMORY_THRESHOLD = 0.3f
        private const val MEDIUM_MEMORY_THRESHOLD = 0.6f
        private const val HIGH_MEMORY_THRESHOLD = 0.8f
        
        // CPU core detection
        private const val CPU_INFO_PATH = "/proc/cpuinfo"
        private const val CPU_STAT_PATH = "/proc/stat"
        private const val LOAD_AVG_PATH = "/proc/loadavg"
        
        @Volatile
        private var INSTANCE: CPUMonitor? = null
        
        fun getInstance(context: Context): CPUMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CPUMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    // CPU monitoring state
    private val isMonitoring = AtomicBoolean(false)
    private val currentCpuUsage = AtomicReference(0.0)
    private val averageCpuUsage = AtomicReference(0.0)
    private val peakCpuUsage = AtomicReference(0.0)
    private val cpuCoreCount = AtomicLong(0L)
    
    // Performance metrics
    private val totalCpuTime = AtomicLong(0L)
    private val idleCpuTime = AtomicLong(0L)
    private val systemCpuTime = AtomicLong(0L)
    private val userCpuTime = AtomicLong(0L)
    private val iowaitCpuTime = AtomicLong(0L)
    
    // Memory metrics
    private val currentMemoryUsage = AtomicLong(0L)
    private val peakMemoryUsage = AtomicLong(0L)
    private val availableMemory = AtomicLong(0L)
    private val memoryPressure = AtomicReference(0.0)
    
    // Process metrics
    private val processCpuUsage = AtomicReference(0.0)
    private val processMemoryUsage = AtomicLong(0L)
    private val threadCount = AtomicLong(0L)
    
    // Statistics
    private val monitoringStartTime = AtomicLong(0L)
    private val totalSamples = AtomicLong(0L)
    private val highCpuSamples = AtomicLong(0L)
    private val criticalCpuSamples = AtomicLong(0L)
    
    // Monitoring scope
    private val monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringJob: Job? = null
    private var detailedMonitoringJob: Job? = null
    private var statisticsJob: Job? = null
    
    // State flows
    private val _cpuUsageFlow = MutableStateFlow(0.0)
    val cpuUsageFlow: Flow<Double> = _cpuUsageFlow.asStateFlow()
    
    private val _memoryUsageFlow = MutableStateFlow(0.0)
    val memoryUsageFlow: Flow<Double> = _memoryUsageFlow.asStateFlow()
    
    private val _performanceStateFlow = MutableStateFlow(PerformanceState.NORMAL)
    val performanceStateFlow: Flow<PerformanceState> = _performanceStateFlow.asStateFlow()
    
    /**
     * Performance state enumeration
     */
    enum class PerformanceState {
        EXCELLENT,  // < 25% CPU usage
        GOOD,       // 25-50% CPU usage
        NORMAL,     // 50-75% CPU usage
        HIGH,       // 75-90% CPU usage
        CRITICAL    // > 90% CPU usage
    }
    
    /**
     * CPU usage data
     */
    data class CPUUsageData(
        val currentUsage: Double,
        val averageUsage: Double,
        val peakUsage: Double,
        val coreCount: Int,
        val loadAverage: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Memory usage data
     */
    data class MemoryUsageData(
        val currentUsage: Long,
        val peakUsage: Long,
        val availableMemory: Long,
        val totalMemory: Long,
        val usagePercentage: Double,
        val pressure: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Process performance data
     */
    data class ProcessPerformanceData(
        val cpuUsage: Double,
        val memoryUsage: Long,
        val threadCount: Int,
        val gcCount: Long,
        val gcTime: Long,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Performance statistics
     */
    data class PerformanceStatistics(
        val totalSamples: Long,
        val averageCpuUsage: Double,
        val peakCpuUsage: Double,
        val highCpuSamples: Long,
        val criticalCpuSamples: Long,
        val averageMemoryUsage: Long,
        val peakMemoryUsage: Long,
        val monitoringDuration: Long,
        val performanceState: PerformanceState
    )
    
    init {
        initializeCpuInfo()
        startMonitoring()
    }
    
    /**
     * Starts CPU monitoring
     */
    fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            monitoringStartTime.set(System.currentTimeMillis())
            startCpuMonitoring()
            startDetailedMonitoring()
            startStatisticsUpdate()
            Log.d(TAG, "CPU monitoring started")
        }
    }
    
    /**
     * Stops CPU monitoring
     */
    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            monitoringJob?.cancel()
            detailedMonitoringJob?.cancel()
            statisticsJob?.cancel()
            Log.d(TAG, "CPU monitoring stopped")
        }
    }
    
    /**
     * Gets current CPU usage
     */
    fun getCurrentCpuUsage(): Double = currentCpuUsage.get()
    
    /**
     * Gets current memory usage
     */
    fun getCurrentMemoryUsage(): Long = currentMemoryUsage.get()
    
    /**
     * Gets CPU usage data
     */
    fun getCpuUsageData(): CPUUsageData {
        return CPUUsageData(
            currentUsage = currentCpuUsage.get(),
            averageUsage = averageCpuUsage.get(),
            peakUsage = peakCpuUsage.get(),
            coreCount = cpuCoreCount.get().toInt(),
            loadAverage = getLoadAverage()
        )
    }
    
    /**
     * Gets memory usage data
     */
    fun getMemoryUsageData(): MemoryUsageData {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalMemory = memInfo.totalMem
        val availableMem = memInfo.availMem
        val currentMem = totalMemory - availableMem
        val usagePercentage = (currentMem.toDouble() / totalMemory) * 100.0
        
        return MemoryUsageData(
            currentUsage = currentMem,
            peakUsage = peakMemoryUsage.get(),
            availableMemory = availableMem,
            totalMemory = totalMemory,
            usagePercentage = usagePercentage,
            pressure = memoryPressure.get()
        )
    }
    
    /**
     * Gets process performance data
     */
    fun getProcessPerformanceData(): ProcessPerformanceData {
        val runtime = Runtime.getRuntime()
        val memoryUsage = runtime.totalMemory() - runtime.freeMemory()
        
        return ProcessPerformanceData(
            cpuUsage = processCpuUsage.get(),
            memoryUsage = memoryUsage,
            threadCount = getThreadCount(),
            gcCount = getGcCount(),
            gcTime = getGcTime()
        )
    }
    
    /**
     * Gets performance statistics
     */
    fun getPerformanceStatistics(): PerformanceStatistics {
        val monitoringDuration = System.currentTimeMillis() - monitoringStartTime.get()
        
        return PerformanceStatistics(
            totalSamples = totalSamples.get(),
            averageCpuUsage = averageCpuUsage.get(),
            peakCpuUsage = peakCpuUsage.get(),
            highCpuSamples = highCpuSamples.get(),
            criticalCpuSamples = criticalCpuSamples.get(),
            averageMemoryUsage = getAverageMemoryUsage(),
            peakMemoryUsage = peakMemoryUsage.get(),
            monitoringDuration = monitoringDuration,
            performanceState = _performanceStateFlow.value
        )
    }
    
    /**
     * Checks if CPU usage is high
     */
    fun isCpuUsageHigh(): Boolean = currentCpuUsage.get() > HIGH_CPU_USAGE
    
    /**
     * Checks if CPU usage is critical
     */
    fun isCpuUsageCritical(): Boolean = currentCpuUsage.get() > CRITICAL_CPU_USAGE
    
    /**
     * Checks if memory pressure is high
     */
    fun isMemoryPressureHigh(): Boolean = memoryPressure.get() > HIGH_MEMORY_THRESHOLD
    
    /**
     * Gets performance recommendations
     */
    fun getPerformanceRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        val cpuUsage = currentCpuUsage.get()
        val memoryPressure = memoryPressure.get()
        
        when {
            cpuUsage > CRITICAL_CPU_USAGE -> {
                recommendations.add("CRITICAL: Reduce CPU-intensive operations")
                recommendations.add("Consider reducing background tasks")
                recommendations.add("Optimize algorithms and data structures")
            }
            cpuUsage > HIGH_CPU_USAGE -> {
                recommendations.add("HIGH: Consider reducing task frequency")
                recommendations.add("Optimize image processing")
                recommendations.add("Use more efficient algorithms")
            }
            cpuUsage > MEDIUM_CPU_USAGE -> {
                recommendations.add("MEDIUM: Monitor CPU usage closely")
                recommendations.add("Consider caching strategies")
            }
        }
        
        when {
            memoryPressure > HIGH_MEMORY_THRESHOLD -> {
                recommendations.add("CRITICAL: Free up memory immediately")
                recommendations.add("Reduce image cache size")
                recommendations.add("Clear unused resources")
            }
            memoryPressure > MEDIUM_MEMORY_THRESHOLD -> {
                recommendations.add("HIGH: Monitor memory usage")
                recommendations.add("Consider reducing cache sizes")
            }
        }
        
        return recommendations
    }
    
    /**
     * Resets monitoring statistics
     */
    fun resetStatistics() {
        totalSamples.set(0L)
        highCpuSamples.set(0L)
        criticalCpuSamples.set(0L)
        averageCpuUsage.set(0.0)
        peakCpuUsage.set(0.0)
        peakMemoryUsage.set(0L)
        monitoringStartTime.set(System.currentTimeMillis())
    }
    
    /**
     * Destroys the CPU monitor
     */
    fun destroy() {
        stopMonitoring()
        monitoringScope.cancel()
    }
    
    // Private helper methods
    
    private fun initializeCpuInfo() {
        try {
            val coreCount = getCpuCoreCount()
            cpuCoreCount.set(coreCount.toLong())
            Log.d(TAG, "Detected $coreCount CPU cores")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CPU info", e)
            cpuCoreCount.set(1L) // Default to 1 core
        }
    }
    
    private fun startCpuMonitoring() {
        monitoringJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                updateCpuUsage()
                updateMemoryUsage()
                updatePerformanceState()
                delay(CPU_CHECK_INTERVAL_MS)
            }
        }
    }
    
    private fun startDetailedMonitoring() {
        detailedMonitoringJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                updateDetailedMetrics()
                delay(DETAILED_MONITORING_INTERVAL_MS)
            }
        }
    }
    
    private fun startStatisticsUpdate() {
        statisticsJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                updateStatistics()
                delay(STATISTICS_UPDATE_INTERVAL_MS)
            }
        }
    }
    
    private fun updateCpuUsage() {
        try {
            val cpuUsage = calculateCpuUsage()
            currentCpuUsage.set(cpuUsage)
            _cpuUsageFlow.value = cpuUsage
            
            // Update peak usage
            peakCpuUsage.updateAndGet { maxOf(it, cpuUsage) }
            
            // Update sample counts
            totalSamples.incrementAndGet()
            if (cpuUsage > HIGH_CPU_USAGE) {
                highCpuSamples.incrementAndGet()
            }
            if (cpuUsage > CRITICAL_CPU_USAGE) {
                criticalCpuSamples.incrementAndGet()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating CPU usage", e)
        }
    }
    
    private fun updateMemoryUsage() {
        try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            val totalMemory = memInfo.totalMem
            val availableMemory = memInfo.availMem
            val currentMemory = totalMemory - availableMemory
            
            currentMemoryUsage.set(currentMemory)
            this.availableMemory.set(availableMemory)
            
            // Update peak memory usage
            peakMemoryUsage.updateAndGet { maxOf(it, currentMemory) }
            
            // Calculate memory pressure
            val pressure = 1.0 - (availableMemory.toDouble() / totalMemory)
            memoryPressure.set(pressure)
            
            _memoryUsageFlow.value = pressure
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating memory usage", e)
        }
    }
    
    private fun updateDetailedMetrics() {
        try {
            // Update process-specific metrics
            updateProcessMetrics()
            
            // Update system-wide metrics
            updateSystemMetrics()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating detailed metrics", e)
        }
    }
    
    private fun updateProcessMetrics() {
        val runtime = Runtime.getRuntime()
        val memoryUsage = runtime.totalMemory() - runtime.freeMemory()
        processMemoryUsage.set(memoryUsage)
        
        // Calculate process CPU usage (simplified)
        val processCpu = calculateProcessCpuUsage()
        processCpuUsage.set(processCpu)
        
        // Update thread count
        val threadCount = getThreadCount()
        this.threadCount.set(threadCount.toLong())
    }
    
    private fun updateSystemMetrics() {
        try {
            val cpuStats = readCpuStats()
            if (cpuStats.isNotEmpty()) {
                val totalTime = cpuStats["total"] ?: 0L
                val idleTime = cpuStats["idle"] ?: 0L
                val systemTime = cpuStats["system"] ?: 0L
                val userTime = cpuStats["user"] ?: 0L
                val iowaitTime = cpuStats["iowait"] ?: 0L
                
                totalCpuTime.set(totalTime)
                idleCpuTime.set(idleTime)
                systemCpuTime.set(systemTime)
                userCpuTime.set(userTime)
                iowaitCpuTime.set(iowaitTime)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating system metrics", e)
        }
    }
    
    private fun updatePerformanceState() {
        val cpuUsage = currentCpuUsage.get()
        val memoryPressure = memoryPressure.get()
        
        val newState = when {
            cpuUsage < LOW_CPU_USAGE && memoryPressure < LOW_MEMORY_THRESHOLD -> PerformanceState.EXCELLENT
            cpuUsage < MEDIUM_CPU_USAGE && memoryPressure < MEDIUM_MEMORY_THRESHOLD -> PerformanceState.GOOD
            cpuUsage < HIGH_CPU_USAGE && memoryPressure < HIGH_MEMORY_THRESHOLD -> PerformanceState.NORMAL
            cpuUsage < CRITICAL_CPU_USAGE -> PerformanceState.HIGH
            else -> PerformanceState.CRITICAL
        }
        
        if (newState != _performanceStateFlow.value) {
            _performanceStateFlow.value = newState
        }
    }
    
    private fun updateStatistics() {
        val samples = totalSamples.get()
        if (samples > 0) {
            val currentAverage = averageCpuUsage.get()
            val newAverage = (currentAverage * (samples - 1).toDouble() + currentCpuUsage.get()) / samples.toDouble()
            averageCpuUsage.set(newAverage)
        }
    }
    
    private fun calculateCpuUsage(): Double {
        return try {
            val cpuStats = readCpuStats()
            if (cpuStats.isEmpty()) return 0.0
            
            val totalTime = cpuStats["total"] ?: 0L
            val idleTime = cpuStats["idle"] ?: 0L
            
            if (totalTime == 0L) return 0.0
            
            val usage = ((totalTime - idleTime).toDouble() / totalTime) * 100.0
            maxOf(0.0, minOf(100.0, usage))
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating CPU usage", e)
            0.0
        }
    }
    
    private fun calculateProcessCpuUsage(): Double {
        // Simplified process CPU usage calculation
        // In a real implementation, you would use more sophisticated methods
        return currentCpuUsage.get() * 0.1 // Assume process uses 10% of system CPU
    }
    
    private fun readCpuStats(): Map<String, Long> {
        return try {
            RandomAccessFile(CPU_STAT_PATH, "r").use { file ->
                val line = file.readLine()
                val parts = line.split("\\s+".toRegex())
                
                if (parts.size >= 8) {
                    mapOf<String, Long>(
                        "user" to (parts[1].toLongOrNull() ?: 0L),
                        "nice" to (parts[2].toLongOrNull() ?: 0L),
                        "system" to (parts[3].toLongOrNull() ?: 0L),
                        "idle" to (parts[4].toLongOrNull() ?: 0L),
                        "iowait" to (parts[5].toLongOrNull() ?: 0L),
                        "irq" to (parts[6].toLongOrNull() ?: 0L),
                        "softirq" to (parts[7].toLongOrNull() ?: 0L),
                        "total" to ((parts[1].toLongOrNull() ?: 0L) +
                                (parts[2].toLongOrNull() ?: 0L) +
                                (parts[3].toLongOrNull() ?: 0L) +
                                (parts[4].toLongOrNull() ?: 0L) +
                                (parts[5].toLongOrNull() ?: 0L) +
                                (parts[6].toLongOrNull() ?: 0L) +
                                (parts[7].toLongOrNull() ?: 0L))
                    )
                } else {
                    emptyMap()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CPU stats", e)
            emptyMap()
        }
    }
    
    private fun getCpuCoreCount(): Int {
        return try {
            RandomAccessFile(CPU_INFO_PATH, "r").use { file ->
                var coreCount = 0
                var line: String?
                while (file.readLine().also { line = it } != null) {
                    if (line?.startsWith("processor") == true) {
                        coreCount++
                    }
                }
                maxOf(1, coreCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CPU core count", e)
            1
        }
    }
    
    private fun getLoadAverage(): Double {
        return try {
            RandomAccessFile(LOAD_AVG_PATH, "r").use { file ->
                val line = file.readLine()
                val parts = line.split("\\s+".toRegex())
                parts[0].toDoubleOrNull() ?: 0.0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading load average", e)
            0.0
        }
    }
    
    private fun getThreadCount(): Int {
        return try {
            Thread.activeCount()
        } catch (e: Exception) {
            0
        }
    }
    
    private fun getGcCount(): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    // Use reflection to access the method if available
                    val method = Debug::class.java.getDeclaredMethod("getGlobalGcInvokeCount")
                    method.invoke(null) as Long
                } catch (e: Exception) {
                    // Fallback if method is not available
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                }
            } else {
                // Fallback for older API levels
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getGcTime(): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    // Use reflection to access the method if available
                    val method = Debug::class.java.getDeclaredMethod("getGlobalGcInvokeCount")
                    val gcCount = method.invoke(null) as Long
                    gcCount * 10 // Simplified GC time calculation
                } catch (e: Exception) {
                    // Fallback if method is not available
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1000
                }
            } else {
                // Fallback for older API levels
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1000
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getAverageMemoryUsage(): Long {
        // Simplified average memory usage calculation
        return currentMemoryUsage.get()
    }
}

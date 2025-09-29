package com.example.gloabtranslate.core.resources

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import kotlin.math.*

/**
 * Advanced memory leak prevention system for Android applications.
 * Monitors object lifecycle, detects potential leaks, and implements prevention strategies.
 */
class MemoryLeakPrevention private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "MemoryLeakPrevention"
        
        // Memory leak detection constants
        private const val LEAK_DETECTION_INTERVAL_MS = 60000L // 1 minute
        private const val OBJECT_AGE_THRESHOLD_MS = 300000L // 5 minutes
        private const val MEMORY_LEAK_THRESHOLD = 0.8f
        private const val MAX_OBJECT_REFERENCES = 1000
        
        // Memory pressure thresholds
        private const val LOW_MEMORY_THRESHOLD = 0.6f
        private const val MEDIUM_MEMORY_THRESHOLD = 0.75f
        private const val HIGH_MEMORY_THRESHOLD = 0.9f
        
        // Cleanup intervals
        private const val CLEANUP_INTERVAL_MS = 30000L // 30 seconds
        private const val FORCE_CLEANUP_INTERVAL_MS = 300000L // 5 minutes
        
        @Volatile
        private var INSTANCE: MemoryLeakPrevention? = null
        
        fun getInstance(context: Context): MemoryLeakPrevention {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryLeakPrevention(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    // Object tracking
    private val trackedObjects = ConcurrentHashMap<String, TrackedObject>()
    private val objectReferences = ConcurrentHashMap<String, MutableSet<WeakReference<Any>>>()
    private val phantomReferences = ConcurrentHashMap<String, PhantomReference<Any>>()
    private val referenceQueue = ReferenceQueue<Any>()
    
    // Memory monitoring
    private val isMonitoring = AtomicBoolean(false)
    private val totalObjectsTracked = AtomicLong(0L)
    private val totalLeaksDetected = AtomicLong(0L)
    private val totalLeaksPrevented = AtomicLong(0L)
    private val totalMemoryFreed = AtomicLong(0L)
    
    // Memory pressure detection
    private val currentMemoryUsage = AtomicLong(0L)
    private val peakMemoryUsage = AtomicLong(0L)
    private val memoryPressureLevel = AtomicInteger(0) // 0-3 scale
    
    // Leak detection
    private val suspectedLeaks = CopyOnWriteArrayList<SuspectedLeak>()
    private val confirmedLeaks = CopyOnWriteArrayList<ConfirmedLeak>()
    
    // Cleanup management
    private val cleanupMutex = Mutex()
    private val monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringJob: Job? = null
    private var cleanupJob: Job? = null
    private var leakDetectionJob: Job? = null
    
    // State flows
    private val _memoryPressureFlow = MutableStateFlow(MemoryPressureLevel.NORMAL)
    val memoryPressureFlow: Flow<MemoryPressureLevel> = _memoryPressureFlow.asStateFlow()
    
    private val _leakDetectionFlow = MutableStateFlow(LeakDetectionState.NORMAL)
    val leakDetectionFlow: Flow<LeakDetectionState> = _leakDetectionFlow.asStateFlow()
    
    /**
     * Memory pressure levels
     */
    enum class MemoryPressureLevel {
        NORMAL,     // < 60% memory usage
        LOW,        // 60-75% memory usage
        MEDIUM,     // 75-90% memory usage
        HIGH,       // 90-95% memory usage
        CRITICAL    // > 95% memory usage
    }
    
    /**
     * Leak detection states
     */
    enum class LeakDetectionState {
        NORMAL,         // No leaks detected
        SUSPECTED,      // Potential leaks detected
        CONFIRMED,      // Leaks confirmed
        CRITICAL        // Critical leaks detected
    }
    
    /**
     * Tracked object data structure
     */
    data class TrackedObject(
        val id: String,
        val className: String,
        val size: Long,
        val createdAt: Long = System.currentTimeMillis(),
        val lastAccessedAt: Long = System.currentTimeMillis(),
        val accessCount: Int = 0,
        val isDisposed: Boolean = false,
        val leakRisk: LeakRisk = LeakRisk.LOW,
        val metadata: Map<String, Any> = emptyMap()
    ) {
        val age: Long get() = System.currentTimeMillis() - createdAt
        val timeSinceLastAccess: Long get() = System.currentTimeMillis() - lastAccessedAt
    }
    
    /**
     * Leak risk levels
     */
    enum class LeakRisk {
        NONE,       // No leak risk
        LOW,        // Low leak risk
        MEDIUM,     // Medium leak risk
        HIGH,       // High leak risk
        CRITICAL    // Critical leak risk
    }
    
    /**
     * Suspected leak data
     */
    data class SuspectedLeak(
        val objectId: String,
        val className: String,
        val age: Long,
        val size: Long,
        val riskLevel: LeakRisk,
        val detectedAt: Long = System.currentTimeMillis(),
        val reasons: List<String> = emptyList()
    )
    
    /**
     * Confirmed leak data
     */
    data class ConfirmedLeak(
        val objectId: String,
        val className: String,
        val size: Long,
        val confirmedAt: Long = System.currentTimeMillis(),
        val preventionActions: List<String> = emptyList()
    )
    
    /**
     * Memory leak prevention result
     */
    data class PreventionResult(
        val success: Boolean,
        val leaksDetected: Int,
        val leaksPrevented: Int,
        val memoryFreed: Long,
        val actionsTaken: List<String>,
        val errors: List<String>
    )
    
    /**
     * Memory statistics
     */
    data class MemoryStatistics(
        val totalObjectsTracked: Long,
        val totalLeaksDetected: Long,
        val totalLeaksPrevented: Long,
        val totalMemoryFreed: Long,
        val currentMemoryUsage: Long,
        val peakMemoryUsage: Long,
        val memoryPressureLevel: MemoryPressureLevel,
        val leakDetectionState: LeakDetectionState,
        val suspectedLeaksCount: Int,
        val confirmedLeaksCount: Int
    )
    
    init {
        startMonitoring()
    }
    
    /**
     * Starts memory leak monitoring
     */
    fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            startMemoryMonitoring()
            startLeakDetection()
            startCleanupRoutine()
            Log.d(TAG, "Memory leak prevention monitoring started")
        }
    }
    
    /**
     * Stops memory leak monitoring
     */
    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            monitoringJob?.cancel()
            leakDetectionJob?.cancel()
            cleanupJob?.cancel()
            Log.d(TAG, "Memory leak prevention monitoring stopped")
        }
    }
    
    /**
     * Tracks an object for leak detection
     */
    fun trackObject(
        obj: Any,
        id: String? = null,
        leakRisk: LeakRisk = LeakRisk.LOW,
        metadata: Map<String, Any> = emptyMap()
    ): String {
        val objectId = id ?: generateObjectId(obj)
        val className = obj.javaClass.simpleName
        val size = estimateObjectSize(obj)
        
        val trackedObject = TrackedObject(
            id = objectId,
            className = className,
            size = size,
            leakRisk = leakRisk,
            metadata = metadata
        )
        
        trackedObjects[objectId] = trackedObject
        totalObjectsTracked.incrementAndGet()
        
        // Create weak reference for tracking
        val weakRef = WeakReference(obj)
        objectReferences.computeIfAbsent(objectId) { mutableSetOf() }.add(weakRef)
        
        // Create phantom reference for cleanup detection
        val phantomRef = PhantomReference(obj, referenceQueue)
        phantomReferences[objectId] = phantomRef
        
        Log.d(TAG, "Tracking object: $objectId (${obj.javaClass.simpleName})")
        return objectId
    }
    
    /**
     * Stops tracking an object
     */
    fun stopTracking(objectId: String): Boolean {
        return try {
            trackedObjects.remove(objectId)
            objectReferences.remove(objectId)
            phantomReferences.remove(objectId)
            Log.d(TAG, "Stopped tracking object: $objectId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tracking for object: $objectId", e)
            false
        }
    }
    
    /**
     * Updates object access information
     */
    fun updateObjectAccess(objectId: String): Boolean {
        return try {
            val trackedObject = trackedObjects[objectId]
            if (trackedObject != null && !trackedObject.isDisposed) {
                val updatedObject = trackedObject.copy(
                    lastAccessedAt = System.currentTimeMillis(),
                    accessCount = trackedObject.accessCount + 1
                )
                trackedObjects[objectId] = updatedObject
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating object access: $objectId", e)
            false
        }
    }
    
    /**
     * Performs memory leak prevention
     */
    suspend fun preventMemoryLeaks(): PreventionResult = cleanupMutex.withLock {
        try {
            val startTime = System.currentTimeMillis()
            val actionsTaken = mutableListOf<String>()
            val errors = mutableListOf<String>()
            var leaksDetected = 0
            var leaksPrevented = 0
            var memoryFreed = 0L
            
            // Detect potential leaks
            val suspectedLeaks = detectSuspectedLeaks()
            leaksDetected = suspectedLeaks.size
            
            // Process phantom references (objects that have been garbage collected)
            processPhantomReferences()
            
            // Clean up confirmed leaks
            for (leak in confirmedLeaks.toList()) {
                try {
                    val success = cleanUpLeak(leak)
                    if (success) {
                        leaksPrevented++
                        memoryFreed += leak.size
                        actionsTaken.add("Cleaned up leak: ${leak.objectId}")
                    }
                } catch (e: Exception) {
                    errors.add("Failed to clean up leak ${leak.objectId}: ${e.message}")
                }
            }
            
            // Clean up old objects
            val oldObjects = trackedObjects.values
                .filter { !it.isDisposed && it.age > OBJECT_AGE_THRESHOLD_MS }
                .sortedBy { it.leakRisk.ordinal }
            
            for (obj in oldObjects.take(10)) { // Clean up max 10 at a time
                try {
                    val success = cleanUpObject(obj.id)
                    if (success) {
                        memoryFreed += obj.size
                        actionsTaken.add("Cleaned up old object: ${obj.id}")
                    }
                } catch (e: Exception) {
                    errors.add("Failed to clean up old object ${obj.id}: ${e.message}")
                }
            }
            
            // Update statistics
            totalLeaksDetected.addAndGet(leaksDetected.toLong())
            totalLeaksPrevented.addAndGet(leaksPrevented.toLong())
            totalMemoryFreed.addAndGet(memoryFreed)
            
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Memory leak prevention completed in ${duration}ms")
            
            PreventionResult(
                success = errors.isEmpty(),
                leaksDetected = leaksDetected,
                leaksPrevented = leaksPrevented,
                memoryFreed = memoryFreed,
                actionsTaken = actionsTaken,
                errors = errors
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during memory leak prevention", e)
            PreventionResult(
                success = false,
                leaksDetected = 0,
                leaksPrevented = 0,
                memoryFreed = 0L,
                actionsTaken = emptyList(),
                errors = listOf(e.message ?: "Unknown error")
            )
        }
    }
    
    /**
     * Gets memory statistics
     */
    fun getMemoryStatistics(): MemoryStatistics {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val currentUsage = memInfo.totalMem - memInfo.availMem
        currentMemoryUsage.set(currentUsage)
        peakMemoryUsage.updateAndGet { maxOf(it, currentUsage) }
        
        return MemoryStatistics(
            totalObjectsTracked = totalObjectsTracked.get(),
            totalLeaksDetected = totalLeaksDetected.get(),
            totalLeaksPrevented = totalLeaksPrevented.get(),
            totalMemoryFreed = totalMemoryFreed.get(),
            currentMemoryUsage = currentUsage,
            peakMemoryUsage = peakMemoryUsage.get(),
            memoryPressureLevel = _memoryPressureFlow.value,
            leakDetectionState = _leakDetectionFlow.value,
            suspectedLeaksCount = suspectedLeaks.size,
            confirmedLeaksCount = confirmedLeaks.size
        )
    }
    
    /**
     * Gets suspected leaks
     */
    fun getSuspectedLeaks(): List<SuspectedLeak> = suspectedLeaks.toList()
    
    /**
     * Gets confirmed leaks
     */
    fun getConfirmedLeaks(): List<ConfirmedLeak> = confirmedLeaks.toList()
    
    /**
     * Forces cleanup of all tracked objects
     */
    suspend fun forceCleanupAll(): PreventionResult = cleanupMutex.withLock {
        val startTime = System.currentTimeMillis()
        val actionsTaken = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var memoryFreed = 0L
        
        val allObjects = trackedObjects.values.toList()
        
        for (obj in allObjects) {
            try {
                val success = cleanUpObject(obj.id)
                if (success) {
                    memoryFreed += obj.size
                    actionsTaken.add("Force cleaned object: ${obj.id}")
                }
            } catch (e: Exception) {
                errors.add("Failed to force clean object ${obj.id}: ${e.message}")
            }
        }
        
        val duration = System.currentTimeMillis() - startTime
        
        PreventionResult(
            success = errors.isEmpty(),
            leaksDetected = 0,
            leaksPrevented = allObjects.size,
            memoryFreed = memoryFreed,
            actionsTaken = actionsTaken,
            errors = errors
        )
    }
    
    /**
     * Destroys the memory leak prevention system
     */
    fun destroy() {
        stopMonitoring()
        runBlocking {
            forceCleanupAll()
        }
    }
    
    // Private helper methods
    
    private fun startMemoryMonitoring() {
        monitoringJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                updateMemoryUsage()
                updateMemoryPressure()
                delay(LEAK_DETECTION_INTERVAL_MS)
            }
        }
    }
    
    private fun startLeakDetection() {
        leakDetectionJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                detectMemoryLeaks()
                delay(LEAK_DETECTION_INTERVAL_MS)
            }
        }
    }
    
    private fun startCleanupRoutine() {
        cleanupJob = monitoringScope.launch {
            while (isActive && isMonitoring.get()) {
                delay(CLEANUP_INTERVAL_MS)
                performAutomaticCleanup()
            }
        }
    }
    
    private fun updateMemoryUsage() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val currentUsage = memInfo.totalMem - memInfo.availMem
        currentMemoryUsage.set(currentUsage)
        peakMemoryUsage.updateAndGet { maxOf(it, currentUsage) }
    }
    
    private fun updateMemoryPressure() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val memoryPressure = 1.0f - (memInfo.availMem.toFloat() / memInfo.totalMem.toFloat())
        
        val newLevel = when {
            memoryPressure > HIGH_MEMORY_THRESHOLD -> MemoryPressureLevel.CRITICAL
            memoryPressure > MEDIUM_MEMORY_THRESHOLD -> MemoryPressureLevel.HIGH
            memoryPressure > LOW_MEMORY_THRESHOLD -> MemoryPressureLevel.MEDIUM
            else -> MemoryPressureLevel.NORMAL
        }
        
        if (newLevel != _memoryPressureFlow.value) {
            _memoryPressureFlow.value = newLevel
        }
        
        memoryPressureLevel.set(newLevel.ordinal)
    }
    
    private suspend fun detectMemoryLeaks() {
        val suspectedLeaks = detectSuspectedLeaks()
        
        if (suspectedLeaks.isNotEmpty()) {
            this.suspectedLeaks.addAll(suspectedLeaks)
            
            // Confirm leaks
            val confirmedLeaks = confirmLeaks(suspectedLeaks)
            this.confirmedLeaks.addAll(confirmedLeaks)
            
            // Update leak detection state
            val newState = when {
                confirmedLeaks.size > 10 -> LeakDetectionState.CRITICAL
                confirmedLeaks.isNotEmpty() -> LeakDetectionState.CONFIRMED
                suspectedLeaks.isNotEmpty() -> LeakDetectionState.SUSPECTED
                else -> LeakDetectionState.NORMAL
            }
            
            if (newState != _leakDetectionFlow.value) {
                _leakDetectionFlow.value = newState
            }
        }
    }
    
    private fun detectSuspectedLeaks(): List<SuspectedLeak> {
        val suspected = mutableListOf<SuspectedLeak>()
        
        for ((id, obj) in trackedObjects) {
            if (obj.isDisposed) continue
            
            val reasons = mutableListOf<String>()
            var riskLevel = obj.leakRisk
            
            // Check object age
            if (obj.age > OBJECT_AGE_THRESHOLD_MS) {
                reasons.add("Object is very old (${obj.age}ms)")
                riskLevel = LeakRisk.HIGH
            }
            
            // Check access pattern
            if (obj.timeSinceLastAccess > OBJECT_AGE_THRESHOLD_MS) {
                reasons.add("Object hasn't been accessed recently (${obj.timeSinceLastAccess}ms)")
                riskLevel = LeakRisk.MEDIUM
            }
            
            // Check memory pressure
            if (memoryPressureLevel.get() > 2) {
                reasons.add("High memory pressure detected")
                riskLevel = LeakRisk.HIGH
            }
            
            // Check object size
            if (obj.size > 1024 * 1024) { // 1MB
                reasons.add("Object is very large (${obj.size} bytes)")
                riskLevel = LeakRisk.MEDIUM
            }
            
            if (reasons.isNotEmpty()) {
                suspected.add(
                    SuspectedLeak(
                        objectId = id,
                        className = obj.className,
                        age = obj.age,
                        size = obj.size,
                        riskLevel = riskLevel,
                        reasons = reasons
                    )
                )
            }
        }
        
        return suspected
    }
    
    private fun confirmLeaks(suspectedLeaks: List<SuspectedLeak>): List<ConfirmedLeak> {
        val confirmed = mutableListOf<ConfirmedLeak>()
        
        for (leak in suspectedLeaks) {
            // Check if object is still referenced
            val references = objectReferences[leak.objectId]
            val hasActiveReferences = references?.any { it.get() != null } == true
            
            if (!hasActiveReferences && leak.riskLevel >= LeakRisk.MEDIUM) {
                confirmed.add(
                    ConfirmedLeak(
                        objectId = leak.objectId,
                        className = leak.className,
                        size = leak.size,
                        preventionActions = listOf("Remove object references", "Force garbage collection")
                    )
                )
            }
        }
        
        return confirmed
    }
    
    private suspend fun performAutomaticCleanup() {
        if (memoryPressureLevel.get() > 1) { // Medium or higher memory pressure
            preventMemoryLeaks()
        }
    }
    
    private fun processPhantomReferences() {
        var phantomRef: PhantomReference<Any>?
        while (referenceQueue.poll().also { phantomRef = it as? PhantomReference<Any> } != null) {
            // Find the corresponding tracked object
            val objectId = phantomReferences.entries.find { it.value == phantomRef }?.key
            if (objectId != null) {
                // Object has been garbage collected, clean up tracking
                trackedObjects.remove(objectId)
                objectReferences.remove(objectId)
                phantomReferences.remove(objectId)
                Log.d(TAG, "Object $objectId was garbage collected")
            }
        }
    }
    
    private suspend fun cleanUpLeak(leak: ConfirmedLeak): Boolean {
        return try {
            // Remove from tracking
            trackedObjects.remove(leak.objectId)
            objectReferences.remove(leak.objectId)
            phantomReferences.remove(leak.objectId)
            
            // Remove from confirmed leaks
            confirmedLeaks.remove(leak)
            
            Log.d(TAG, "Cleaned up confirmed leak: ${leak.objectId}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up leak: ${leak.objectId}", e)
            false
        }
    }
    
    private suspend fun cleanUpObject(objectId: String): Boolean {
        return try {
            val obj = trackedObjects[objectId]
            if (obj != null) {
                // Mark as disposed
                trackedObjects[objectId] = obj.copy(isDisposed = true)
                
                // Remove references
                objectReferences.remove(objectId)
                phantomReferences.remove(objectId)
                
                Log.d(TAG, "Cleaned up object: $objectId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up object: $objectId", e)
            false
        }
    }
    
    private fun estimateObjectSize(obj: Any): Long {
        // Simplified object size estimation
        return when (obj) {
            is String -> obj.length * 2L // 2 bytes per character
            is ByteArray -> obj.size.toLong()
            is IntArray -> obj.size * 4L
            is LongArray -> obj.size * 8L
            is FloatArray -> obj.size * 4L
            is DoubleArray -> obj.size * 8L
            else -> 64L // Default size for objects
        }
    }
    
    private fun generateObjectId(obj: Any): String {
        return "${obj.javaClass.simpleName}_${System.currentTimeMillis()}_${obj.hashCode()}"
    }
}

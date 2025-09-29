package com.example.gloabtranslate.core.resources

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

/**
 * Advanced resource management system for Android applications.
 * Handles resource lifecycle, cleanup, and monitoring across all services.
 */
class ResourceManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ResourceManager"
        
        // Resource management constants
        private const val CLEANUP_INTERVAL_MS = 30000L // 30 seconds
        private const val MAX_RESOURCE_AGE_MS = 300000L // 5 minutes
        private const val MEMORY_CLEANUP_THRESHOLD = 0.8f
        private const val MAX_RESOURCES_PER_TYPE = 100
        
        // Resource types
        private const val TYPE_MEMORY = "memory"
        private const val TYPE_FILE = "file"
        private const val TYPE_NETWORK = "network"
        private const val TYPE_DATABASE = "database"
        private const val TYPE_CACHE = "cache"
        private const val TYPE_SERVICE = "service"
        
        @Volatile
        private var INSTANCE: ResourceManager? = null
        
        fun getInstance(context: Context): ResourceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResourceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Resource tracking
    private val managedResources = ConcurrentHashMap<String, ManagedResource>()
    private val resourceTypes = ConcurrentHashMap<String, MutableSet<String>>()
    private val resourceCleanupQueue = CopyOnWriteArrayList<ResourceCleanupTask>()
    
    // Resource monitoring
    private val totalResourcesCreated = AtomicLong(0L)
    private val totalResourcesCleaned = AtomicLong(0L)
    private val totalMemoryFreed = AtomicLong(0L)
    private val isCleaningUp = AtomicBoolean(false)
    
    // Resource statistics
    private val resourceCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val resourceMemoryUsage = ConcurrentHashMap<String, AtomicLong>()
    private val resourceLastAccess = ConcurrentHashMap<String, AtomicLong>()
    
    // Cleanup management
    private val cleanupMutex = Mutex()
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null
    
    // State flows
    private val _resourceStateFlow = MutableStateFlow(ResourceState.NORMAL)
    val resourceStateFlow: Flow<ResourceState> = _resourceStateFlow.asStateFlow()
    
    private val _memoryUsageFlow = MutableStateFlow(0L)
    val memoryUsageFlow: Flow<Long> = _memoryUsageFlow.asStateFlow()
    
    /**
     * Resource priority levels
     */
    enum class ResourcePriority {
        CRITICAL,   // Must be kept in memory
        HIGH,       // High priority, clean up last
        NORMAL,     // Normal priority
        LOW,        // Low priority, can be cleaned up
        DISPOSABLE  // Can be disposed immediately
    }
    
    /**
     * Resource state
     */
    enum class ResourceState {
        NORMAL,         // Normal resource usage
        HIGH_USAGE,     // High resource usage
        MEMORY_PRESSURE, // Memory pressure detected
        CLEANUP_NEEDED,  // Cleanup needed
        EMERGENCY       // Emergency cleanup required
    }
    
    /**
     * Managed resource data structure
     */
    data class ManagedResource(
        val id: String,
        val type: String,
        val priority: ResourcePriority,
        val size: Long,
        val createdAt: Long = System.currentTimeMillis(),
        val lastAccessedAt: Long = System.currentTimeMillis(),
        val accessCount: Int = 0,
        val isDisposed: Boolean = false,
        val cleanupCallback: (() -> Unit)? = null,
        val metadata: Map<String, Any> = emptyMap()
    ) {
        val age: Long get() = System.currentTimeMillis() - createdAt
        val timeSinceLastAccess: Long get() = System.currentTimeMillis() - lastAccessedAt
    }
    
    /**
     * Resource cleanup task
     */
    data class ResourceCleanupTask(
        val resourceId: String,
        val priority: Int,
        val scheduledTime: Long,
        val reason: String
    )
    
    /**
     * Resource cleanup result
     */
    data class CleanupResult(
        val success: Boolean,
        val resourcesCleaned: Int,
        val memoryFreed: Long,
        val errors: List<String>,
        val duration: Long
    )
    
    /**
     * Resource statistics
     */
    data class ResourceStatistics(
        val totalResources: Int,
        val resourcesByType: Map<String, Int>,
        val totalMemoryUsage: Long,
        val memoryByType: Map<String, Long>,
        val averageResourceAge: Double,
        val cleanupCount: Long,
        val memoryFreed: Long,
        val resourceState: ResourceState
    )
    
    init {
        startCleanupRoutine()
    }
    
    /**
     * Registers a resource for management
     */
    suspend fun registerResource(
        id: String,
        type: String,
        priority: ResourcePriority,
        size: Long,
        cleanupCallback: (() -> Unit)? = null,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean = cleanupMutex.withLock {
        try {
            val resource = ManagedResource(
                id = id,
                type = type,
                priority = priority,
                size = size,
                cleanupCallback = cleanupCallback,
                metadata = metadata
            )
            
            managedResources[id] = resource
            
            // Update type tracking
            resourceTypes.computeIfAbsent(type) { mutableSetOf() }.add(id)
            
            // Update statistics
            totalResourcesCreated.incrementAndGet()
            resourceCounts.computeIfAbsent(type) { AtomicInteger(0) }.incrementAndGet()
            resourceMemoryUsage.computeIfAbsent(type) { AtomicLong(0L) }.addAndGet(size)
            resourceLastAccess[id] = AtomicLong(System.currentTimeMillis())
            
            // Update memory usage
            updateMemoryUsage()
            
            Log.d(TAG, "Registered resource: $id (type: $type, size: $size bytes)")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error registering resource: $id", e)
            false
        }
    }
    
    /**
     * Unregisters a resource
     */
    suspend fun unregisterResource(id: String): Boolean = cleanupMutex.withLock {
        try {
            val resource = managedResources.remove(id)
            if (resource != null) {
                // Update type tracking
                resourceTypes[resource.type]?.remove(id)
                
                // Update statistics
                resourceCounts[resource.type]?.decrementAndGet()
                resourceMemoryUsage[resource.type]?.addAndGet(-resource.size)
                resourceLastAccess.remove(id)
                
                // Update memory usage
                updateMemoryUsage()
                
                Log.d(TAG, "Unregistered resource: $id")
                true
            } else {
                Log.w(TAG, "Resource not found: $id")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering resource: $id", e)
            false
        }
    }
    
    /**
     * Accesses a resource (updates last access time)
     */
    fun accessResource(id: String): Boolean {
        return try {
            val resource = managedResources[id]
            if (resource != null && !resource.isDisposed) {
                // Update access time and count
                resourceLastAccess[id]?.set(System.currentTimeMillis())
                managedResources[id] = resource.copy(
                    lastAccessedAt = System.currentTimeMillis(),
                    accessCount = resource.accessCount + 1
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing resource: $id", e)
            false
        }
    }
    
    /**
     * Disposes a resource immediately
     */
    suspend fun disposeResource(id: String): Boolean = cleanupMutex.withLock {
        try {
            val resource = managedResources[id]
            if (resource != null && !resource.isDisposed) {
                // Execute cleanup callback
                resource.cleanupCallback?.invoke()
                
                // Mark as disposed
                managedResources[id] = resource.copy(isDisposed = true)
                
                // Update statistics
                totalResourcesCleaned.incrementAndGet()
                totalMemoryFreed.addAndGet(resource.size)
                
                // Update type tracking
                resourceCounts[resource.type]?.decrementAndGet()
                resourceMemoryUsage[resource.type]?.addAndGet(-resource.size)
                
                // Update memory usage
                updateMemoryUsage()
                
                Log.d(TAG, "Disposed resource: $id")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing resource: $id", e)
            false
        }
    }
    
    /**
     * Schedules a resource for cleanup
     */
    suspend fun scheduleCleanup(
        id: String,
        reason: String,
        priority: Int = 0
    ): Boolean = cleanupMutex.withLock {
        try {
            val resource = managedResources[id]
            if (resource != null && !resource.isDisposed) {
                val task = ResourceCleanupTask(
                    resourceId = id,
                    priority = priority,
                    scheduledTime = System.currentTimeMillis(),
                    reason = reason
                )
                
                resourceCleanupQueue.add(task)
                resourceCleanupQueue.sortBy { it.priority }
                
                Log.d(TAG, "Scheduled cleanup for resource: $id (reason: $reason)")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling cleanup for resource: $id", e)
            false
        }
    }
    
    /**
     * Performs cleanup of all resources
     */
    suspend fun performCleanup(force: Boolean = false): CleanupResult = cleanupMutex.withLock {
        if (isCleaningUp.compareAndSet(false, true)) {
            try {
                val startTime = System.currentTimeMillis()
                val errors = mutableListOf<String>()
                var resourcesCleaned = 0
                var memoryFreed = 0L
                
                // Clean up scheduled resources
                val tasksToProcess = resourceCleanupQueue.toList()
                resourceCleanupQueue.clear()
                
                for (task in tasksToProcess) {
                    try {
                        val success = disposeResource(task.resourceId)
                        if (success) {
                            resourcesCleaned++
                            val resource = managedResources[task.resourceId]
                            if (resource != null) {
                                memoryFreed += resource.size
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("Failed to clean up ${task.resourceId}: ${e.message}")
                    }
                }
                
                // Clean up old resources if needed
                if (force || shouldCleanupOldResources()) {
                    val oldResources = managedResources.values
                        .filter { !it.isDisposed && it.age > MAX_RESOURCE_AGE_MS }
                        .sortedBy { it.priority.ordinal }
                    
                    for (resource in oldResources) {
                        try {
                            val success = disposeResource(resource.id)
                            if (success) {
                                resourcesCleaned++
                                memoryFreed += resource.size
                            }
                        } catch (e: Exception) {
                            errors.add("Failed to clean up old resource ${resource.id}: ${e.message}")
                        }
                    }
                }
                
                // Clean up low priority resources if memory pressure is high
                if (isMemoryPressureHigh()) {
                    val lowPriorityResources = managedResources.values
                        .filter { !it.isDisposed && it.priority == ResourcePriority.LOW }
                        .sortedBy { it.timeSinceLastAccess }
                    
                    for (resource in lowPriorityResources.take(10)) { // Clean up max 10 at a time
                        try {
                            val success = disposeResource(resource.id)
                            if (success) {
                                resourcesCleaned++
                                memoryFreed += resource.size
                            }
                        } catch (e: Exception) {
                            errors.add("Failed to clean up low priority resource ${resource.id}: ${e.message}")
                        }
                    }
                }
                
                val duration = System.currentTimeMillis() - startTime
                
                CleanupResult(
                    success = errors.isEmpty(),
                    resourcesCleaned = resourcesCleaned,
                    memoryFreed = memoryFreed,
                    errors = errors,
                    duration = duration
                )
                
            } finally {
                isCleaningUp.set(false)
            }
        } else {
            CleanupResult(
                success = false,
                resourcesCleaned = 0,
                memoryFreed = 0L,
                errors = listOf("Cleanup already in progress"),
                duration = 0L
            )
        }
    }
    
    /**
     * Gets resource statistics
     */
    fun getResourceStatistics(): ResourceStatistics {
        val totalResources = managedResources.size
        val resourcesByType = resourceCounts.mapValues { it.value.get() }
        val totalMemoryUsage = resourceMemoryUsage.values.sumOf { it.get() }
        val memoryByType = resourceMemoryUsage.mapValues { it.value.get() }
        
        val averageAge = if (managedResources.isNotEmpty()) {
            managedResources.values.map { it.age }.average()
        } else 0.0
        
        return ResourceStatistics(
            totalResources = totalResources,
            resourcesByType = resourcesByType,
            totalMemoryUsage = totalMemoryUsage,
            memoryByType = memoryByType,
            averageResourceAge = averageAge,
            cleanupCount = totalResourcesCleaned.get(),
            memoryFreed = totalMemoryFreed.get(),
            resourceState = _resourceStateFlow.value
        )
    }
    
    /**
     * Gets resources by type
     */
    fun getResourcesByType(type: String): List<ManagedResource> {
        return managedResources.values.filter { it.type == type && !it.isDisposed }
    }
    
    /**
     * Gets resources by priority
     */
    fun getResourcesByPriority(priority: ResourcePriority): List<ManagedResource> {
        return managedResources.values.filter { it.priority == priority && !it.isDisposed }
    }
    
    /**
     * Checks if memory pressure is high
     */
    fun isMemoryPressureHigh(): Boolean {
        val memoryUsage = getCurrentMemoryUsage()
        val totalMemory = getTotalMemory()
        return (memoryUsage.toFloat() / totalMemory) > MEMORY_CLEANUP_THRESHOLD
    }
    
    /**
     * Forces cleanup of all resources
     */
    suspend fun forceCleanupAll(): CleanupResult {
        return cleanupMutex.withLock {
            val startTime = System.currentTimeMillis()
            val errors = mutableListOf<String>()
            var resourcesCleaned = 0
            var memoryFreed = 0L
            
            val allResources = managedResources.values.toList()
            
            for (resource in allResources) {
                try {
                    val success = disposeResource(resource.id)
                    if (success) {
                        resourcesCleaned++
                        memoryFreed += resource.size
                    }
                } catch (e: Exception) {
                    errors.add("Failed to force clean up ${resource.id}: ${e.message}")
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            
            CleanupResult(
                success = errors.isEmpty(),
                resourcesCleaned = resourcesCleaned,
                memoryFreed = memoryFreed,
                errors = errors,
                duration = duration
            )
        }
    }
    
    /**
     * Destroys the resource manager
     */
    fun destroy() {
        cleanupJob?.cancel()
        runBlocking {
            forceCleanupAll()
        }
    }
    
    // Private helper methods
    
    private fun startCleanupRoutine() {
        cleanupJob = cleanupScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                performAutomaticCleanup()
            }
        }
    }
    
    private suspend fun performAutomaticCleanup() {
        if (!isCleaningUp.get()) {
            performCleanup()
        }
    }
    
    private fun shouldCleanupOldResources(): Boolean {
        val oldResourceCount = managedResources.values.count { it.age > MAX_RESOURCE_AGE_MS }
        return oldResourceCount > 10 // Clean up if more than 10 old resources
    }
    
    private fun updateMemoryUsage() {
        val totalMemory = resourceMemoryUsage.values.sumOf { it.get() }
        _memoryUsageFlow.value = totalMemory
        
        // Update resource state based on memory usage
        val memoryPressure = totalMemory.toFloat() / getTotalMemory()
        val newState = when {
            memoryPressure > 0.9f -> ResourceState.EMERGENCY
            memoryPressure > 0.8f -> ResourceState.MEMORY_PRESSURE
            memoryPressure > 0.6f -> ResourceState.HIGH_USAGE
            memoryPressure > 0.4f -> ResourceState.CLEANUP_NEEDED
            else -> ResourceState.NORMAL
        }
        
        if (newState != _resourceStateFlow.value) {
            _resourceStateFlow.value = newState
        }
    }
    
    private fun getCurrentMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
    
    private fun getTotalMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory()
    }
}

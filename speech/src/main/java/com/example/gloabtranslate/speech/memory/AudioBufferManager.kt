package com.example.gloabtranslate.speech.memory

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/**
 * Advanced audio buffer memory manager for efficient audio processing.
 * Provides intelligent buffer pooling, memory monitoring, and automatic cleanup.
 */
class AudioBufferManager {
    
    companion object {
        private const val TAG = "AudioBufferManager"
        
        // Memory management constants
        private const val DEFAULT_BUFFER_SIZE = 1024
        private const val MAX_BUFFER_POOL_SIZE = 20
        private const val MIN_BUFFER_POOL_SIZE = 5
        private const val MAX_MEMORY_USAGE_MB = 100
        private const val MEMORY_CLEANUP_THRESHOLD = 0.8f
        private const val BUFFER_CLEANUP_INTERVAL_MS = 30000L // 30 seconds
        
        // Buffer lifecycle constants
        private const val MAX_BUFFER_AGE_MS = 60000L // 1 minute
        private const val BUFFER_REUSE_THRESHOLD = 0.7f
        private const val MEMORY_PRESSURE_THRESHOLD = 0.9f
    }
    
    // Buffer pool management
    private val bufferPool = ConcurrentLinkedQueue<AudioBuffer>()
    private val activeBuffers = mutableSetOf<AudioBuffer>()
    private val bufferPoolMutex = Mutex()
    
    // Memory monitoring
    private val totalMemoryUsage = AtomicLong(0L)
    private val peakMemoryUsage = AtomicLong(0L)
    private val bufferCount = AtomicInteger(0)
    private val isMemoryPressure = AtomicBoolean(false)
    
    // Statistics
    private val totalBuffersCreated = AtomicLong(0L)
    private val totalBuffersReused = AtomicLong(0L)
    private val totalMemoryFreed = AtomicLong(0L)
    private val lastCleanupTime = AtomicLong(0L)
    
    // Cleanup coroutine
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null
    
    /**
     * Audio buffer data structure
     */
    data class AudioBuffer(
        val id: String,
        val data: FloatArray,
        val size: Int,
        val createdAt: Long = System.currentTimeMillis(),
        var lastUsedAt: Long = System.currentTimeMillis(),
        var isActive: Boolean = false,
        var referenceCount: Int = 0
    ) {
        val age: Long get() = System.currentTimeMillis() - createdAt
        val timeSinceLastUse: Long get() = System.currentTimeMillis() - lastUsedAt
        val memorySize: Long get() = size * 4L // 4 bytes per float
    }
    
    /**
     * Buffer allocation result
     */
    data class BufferAllocationResult(
        val buffer: AudioBuffer?,
        val success: Boolean,
        val memoryUsageMB: Float,
        val poolSize: Int,
        val error: String? = null
    )
    
    /**
     * Memory statistics
     */
    data class MemoryStatistics(
        val totalMemoryUsageMB: Float,
        val peakMemoryUsageMB: Float,
        val activeBufferCount: Int,
        val poolSize: Int,
        val totalBuffersCreated: Long,
        val totalBuffersReused: Long,
        val totalMemoryFreedMB: Float,
        val isMemoryPressure: Boolean,
        val averageBufferAge: Float
    )
    
    init {
        startCleanupRoutine()
    }
    
    /**
     * Allocates a new audio buffer or reuses an existing one
     */
    suspend fun allocateBuffer(
        size: Int = DEFAULT_BUFFER_SIZE,
        forceNew: Boolean = false
    ): BufferAllocationResult = bufferPoolMutex.withLock {
        
        try {
            val currentMemoryUsage = getCurrentMemoryUsage()
            val memoryUsageMB = (currentMemoryUsage / (1024 * 1024)).toFloat()
            
            // Check memory pressure
            if (memoryUsageMB > MAX_MEMORY_USAGE_MB * MEMORY_PRESSURE_THRESHOLD) {
                performEmergencyCleanup()
            }
            
            val buffer = if (forceNew || !canReuseBuffer(size)) {
                createNewBuffer(size)
            } else {
                reuseExistingBuffer(size)
            }
            
            if (buffer != null) {
                activeBuffers.add(buffer)
                buffer.isActive = true
                buffer.lastUsedAt = System.currentTimeMillis()
                buffer.referenceCount++
                
                updateMemoryUsage(buffer.memorySize)
                
                BufferAllocationResult(
                    buffer = buffer,
                    success = true,
                    memoryUsageMB = memoryUsageMB,
                    poolSize = bufferPool.size
                )
            } else {
                BufferAllocationResult(
                    buffer = null,
                    success = false,
                    memoryUsageMB = memoryUsageMB,
                    poolSize = bufferPool.size,
                    error = "Failed to allocate buffer"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error allocating buffer", e)
            BufferAllocationResult(
                buffer = null,
                success = false,
                memoryUsageMB = (getCurrentMemoryUsage() / (1024 * 1024)).toFloat(),
                poolSize = bufferPool.size,
                error = e.message
            )
        }
    }
    
    /**
     * Releases a buffer back to the pool
     */
    suspend fun releaseBuffer(buffer: AudioBuffer): Boolean = bufferPoolMutex.withLock {
        try {
            if (buffer in activeBuffers) {
                activeBuffers.remove(buffer)
                buffer.isActive = false
                buffer.referenceCount = maxOf(0, buffer.referenceCount - 1)
                
                // Return to pool if it can be reused
                if (canReuseBuffer(buffer.size)) {
                    bufferPool.offer(buffer)
                } else {
                    // Buffer is too old or memory pressure, free it
                    freeBuffer(buffer)
                }
                
                updateMemoryUsage(-buffer.memorySize)
                true
            } else {
                Log.w(TAG, "Attempted to release buffer not in active set")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing buffer", e)
            false
        }
    }
    
    /**
     * Gets a buffer from the pool without marking it as active
     */
    suspend fun getPooledBuffer(size: Int): AudioBuffer? = bufferPoolMutex.withLock {
        bufferPool.poll()?.takeIf { it.size >= size }
    }
    
    /**
     * Pre-allocates buffers for better performance
     */
    suspend fun preAllocateBuffers(
        count: Int,
        size: Int = DEFAULT_BUFFER_SIZE
    ): Int = bufferPoolMutex.withLock {
        var allocated = 0
        repeat(count) {
            if (bufferPool.size < MAX_BUFFER_POOL_SIZE) {
                val buffer = createNewBuffer(size)
                if (buffer != null) {
                    bufferPool.offer(buffer)
                    allocated++
                }
            }
        }
        allocated
    }
    
    /**
     * Clears all buffers and resets memory usage
     */
    suspend fun clearAllBuffers() = bufferPoolMutex.withLock {
        // Free all active buffers
        activeBuffers.forEach { freeBuffer(it) }
        activeBuffers.clear()
        
        // Free all pooled buffers
        while (bufferPool.isNotEmpty()) {
            freeBuffer(bufferPool.poll() ?: break)
        }
        
        totalMemoryUsage.set(0L)
        bufferCount.set(0)
        isMemoryPressure.set(false)
    }
    
    /**
     * Gets current memory statistics
     */
    fun getMemoryStatistics(): MemoryStatistics {
        val activeBuffersList = activeBuffers.toList()
        val averageAge = if (activeBuffersList.isNotEmpty()) {
            activeBuffersList.map { it.age }.average().toFloat()
        } else 0f
        
        return MemoryStatistics(
            totalMemoryUsageMB = (totalMemoryUsage.get() / (1024 * 1024)).toFloat(),
            peakMemoryUsageMB = (peakMemoryUsage.get() / (1024 * 1024)).toFloat(),
            activeBufferCount = activeBuffers.size,
            poolSize = bufferPool.size,
            totalBuffersCreated = totalBuffersCreated.get(),
            totalBuffersReused = totalBuffersReused.get(),
            totalMemoryFreedMB = (totalMemoryFreed.get() / (1024 * 1024)).toFloat(),
            isMemoryPressure = isMemoryPressure.get(),
            averageBufferAge = averageAge
        )
    }
    
    /**
     * Forces memory cleanup
     */
    suspend fun forceCleanup(): Int = bufferPoolMutex.withLock {
        performCleanup()
    }
    
    /**
     * Checks if memory pressure is high
     */
    fun isMemoryPressureHigh(): Boolean {
        val currentUsage = getCurrentMemoryUsage() / (1024 * 1024)
        return currentUsage > MAX_MEMORY_USAGE_MB * MEMORY_PRESSURE_THRESHOLD
    }
    
    /**
     * Destroys the buffer manager and cleans up resources
     */
    fun destroy() {
        cleanupJob?.cancel()
        runBlocking {
            clearAllBuffers()
        }
    }
    
    // Private helper methods
    
    private fun startCleanupRoutine() {
        cleanupJob = cleanupScope.launch {
            while (isActive) {
                delay(BUFFER_CLEANUP_INTERVAL_MS)
                bufferPoolMutex.withLock {
                    performCleanup()
                }
            }
        }
    }
    
    private fun createNewBuffer(size: Int): AudioBuffer? {
        return try {
            val buffer = AudioBuffer(
                id = generateBufferId(),
                data = FloatArray(size),
                size = size
            )
            
            totalBuffersCreated.incrementAndGet()
            bufferCount.incrementAndGet()
            
            Log.d(TAG, "Created new buffer: ${buffer.id}, size: $size")
            buffer
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory creating buffer", e)
            null
        }
    }
    
    private fun reuseExistingBuffer(size: Int): AudioBuffer? {
        val iterator = bufferPool.iterator()
        while (iterator.hasNext()) {
            val buffer = iterator.next()
            if (buffer.size >= size && buffer.timeSinceLastUse < MAX_BUFFER_AGE_MS) {
                iterator.remove()
                totalBuffersReused.incrementAndGet()
                Log.d(TAG, "Reused buffer: ${buffer.id}")
                return buffer
            }
        }
        return null
    }
    
    private fun canReuseBuffer(size: Int): Boolean {
        return bufferPool.any { it.size >= size && it.timeSinceLastUse < MAX_BUFFER_AGE_MS }
    }
    
    private fun freeBuffer(buffer: AudioBuffer) {
        totalMemoryFreed.addAndGet(buffer.memorySize)
        bufferCount.decrementAndGet()
        Log.d(TAG, "Freed buffer: ${buffer.id}")
    }
    
    private fun updateMemoryUsage(delta: Long) {
        val newUsage = totalMemoryUsage.addAndGet(delta)
        peakMemoryUsage.updateAndGet { maxOf(it, newUsage) }
        
        val usageMB = newUsage / (1024 * 1024)
        isMemoryPressure.set(usageMB > MAX_MEMORY_USAGE_MB * MEMORY_PRESSURE_THRESHOLD)
    }
    
    private fun getCurrentMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
    
    private fun performCleanup(): Int {
        val currentTime = System.currentTimeMillis()
        var cleanedCount = 0
        
        // Clean up old buffers from pool
        val iterator = bufferPool.iterator()
        while (iterator.hasNext()) {
            val buffer = iterator.next()
            if (buffer.timeSinceLastUse > MAX_BUFFER_AGE_MS) {
                iterator.remove()
                freeBuffer(buffer)
                cleanedCount++
            }
        }
        
        // Clean up inactive buffers if memory pressure is high
        if (isMemoryPressure.get()) {
            val inactiveBuffers = activeBuffers.filter { !it.isActive && it.timeSinceLastUse > MAX_BUFFER_AGE_MS }
            inactiveBuffers.forEach { buffer ->
                activeBuffers.remove(buffer)
                freeBuffer(buffer)
                cleanedCount++
            }
        }
        
        lastCleanupTime.set(currentTime)
        
        if (cleanedCount > 0) {
            Log.d(TAG, "Cleaned up $cleanedCount buffers")
        }
        
        return cleanedCount
    }
    
    private fun performEmergencyCleanup() {
        Log.w(TAG, "Performing emergency memory cleanup")
        
        // Remove oldest buffers from pool
        val buffersToRemove = bufferPool.sortedBy { it.timeSinceLastUse }
            .take(bufferPool.size / 2)
        
        buffersToRemove.forEach { buffer ->
            bufferPool.remove(buffer)
            freeBuffer(buffer)
        }
        
        // Force garbage collection
        System.gc()
    }
    
    private fun generateBufferId(): String {
        return "buffer_${System.currentTimeMillis()}_${bufferCount.get()}"
    }
    
    /**
     * Buffer pool statistics for monitoring
     */
    data class BufferPoolStatistics(
        val poolSize: Int,
        val activeBufferCount: Int,
        val totalMemoryUsageMB: Float,
        val averageBufferSize: Float,
        val bufferReuseRate: Float,
        val memoryEfficiency: Float
    )
    
    /**
     * Gets detailed buffer pool statistics
     */
    fun getBufferPoolStatistics(): BufferPoolStatistics {
        val activeBuffersList = activeBuffers.toList()
        val pooledBuffersList = bufferPool.toList()
        val allBuffers = activeBuffersList + pooledBuffersList
        
        val totalCreated = totalBuffersCreated.get()
        val totalReused = totalBuffersReused.get()
        val reuseRate = if (totalCreated > 0) totalReused.toFloat() / totalCreated else 0f
        
        val totalSize = allBuffers.sumOf { it.size }
        val averageSize = if (allBuffers.isNotEmpty()) totalSize.toFloat() / allBuffers.size else 0f
        
        val memoryEfficiency = if (totalCreated > 0) {
            (totalCreated - totalReused).toFloat() / totalCreated
        } else 1f
        
        return BufferPoolStatistics(
            poolSize = bufferPool.size,
            activeBufferCount = activeBuffers.size,
            totalMemoryUsageMB = (totalMemoryUsage.get() / (1024 * 1024)).toFloat(),
            averageBufferSize = averageSize,
            bufferReuseRate = reuseRate,
            memoryEfficiency = memoryEfficiency
        )
    }
}

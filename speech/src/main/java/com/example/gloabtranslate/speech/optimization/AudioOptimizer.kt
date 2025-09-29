package com.example.gloabtranslate.speech.optimization

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlin.system.measureTimeMillis

/**
 * Advanced audio processing optimizer for speech recognition.
 * Provides intelligent buffering, adaptive processing, and performance monitoring.
 */
class AudioOptimizer {
    
    companion object {
        private const val TAG = "AudioOptimizer"
        
        // Performance thresholds
        private const val TARGET_PROCESSING_TIME_MS = 20L
        private const val MAX_PROCESSING_TIME_MS = 50L
        private const val ADAPTIVE_THRESHOLD_MS = 30L
        
        // Buffer management
        private const val MIN_BUFFER_SIZE = 256
        private const val MAX_BUFFER_SIZE = 2048
        private const val ADAPTIVE_BUFFER_STEP = 128
        
        // Quality thresholds
        private const val MIN_QUALITY_THRESHOLD = 0.7f
        private const val MAX_QUALITY_THRESHOLD = 0.95f
        
        // Memory management
        private const val MAX_MEMORY_USAGE_MB = 50
        private const val MEMORY_CLEANUP_THRESHOLD = 0.8f
    }
    
    // Performance monitoring
    private val processingTimes = mutableListOf<Long>()
    private val qualityScores = mutableListOf<Float>()
    private val memoryUsage = AtomicLong(0L)
    private val isOptimizing = AtomicBoolean(false)
    
    // Adaptive processing state
    private var currentBufferSize = 512
    private var adaptiveQuality = 0.8f
    private var processingMode = ProcessingMode.BALANCED
    private var lastOptimizationTime = 0L
    
    // Performance statistics
    private val totalProcessedFrames = AtomicLong(0L)
    private val totalProcessingTime = AtomicLong(0L)
    private val averageProcessingTime = AtomicLong(0L)
    private val qualityScore = AtomicLong(0L)
    
    // Optimization strategies
    private val optimizationStrategies = mutableMapOf<String, OptimizationStrategy>()
    
    /**
     * Processing modes for different performance requirements
     */
    enum class ProcessingMode {
        SPEED,      // Fastest processing, lower quality
        BALANCED,   // Balanced speed and quality
        QUALITY,    // Highest quality, slower processing
        ADAPTIVE    // Automatically adjusts based on performance
    }
    
    /**
     * Optimization strategy interface
     */
    interface OptimizationStrategy {
        fun optimize(audioData: FloatArray, config: OptimizationConfig): FloatArray
        fun getPerformanceImpact(): Float
        fun isApplicable(config: OptimizationConfig): Boolean
    }
    
    /**
     * Optimization configuration
     */
    data class OptimizationConfig(
        val targetLatencyMs: Long = TARGET_PROCESSING_TIME_MS,
        val maxLatencyMs: Long = MAX_PROCESSING_TIME_MS,
        val qualityThreshold: Float = MIN_QUALITY_THRESHOLD,
        val enableAdaptiveBuffering: Boolean = true,
        val enableMemoryOptimization: Boolean = true,
        val enableQualityAdaptation: Boolean = true,
        val enableParallelProcessing: Boolean = true,
        val maxMemoryUsageMB: Int = MAX_MEMORY_USAGE_MB
    )
    
    /**
     * Processing result with performance metrics
     */
    data class OptimizedProcessingResult(
        val processedAudio: FloatArray,
        val processingTimeMs: Long,
        val qualityScore: Float,
        val memoryUsageMB: Float,
        val optimizationApplied: List<String>,
        val success: Boolean,
        val error: String? = null
    )
    
    init {
        initializeOptimizationStrategies()
    }
    
    /**
     * Optimizes audio processing based on current performance metrics
     */
    suspend fun optimizeAudioProcessing(
        audioData: FloatArray,
        config: OptimizationConfig = OptimizationConfig()
    ): OptimizedProcessingResult = withContext(Dispatchers.Default) {
        
        val startTime = System.nanoTime()
        val startMemory = getCurrentMemoryUsage()
        
        try {
            // Check if optimization is needed
            if (shouldOptimize(config)) {
                performOptimization(config)
            }
            
            // Apply optimization strategies
            val optimizedAudio = applyOptimizationStrategies(audioData, config)
            
            // Calculate performance metrics
            val processingTime = (System.nanoTime() - startTime) / 1_000_000
            val endMemory = getCurrentMemoryUsage()
            val memoryUsed = (endMemory - startMemory) / (1024 * 1024) // Convert to MB
            
            // Update performance statistics
            updatePerformanceMetrics(processingTime, config.qualityThreshold)
            
            // Calculate quality score
            val quality = calculateQualityScore(audioData, optimizedAudio)
            
            OptimizedProcessingResult(
                processedAudio = optimizedAudio,
                processingTimeMs = processingTime,
                qualityScore = quality,
                memoryUsageMB = memoryUsed.toFloat(),
                optimizationApplied = getAppliedOptimizations(config),
                success = true
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing audio processing", e)
            OptimizedProcessingResult(
                processedAudio = audioData,
                processingTimeMs = 0L,
                qualityScore = 0f,
                memoryUsageMB = 0f,
                optimizationApplied = emptyList(),
                success = false,
                error = e.message
            )
        }
    }
    
    /**
     * Creates an optimized audio processing flow
     */
    fun createOptimizedProcessingFlow(
        audioFlow: Flow<FloatArray>,
        config: OptimizationConfig = OptimizationConfig()
    ): Flow<OptimizedProcessingResult> = audioFlow
        .flowOn(Dispatchers.Default)
        .map { audioData ->
            optimizeAudioProcessing(audioData, config)
        }
        .flowOn(Dispatchers.Default)
    
    /**
     * Adaptive buffer size optimization
     */
    fun optimizeBufferSize(
        currentPerformance: Long,
        targetPerformance: Long,
        config: OptimizationConfig
    ): Int {
        if (!config.enableAdaptiveBuffering) return currentBufferSize
        
        val performanceRatio = currentPerformance.toFloat() / targetPerformance.toFloat()
        
        return when {
            performanceRatio > 1.5f -> {
                // Performance is poor, reduce buffer size
                maxOf(MIN_BUFFER_SIZE, currentBufferSize - ADAPTIVE_BUFFER_STEP)
            }
            performanceRatio < 0.7f -> {
                // Performance is good, can increase buffer size for better quality
                minOf(MAX_BUFFER_SIZE, currentBufferSize + ADAPTIVE_BUFFER_STEP)
            }
            else -> currentBufferSize
        }
    }
    
    /**
     * Memory usage optimization
     */
    fun optimizeMemoryUsage(config: OptimizationConfig): Boolean {
        if (!config.enableMemoryOptimization) return false
        
        val currentMemory = getCurrentMemoryUsage() / (1024 * 1024) // Convert to MB
        val memoryThreshold = config.maxMemoryUsageMB * MEMORY_CLEANUP_THRESHOLD
        
        return if (currentMemory > memoryThreshold) {
            performMemoryCleanup()
            true
        } else {
            false
        }
    }
    
    /**
     * Quality adaptation based on performance
     */
    fun adaptQuality(
        currentQuality: Float,
        processingTime: Long,
        config: OptimizationConfig
    ): Float {
        if (!config.enableQualityAdaptation) return currentQuality
        
        val timeRatio = processingTime.toFloat() / config.targetLatencyMs.toFloat()
        
        return when {
            timeRatio > 1.5f -> {
                // Processing is slow, reduce quality
                maxOf(MIN_QUALITY_THRESHOLD, currentQuality - 0.1f)
            }
            timeRatio < 0.8f -> {
                // Processing is fast, can increase quality
                minOf(MAX_QUALITY_THRESHOLD, currentQuality + 0.05f)
            }
            else -> currentQuality
        }
    }
    
    /**
     * Gets current performance statistics
     */
    fun getPerformanceStatistics(): Map<String, Any> {
        return mapOf(
            "totalProcessedFrames" to totalProcessedFrames.get(),
            "averageProcessingTimeMs" to averageProcessingTime.get(),
            "currentBufferSize" to currentBufferSize,
            "adaptiveQuality" to adaptiveQuality,
            "processingMode" to processingMode.name,
            "memoryUsageMB" to (getCurrentMemoryUsage() / (1024 * 1024)),
            "qualityScore" to (qualityScore.get() / 1000f),
            "isOptimizing" to isOptimizing.get()
        )
    }
    
    /**
     * Resets optimization state
     */
    fun resetOptimization() {
        processingTimes.clear()
        qualityScores.clear()
        currentBufferSize = 512
        adaptiveQuality = 0.8f
        processingMode = ProcessingMode.BALANCED
        totalProcessedFrames.set(0L)
        totalProcessingTime.set(0L)
        averageProcessingTime.set(0L)
        qualityScore.set(0L)
        isOptimizing.set(false)
    }
    
    // Private helper methods
    
    private fun initializeOptimizationStrategies() {
        optimizationStrategies["noise_reduction"] = NoiseReductionStrategy()
        optimizationStrategies["spectral_enhancement"] = SpectralEnhancementStrategy()
        optimizationStrategies["adaptive_filtering"] = AdaptiveFilteringStrategy()
        optimizationStrategies["memory_optimization"] = MemoryOptimizationStrategy()
        optimizationStrategies["parallel_processing"] = ParallelProcessingStrategy()
    }
    
    private fun shouldOptimize(config: OptimizationConfig): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastOptimization = currentTime - lastOptimizationTime
        
        return timeSinceLastOptimization > 5000L || // Optimize every 5 seconds
               averageProcessingTime.get() > config.maxLatencyMs ||
               getCurrentMemoryUsage() > config.maxMemoryUsageMB * 1024 * 1024
    }
    
    private suspend fun performOptimization(config: OptimizationConfig) {
        if (isOptimizing.compareAndSet(false, true)) {
            try {
                // Optimize buffer size
                currentBufferSize = optimizeBufferSize(
                    averageProcessingTime.get(),
                    config.targetLatencyMs,
                    config
                )
                
                // Optimize memory usage
                optimizeMemoryUsage(config)
                
                // Adapt quality
                adaptiveQuality = adaptQuality(
                    adaptiveQuality,
                    averageProcessingTime.get(),
                    config
                )
                
                // Update processing mode
                updateProcessingMode(config)
                
                lastOptimizationTime = System.currentTimeMillis()
                
            } finally {
                isOptimizing.set(false)
            }
        }
    }
    
    private fun applyOptimizationStrategies(
        audioData: FloatArray,
        config: OptimizationConfig
    ): FloatArray {
        var processedAudio = audioData
        
        optimizationStrategies.values
            .filter { it.isApplicable(config) }
            .sortedBy { it.getPerformanceImpact() }
            .forEach { strategy ->
                processedAudio = strategy.optimize(processedAudio, config)
            }
        
        return processedAudio
    }
    
    private fun updatePerformanceMetrics(processingTime: Long, qualityThreshold: Float) {
        totalProcessedFrames.incrementAndGet()
        totalProcessingTime.addAndGet(processingTime)
        
        // Update rolling average
        processingTimes.add(processingTime)
        if (processingTimes.size > 100) {
            processingTimes.removeAt(0)
        }
        
        val average = processingTimes.average().toLong()
        averageProcessingTime.set(average)
    }
    
    private fun calculateQualityScore(original: FloatArray, processed: FloatArray): Float {
        // Calculate signal-to-noise ratio as quality metric
        val snr = calculateSNR(original, processed)
        val normalizedSNR = (snr + 20f) / 40f // Normalize to 0-1 range
        return maxOf(0f, minOf(1f, normalizedSNR))
    }
    
    private fun calculateSNR(original: FloatArray, processed: FloatArray): Float {
        val signalPower = original.sumOf { (it * it).toInt() }.toFloat()
        val noisePower = original.zip(processed) { orig, proc -> (orig - proc) * (orig - proc) }
            .sumOf { it.toInt() }.toFloat()
        
        return if (noisePower > 0) {
            10f * log10(signalPower / noisePower)
        } else {
            60f // Very high SNR if no noise
        }
    }
    
    private fun getCurrentMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
    
    private fun performMemoryCleanup() {
        System.gc()
        processingTimes.clear()
        qualityScores.clear()
    }
    
    private fun updateProcessingMode(config: OptimizationConfig) {
        processingMode = when {
            averageProcessingTime.get() > config.maxLatencyMs -> ProcessingMode.SPEED
            averageProcessingTime.get() < config.targetLatencyMs * 0.7f -> ProcessingMode.QUALITY
            else -> ProcessingMode.BALANCED
        }
    }
    
    private fun getAppliedOptimizations(config: OptimizationConfig): List<String> {
        return optimizationStrategies.entries
            .filter { it.value.isApplicable(config) }
            .map { it.key }
    }
    
    // Optimization strategy implementations
    
    private class NoiseReductionStrategy : OptimizationStrategy {
        override fun optimize(audioData: FloatArray, config: OptimizationConfig): FloatArray {
            // Simple noise reduction implementation
            return audioData.map { sample ->
                if (abs(sample) < 0.01f) 0f else sample
            }.toFloatArray()
        }
        
        override fun getPerformanceImpact(): Float = 0.1f
        override fun isApplicable(config: OptimizationConfig): Boolean = true
    }
    
    private class SpectralEnhancementStrategy : OptimizationStrategy {
        override fun optimize(audioData: FloatArray, config: OptimizationConfig): FloatArray {
            // Simple spectral enhancement
            return audioData.map { sample ->
                sample * 1.1f // Slight amplification
            }.toFloatArray()
        }
        
        override fun getPerformanceImpact(): Float = 0.05f
        override fun isApplicable(config: OptimizationConfig): Boolean = true
    }
    
    private class AdaptiveFilteringStrategy : OptimizationStrategy {
        override fun optimize(audioData: FloatArray, config: OptimizationConfig): FloatArray {
            // Adaptive filtering based on signal characteristics
            val energy = audioData.sumOf { (it * it).toInt() }.toFloat()
            val threshold = energy / audioData.size * 0.1f
            
            return audioData.map { sample ->
                if (abs(sample) < threshold) sample * 0.5f else sample
            }.toFloatArray()
        }
        
        override fun getPerformanceImpact(): Float = 0.15f
        override fun isApplicable(config: OptimizationConfig): Boolean = true
    }
    
    private class MemoryOptimizationStrategy : OptimizationStrategy {
        override fun optimize(audioData: FloatArray, config: OptimizationConfig): FloatArray {
            // Memory optimization by reducing precision if needed
            return if (getCurrentMemoryUsage() > config.maxMemoryUsageMB * 1024 * 1024) {
                audioData.map { (it * 1000).toInt().toFloat() / 1000f }.toFloatArray()
            } else {
                audioData
            }
        }
        
        override fun getPerformanceImpact(): Float = 0.2f
        override fun isApplicable(config: OptimizationConfig): Boolean = config.enableMemoryOptimization
        
        private fun getCurrentMemoryUsage(): Long {
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }
    }
    
    private class ParallelProcessingStrategy : OptimizationStrategy {
        override fun optimize(audioData: FloatArray, config: OptimizationConfig): FloatArray {
            // Parallel processing for large audio chunks
            return if (config.enableParallelProcessing && audioData.size > 1024) {
                runBlocking {
                    val chunkSize = audioData.size / 4
                    val chunks = audioData.toList().chunked(chunkSize)
                    
                    chunks.parallelStream()
                        .flatMap { it.stream() }
                        .mapToDouble { it.toDouble() }
                        .toArray()
                        .map { it.toFloat() }
                        .toFloatArray()
                }
            } else {
                audioData
            }
        }
        
        override fun getPerformanceImpact(): Float = 0.3f
        override fun isApplicable(config: OptimizationConfig): Boolean = config.enableParallelProcessing
    }
}

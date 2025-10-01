package com.example.gloabtranslate.speech

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import com.example.gloabtranslate.core.data.config.AudioConfig as PreferencesAudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlin.math.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.example.gloabtranslate.speech.AudioRecorder.AudioFrame
import com.example.gloabtranslate.speech.config.AudioConfigurationObserver

/**
 * Real-time audio processor for speech recognition and analysis.
 * Provides audio filtering, noise reduction, feature extraction, and buffering capabilities.
 */
class AudioProcessor(
    private val context: Context,
    private val platform: AudioProcessorPlatform = AndroidAudioProcessorPlatform(context)
) {
    
    companion object {
        private const val TAG = "AudioProcessor"
        
        // Audio processing constants
        private const val DEFAULT_SAMPLE_RATE = 16000
        private const val DEFAULT_BUFFER_SIZE = 1024
        private const val DEFAULT_CHUNK_SIZE = 320 // 20ms at 16kHz
        private const val SILENCE_THRESHOLD = 0.01f
        private const val NOISE_REDUCTION_FACTOR = 0.8f
        private const val PREEMPHASIS_COEFFICIENT = 0.97f
        
        // FFT constants for spectral analysis
        private const val FFT_SIZE = 512
        private const val MEL_BINS = 26
        private const val MFCC_COEFFICIENTS = 13
    }
    
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var bufferSize = DEFAULT_BUFFER_SIZE
    private var chunkSize = DEFAULT_CHUNK_SIZE
    private val configurationManager = ConfigurationManager.getInstance(context)
    private val configurationObserver = platform.createConfigurationObserver()
    private val processorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activePreferencesConfig: PreferencesAudioConfig? = null
    private var activeProcessingConfig: ProcessingConfig = ProcessingConfig()

    init {
        configurationObserver.setOnConfigChanged { config: PreferencesAudioConfig ->
            applyNonCriticalConfiguration(config)
        }
        configurationObserver.setOnCriticalConfigChanged { config: PreferencesAudioConfig ->
            applyCriticalConfiguration(config)
        }
        configurationObserver.start()
    }
    
    // Audio processing buffers
    private val audioBuffer = mutableListOf<Float>()
    private val processedBuffer = mutableListOf<Float>()
    private val noiseProfile = mutableListOf<Float>()
    
    // VAD (Voice Activity Detection) state
    private var isVoiceDetected = false
    private var silenceCounter = 0
    private val maxSilenceFrames = 10
    
    // Audio analysis data
    private var energyLevel = 0f
    private var spectralCentroid = 0f
    private var zeroCrossingRate = 0f
    
    /**
     * Configuration for audio processing
     */
    data class ProcessingConfig(
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val bufferSize: Int = DEFAULT_BUFFER_SIZE,
        val chunkSize: Int = DEFAULT_CHUNK_SIZE,
        val enableNoiseReduction: Boolean = true,
        val enablePreemphasis: Boolean = true,
        val enableVAD: Boolean = true,
        val silenceThreshold: Float = SILENCE_THRESHOLD,
        val noiseReductionFactor: Float = NOISE_REDUCTION_FACTOR,
        val preemphasisCoeff: Float = PREEMPHASIS_COEFFICIENT
    )
    
    /**
     * Result of audio processing operations
     */
    data class ProcessingResult(
        val success: Boolean,
        val processedAudio: FloatArray? = null,
        val features: AudioFeatures? = null,
        val vadResult: VadResult? = null,
        val error: String? = null
    )
    
    /**
     * Audio features extracted from the signal
     */
    data class AudioFeatures(
        val energy: Float,
        val spectralCentroid: Float,
        val zeroCrossingRate: Float,
        val mfccCoefficients: FloatArray? = null,
        val melSpectrum: FloatArray? = null,
        val spectralRolloff: Float = 0f,
        val spectralBandwidth: Float = 0f
    )
    
    /**
     * Voice Activity Detection result
     */
    data class VadResult(
        val isVoice: Boolean,
        val confidence: Float,
        val energy: Float,
        val silenceFrames: Int
    )
    
    /**
     * Audio frame with processing metadata
     */
    data class ProcessedAudioFrame(
        val audioData: FloatArray,
        val timestamp: Long,
        val features: AudioFeatures,
        val vadResult: VadResult,
        val isSilence: Boolean
    )
    
    private fun applyNonCriticalConfiguration(config: PreferencesAudioConfig) {
        activePreferencesConfig = config
        val buffer = if (config.audioBufferSize > 0) config.audioBufferSize else activeProcessingConfig.bufferSize
        val chunk = minOf(buffer, activeProcessingConfig.chunkSize.coerceAtLeast(DEFAULT_CHUNK_SIZE))
        activeProcessingConfig = activeProcessingConfig.copy(
            sampleRate = config.sampleRate,
            bufferSize = buffer,
            chunkSize = chunk,
            enableNoiseReduction = config.enableNoiseReduction && activeProcessingConfig.enableNoiseReduction
        )
        sampleRate = activeProcessingConfig.sampleRate
        bufferSize = activeProcessingConfig.bufferSize
        chunkSize = activeProcessingConfig.chunkSize
    }

    private fun applyCriticalConfiguration(config: PreferencesAudioConfig) {
        activePreferencesConfig = config
        processorScope.launch {
            sampleRate = config.sampleRate
            bufferSize = if (config.audioBufferSize > 0) config.audioBufferSize else DEFAULT_BUFFER_SIZE
            chunkSize = minOf(bufferSize, DEFAULT_CHUNK_SIZE)
            activeProcessingConfig = activeProcessingConfig.copy(
                sampleRate = sampleRate,
                bufferSize = bufferSize,
                chunkSize = chunkSize,
                enableNoiseReduction = config.enableNoiseReduction
            )
        }
    }

    /**
     * Initializes the audio processor with configuration
     */
    fun getProcessingConfig(): ProcessingConfig = activeProcessingConfig

    fun initialize(config: ProcessingConfig = getProcessingConfig()): ProcessingResult {
        return try {
            val preferences = activePreferencesConfig ?: platform.getCurrentAudioConfig()

            val resolvedBufferSize = (if (preferences.audioBufferSize > 0) {
                preferences.audioBufferSize
            } else {
                config.bufferSize
            }).coerceAtLeast(DEFAULT_BUFFER_SIZE)

            val resolvedChunkSize = if (resolvedBufferSize > 0) {
                minOf(resolvedBufferSize, config.chunkSize.coerceAtLeast(DEFAULT_CHUNK_SIZE))
            } else {
                config.chunkSize.coerceAtLeast(DEFAULT_CHUNK_SIZE)
            }

            val resolvedConfig = config.copy(
                sampleRate = preferences.sampleRate,
                bufferSize = resolvedBufferSize,
                chunkSize = resolvedChunkSize,
                enableNoiseReduction = preferences.enableNoiseReduction && config.enableNoiseReduction
            )

            sampleRate = resolvedConfig.sampleRate
            bufferSize = resolvedConfig.bufferSize
            chunkSize = resolvedConfig.chunkSize

            audioBuffer.clear()
            processedBuffer.clear()
            noiseProfile.clear()

            isVoiceDetected = false
            silenceCounter = 0

            activeProcessingConfig = resolvedConfig
            activePreferencesConfig = preferences
            configurationObserver.updateCurrentConfig(preferences)

            Log.d(TAG, "AudioProcessor initialized with sample rate: ${sampleRate}Hz")
            ProcessingResult(success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioProcessor", e)
            ProcessingResult(
                success = false,
                error = "Initialization failed: ${e.message}"
            )
        }
    }
    
    /**
     * Processes raw audio data and returns processed audio with features
     */
    fun processAudioFrame(
        audioData: ByteArray,
        config: ProcessingConfig = getProcessingConfig()
    ): ProcessingResult {
        try {
            // Convert byte array to float array
            val floatData = bytesToFloatArray(audioData)
            
            // Apply preprocessing
            val preprocessedData = preprocessAudio(floatData, config)
            
            // Extract audio features
            val features = extractAudioFeatures(preprocessedData)
            
            // Perform Voice Activity Detection
            val vadResult = performVAD(preprocessedData, features.energy, config)
            
            // Apply noise reduction if enabled
            val processedData = if (config.enableNoiseReduction) {
                applyNoiseReduction(preprocessedData, config)
            } else {
                preprocessedData
            }
            
            return ProcessingResult(
                success = true,
                processedAudio = processedData,
                features = features,
                vadResult = vadResult
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio frame", e)
            return ProcessingResult(
                success = false,
                error = "Processing failed: ${e.message}"
            )
        }
    }
    
    /**
     * Creates a Flow for real-time audio processing
     */
    fun createProcessingFlow(
        audioFlow: Flow<AudioFrame>,
        config: ProcessingConfig = getProcessingConfig()
    ): Flow<ProcessedAudioFrame> = audioFlow.transform { audioFrame ->
        val processingResult = processAudioFrame(audioFrame.data, config)
        
        if (processingResult.success && 
            processingResult.processedAudio != null && 
            processingResult.features != null && 
            processingResult.vadResult != null) {
            
            emit(ProcessedAudioFrame(
                audioData = processingResult.processedAudio,
                timestamp = audioFrame.timestamp,
                features = processingResult.features,
                vadResult = processingResult.vadResult,
                isSilence = !processingResult.vadResult.isVoice
            ))
        }
    }.flowOn(Dispatchers.Default)
    
    /**
     * Processes audio chunks for speech recognition
     */
    fun processAudioChunk(
        audioData: FloatArray,
        config: ProcessingConfig = getProcessingConfig()
    ): Flow<ProcessedAudioFrame> = flow {
        try {
            // Add to buffer
            audioBuffer.addAll(audioData.toList())
            
            // Process in chunks
            while (audioBuffer.size >= chunkSize) {
                val chunk = FloatArray(chunkSize)
                for (i in 0 until chunkSize) {
                    chunk[i] = audioBuffer.removeAt(0)
                }
                
                val processingResult = processAudioFrame(
                    floatArrayToBytes(chunk), 
                    config
                )
                
                if (processingResult.success && 
                    processingResult.processedAudio != null && 
                    processingResult.features != null && 
                    processingResult.vadResult != null) {
                    
                    emit(ProcessedAudioFrame(
                        audioData = processingResult.processedAudio,
                        timestamp = System.currentTimeMillis(),
                        features = processingResult.features,
                        vadResult = processingResult.vadResult,
                        isSilence = !processingResult.vadResult.isVoice
                    ))
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio chunk", e)
            throw e
        }
    }.flowOn(Dispatchers.Default)
    
    /**
     * Preprocesses audio data (preemphasis, normalization)
     */
    private fun preprocessAudio(
        audioData: FloatArray,
        config: ProcessingConfig
    ): FloatArray {
        var processed = audioData.copyOf()
        
        // Apply preemphasis filter
        if (config.enablePreemphasis) {
            processed = applyPreemphasis(processed, config.preemphasisCoeff)
        }
        
        // Normalize audio
        processed = normalizeAudio(processed)
        
        return processed
    }
    
    /**
     * Applies preemphasis filter to enhance high frequencies
     */
    private fun applyPreemphasis(audioData: FloatArray, coeff: Float): FloatArray {
        val result = FloatArray(audioData.size)
        result[0] = audioData[0]
        
        for (i in 1 until audioData.size) {
            result[i] = audioData[i] - coeff * audioData[i - 1]
        }
        
        return result
    }
    
    /**
     * Normalizes audio data to [-1, 1] range
     */
    private fun normalizeAudio(audioData: FloatArray): FloatArray {
        val maxVal = audioData.maxOrNull() ?: 1f
        val minVal = audioData.minOrNull() ?: -1f
        val maxAbs = maxOf(abs(maxVal), abs(minVal))
        
        if (maxAbs > 0f) {
            val scale = 1f / maxAbs
            return audioData.map { it * scale }.toFloatArray()
        }
        
        return audioData
    }
    
    /**
     * Applies noise reduction using spectral subtraction
     */
    private fun applyNoiseReduction(
        audioData: FloatArray,
        config: ProcessingConfig
    ): FloatArray {
        // Simple noise reduction based on energy threshold
        val threshold = config.silenceThreshold * config.noiseReductionFactor
        
        return audioData.map { sample ->
            if (abs(sample) < threshold) {
                sample * 0.1f // Reduce noise
            } else {
                sample
            }
        }.toFloatArray()
    }
    
    /**
     * Extracts audio features from the signal
     */
    private fun extractAudioFeatures(audioData: FloatArray): AudioFeatures {
        // Calculate energy
        val energy = calculateEnergy(audioData)
        
        // Calculate spectral centroid
        val spectralCentroid = calculateSpectralCentroid(audioData)
        
        // Calculate zero crossing rate
        val zcr = calculateZeroCrossingRate(audioData)
        
        // Calculate spectral rolloff
        val spectralRolloff = calculateSpectralRolloff(audioData)
        
        // Calculate spectral bandwidth
        val spectralBandwidth = calculateSpectralBandwidth(audioData)
        
        // Calculate MFCC coefficients (simplified)
        val mfcc = calculateMFCC(audioData)
        
        // Calculate Mel spectrum (simplified)
        val melSpectrum = calculateMelSpectrum(audioData)
        
        return AudioFeatures(
            energy = energy,
            spectralCentroid = spectralCentroid,
            zeroCrossingRate = zcr,
            mfccCoefficients = mfcc,
            melSpectrum = melSpectrum,
            spectralRolloff = spectralRolloff,
            spectralBandwidth = spectralBandwidth
        )
    }
    
    /**
     * Performs Voice Activity Detection
     */
    private fun performVAD(
        audioData: FloatArray,
        energy: Float,
        config: ProcessingConfig
    ): VadResult {
        val isVoice = energy > config.silenceThreshold
        val confidence = minOf(1f, energy / config.silenceThreshold)
        
        if (isVoice) {
            silenceCounter = 0
            isVoiceDetected = true
        } else {
            silenceCounter++
            if (silenceCounter > maxSilenceFrames) {
                isVoiceDetected = false
            }
        }
        
        return VadResult(
            isVoice = isVoice,  // Return immediate voice detection, not persistent state
            confidence = confidence,
            energy = energy,
            silenceFrames = silenceCounter
        )
    }
    
    /**
     * Calculates the energy of the audio signal
     */
    private fun calculateEnergy(audioData: FloatArray): Float {
        var sum = 0f
        for (sample in audioData) {
            sum += sample * sample
        }
        return sqrt(sum / audioData.size)
    }
    
    /**
     * Calculates the spectral centroid
     */
    private fun calculateSpectralCentroid(audioData: FloatArray): Float {
        // Simplified spectral centroid calculation
        val fft = performFFT(audioData)
        var weightedSum = 0f
        var magnitudeSum = 0f
        
        for (i in 0 until fft.size / 2) {
            val magnitude = sqrt(fft[i * 2] * fft[i * 2] + fft[i * 2 + 1] * fft[i * 2 + 1])
            weightedSum += i * magnitude
            magnitudeSum += magnitude
        }
        
        return if (magnitudeSum > 0) weightedSum / magnitudeSum else 0f
    }
    
    /**
     * Calculates the zero crossing rate
     */
    private fun calculateZeroCrossingRate(audioData: FloatArray): Float {
        var crossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] >= 0) != (audioData[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / (audioData.size - 1)
    }
    
    /**
     * Calculates spectral rolloff
     */
    private fun calculateSpectralRolloff(audioData: FloatArray): Float {
        val fft = performFFT(audioData)
        val magnitudes = FloatArray(fft.size / 2)
        
        for (i in 0 until magnitudes.size) {
            magnitudes[i] = sqrt(fft[i * 2] * fft[i * 2] + fft[i * 2 + 1] * fft[i * 2 + 1])
        }
        
        val totalEnergy = magnitudes.sum()
        val threshold = 0.85f * totalEnergy
        var cumulativeEnergy = 0f
        
        for (i in magnitudes.indices) {
            cumulativeEnergy += magnitudes[i]
            if (cumulativeEnergy >= threshold) {
                return i.toFloat() / magnitudes.size
            }
        }
        
        return 1f
    }
    
    /**
     * Calculates spectral bandwidth
     */
    private fun calculateSpectralBandwidth(audioData: FloatArray): Float {
        val spectralCentroid = calculateSpectralCentroid(audioData)
        val fft = performFFT(audioData)
        val magnitudes = FloatArray(fft.size / 2)
        
        for (i in 0 until magnitudes.size) {
            magnitudes[i] = sqrt(fft[i * 2] * fft[i * 2] + fft[i * 2 + 1] * fft[i * 2 + 1])
        }
        
        var weightedSum = 0f
        var magnitudeSum = 0f
        
        for (i in magnitudes.indices) {
            val diff = (i - spectralCentroid).toFloat()
            weightedSum += diff * diff * magnitudes[i]
            magnitudeSum += magnitudes[i]
        }
        
        return if (magnitudeSum > 0) sqrt(weightedSum / magnitudeSum) else 0f
    }
    
    /**
     * Calculates MFCC coefficients (simplified implementation)
     */
    private fun calculateMFCC(audioData: FloatArray): FloatArray {
        // Simplified MFCC calculation
        val fft = performFFT(audioData)
        val melSpectrum = calculateMelSpectrum(audioData)
        
        // Apply DCT (simplified)
        val mfcc = FloatArray(MFCC_COEFFICIENTS)
        for (i in 0 until MFCC_COEFFICIENTS) {
            var sum = 0f
            for (j in melSpectrum.indices) {
                sum += melSpectrum[j] * cos(PI * i * (2 * j + 1) / (2 * melSpectrum.size)).toFloat()
            }
            mfcc[i] = sum
        }
        
        return mfcc
    }
    
    /**
     * Calculates Mel spectrum (simplified implementation)
     */
    private fun calculateMelSpectrum(audioData: FloatArray): FloatArray {
        val fft = performFFT(audioData)
        val melSpectrum = FloatArray(MEL_BINS)
        
        // Simplified mel filter bank
        for (i in 0 until MEL_BINS) {
            var sum = 0f
            val start = i * fft.size / (2 * MEL_BINS)
            val end = (i + 1) * fft.size / (2 * MEL_BINS)
            
            for (j in start until end) {
                if (j * 2 + 1 < fft.size) {
                    val magnitude = sqrt(fft[j * 2] * fft[j * 2] + fft[j * 2 + 1] * fft[j * 2 + 1])
                    sum += magnitude
                }
            }
            
            melSpectrum[i] = sum
        }
        
        return melSpectrum
    }
    
    /**
     * Performs FFT on audio data (simplified implementation)
     */
    private fun performFFT(audioData: FloatArray): FloatArray {
        // Simplified FFT implementation
        val n = audioData.size
        val fft = FloatArray(n * 2) // Real and imaginary parts
        
        // Copy real data
        for (i in 0 until n) {
            fft[i * 2] = audioData[i]
            fft[i * 2 + 1] = 0f
        }
        
        // Simple FFT implementation (not optimized)
        for (i in 0 until n / 2) {
            val angle = -2 * PI * i / n
            val cos = cos(angle).toFloat()
            val sin = sin(angle).toFloat()
            
            var realSum = 0f
            var imagSum = 0f
            
            for (j in 0 until n) {
                val real = fft[j * 2]
                val imag = fft[j * 2 + 1]
                
                realSum += real * cos - imag * sin
                imagSum += real * sin + imag * cos
            }
            
            fft[i * 2] = realSum
            fft[i * 2 + 1] = imagSum
        }
        
        return fft
    }

    fun cleanup() {
        configurationObserver.cleanup()
        processorScope.cancel()
        audioBuffer.clear()
        processedBuffer.clear()
        noiseProfile.clear()
        platform.cleanup()
    }

    /**
     * Converts byte array to float array
     */
    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        val shorts = ShortArray(bytes.size / 2)
        buffer.asShortBuffer().get(shorts)
        
        return FloatArray(shorts.size) { shorts[it] / 32768f }
    }
    
    /**
     * Converts float array to byte array
     */
    private fun floatArrayToBytes(floatArray: FloatArray): ByteArray {
        val shorts = ShortArray(floatArray.size) { (floatArray[it] * 32767).toInt().toShort() }
        val buffer = ByteBuffer.allocate(shorts.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(shorts)
        return buffer.array()
    }
    
    /**
     * Gets current processing statistics
     */
    fun getProcessingStats(): Map<String, Any> {
        return mapOf(
            "sampleRate" to sampleRate,
            "bufferSize" to bufferSize,
            "chunkSize" to chunkSize,
            "isVoiceDetected" to isVoiceDetected,
            "silenceCounter" to silenceCounter,
            "energyLevel" to energyLevel,
            "spectralCentroid" to spectralCentroid,
            "zeroCrossingRate" to zeroCrossingRate,
            "audioBufferSize" to audioBuffer.size,
            "processedBufferSize" to processedBuffer.size
        )
    }
    
    /**
     * Clears all buffers and resets state
     */
    fun reset() {
        audioBuffer.clear()
        processedBuffer.clear()
        noiseProfile.clear()
        
        isVoiceDetected = false
        silenceCounter = 0
        energyLevel = 0f
        spectralCentroid = 0f
        zeroCrossingRate = 0f
        
        Log.d(TAG, "AudioProcessor reset")
    }
}

interface AudioProcessorPlatform {
    fun getCurrentAudioConfig(): PreferencesAudioConfig
    fun createConfigurationObserver(): AudioConfigurationObserver
    fun cleanup()
}

internal class AndroidAudioProcessorPlatform(private val context: Context) : AudioProcessorPlatform {
    override fun getCurrentAudioConfig(): PreferencesAudioConfig {
        return kotlinx.coroutines.runBlocking { 
            ConfigurationManager.getInstance(context).currentAudioConfig()
        }
    }

    override fun createConfigurationObserver(): AudioConfigurationObserver {
        return AudioConfigurationObserver(context)
    }

    override fun cleanup() {
        // Platform-specific cleanup if needed
    }
}

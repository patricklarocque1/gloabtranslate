package com.example.gloabtranslate.speech

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.*

/**
 * Audio filtering and noise reduction implementation.
 * Provides various audio processing filters including noise reduction, spectral subtraction,
 * Wiener filtering, and adaptive filtering techniques.
 */
class AudioFilter {
    
    companion object {
        private const val TAG = "AudioFilter"
        
        // Filter constants
        private const val DEFAULT_FFT_SIZE = 512
        private const val DEFAULT_OVERLAP = 0.5f
        private const val DEFAULT_ALPHA = 0.9f
        private const val DEFAULT_BETA = 0.1f
        private const val DEFAULT_GAMMA = 0.1f
        private const val DEFAULT_MU = 0.01f
        private const val DEFAULT_LAMBDA = 0.99f
        
        // Noise estimation parameters
        private const val NOISE_ESTIMATION_FRAMES = 10
        private const val MIN_NOISE_LEVEL = 0.001f
        private const val MAX_NOISE_LEVEL = 0.1f
        
        // Spectral subtraction parameters
        private const val SPECTRAL_OVERSUBTRACTION_FACTOR = 2.0f
        private const val SPECTRAL_FLOOR_FACTOR = 0.002f
        private const val SPECTRAL_ALPHA = 0.9f
        private const val SPECTRAL_BETA = 0.1f
    }
    
    private var fftSize = DEFAULT_FFT_SIZE
    private var overlap = DEFAULT_OVERLAP
    private var hopSize = 0
    private var windowSize = 0
    
    // Noise profile and estimation
    private val noiseProfile = mutableListOf<Float>()
    private val noiseSpectrum = FloatArray(DEFAULT_FFT_SIZE / 2 + 1)
    private var noiseEstimationFrames = 0
    private var isNoiseProfileReady = false
    
    // Filter state
    private val previousFrame = FloatArray(DEFAULT_FFT_SIZE)
    private val previousNoiseSpectrum = FloatArray(DEFAULT_FFT_SIZE / 2 + 1)
    private val adaptiveFilterCoefficients = FloatArray(DEFAULT_FFT_SIZE)
    
    // Performance tracking
    private var totalProcessedFrames = 0
    private var totalProcessingTime = 0L
    
    /**
     * Configuration for audio filtering
     */
    data class FilterConfig(
        val fftSize: Int = DEFAULT_FFT_SIZE,
        val overlap: Float = DEFAULT_OVERLAP,
        val enableNoiseReduction: Boolean = true,
        val enableSpectralSubtraction: Boolean = true,
        val enableWienerFilter: Boolean = false,
        val enableAdaptiveFilter: Boolean = false,
        val enableHighPassFilter: Boolean = true,
        val enableLowPassFilter: Boolean = false,
        val enableBandPassFilter: Boolean = false,
        
        // Noise reduction parameters
        val noiseReductionFactor: Float = 0.8f,
        val noiseEstimationFrames: Int = NOISE_ESTIMATION_FRAMES,
        val minNoiseLevel: Float = MIN_NOISE_LEVEL,
        val maxNoiseLevel: Float = MAX_NOISE_LEVEL,
        
        // Spectral subtraction parameters
        val spectralOversubtractionFactor: Float = SPECTRAL_OVERSUBTRACTION_FACTOR,
        val spectralFloorFactor: Float = SPECTRAL_FLOOR_FACTOR,
        val spectralAlpha: Float = SPECTRAL_ALPHA,
        val spectralBeta: Float = SPECTRAL_BETA,
        
        // Wiener filter parameters
        val wienerAlpha: Float = DEFAULT_ALPHA,
        val wienerBeta: Float = DEFAULT_BETA,
        
        // Adaptive filter parameters
        val adaptiveMu: Float = DEFAULT_MU,
        val adaptiveLambda: Float = DEFAULT_LAMBDA,
        
        // Filter cutoff frequencies (Hz)
        val highPassCutoff: Float = 80f,
        val lowPassCutoff: Float = 8000f,
        val bandPassLow: Float = 300f,
        val bandPassHigh: Float = 3400f,
        
        // Sample rate for frequency calculations
        val sampleRate: Int = 16000
    )
    
    /**
     * Result of audio filtering operations
     */
    data class FilterResult(
        val success: Boolean,
        val filteredAudio: FloatArray? = null,
        val noiseLevel: Float = 0f,
        val snr: Float = 0f,
        val processingTime: Long = 0,
        val error: String? = null
    )
    
    /**
     * Audio frame with filtering metadata
     */
    data class FilteredAudioFrame(
        val audioData: FloatArray,
        val timestamp: Long,
        val noiseLevel: Float,
        val snr: Float,
        val filterType: String,
        val processingTime: Long
    )
    
    /**
     * Initializes the audio filter with configuration
     */
    fun initialize(config: FilterConfig): FilterResult {
        try {
            fftSize = config.fftSize
            overlap = config.overlap
            hopSize = (fftSize * (1 - overlap)).toInt()
            windowSize = fftSize
            
            // Initialize arrays
            noiseSpectrum.fill(0f)
            previousFrame.fill(0f)
            previousNoiseSpectrum.fill(0f)
            adaptiveFilterCoefficients.fill(0f)
            
            // Reset state
            noiseProfile.clear()
            noiseEstimationFrames = 0
            isNoiseProfileReady = false
            totalProcessedFrames = 0
            totalProcessingTime = 0L
            
            Log.d(TAG, "AudioFilter initialized with FFT size: $fftSize, overlap: $overlap")
            return FilterResult(success = true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioFilter", e)
            return FilterResult(
                success = false,
                error = "Initialization failed: ${e.message}"
            )
        }
    }
    
    /**
     * Applies filtering to audio data
     */
    fun applyFilter(
        audioData: FloatArray,
        config: FilterConfig
    ): FilterResult {
        val startTime = System.nanoTime()
        
        try {
            var filteredData = audioData.copyOf()
            
            // Apply high-pass filter if enabled
            if (config.enableHighPassFilter) {
                filteredData = applyHighPassFilter(filteredData, config)
            }
            
            // Apply low-pass filter if enabled
            if (config.enableLowPassFilter) {
                filteredData = applyLowPassFilter(filteredData, config)
            }
            
            // Apply band-pass filter if enabled
            if (config.enableBandPassFilter) {
                filteredData = applyBandPassFilter(filteredData, config)
            }
            
            // Apply noise reduction if enabled
            if (config.enableNoiseReduction) {
                filteredData = applyNoiseReduction(filteredData, config)
            }
            
            // Apply spectral subtraction if enabled
            if (config.enableSpectralSubtraction) {
                filteredData = applySpectralSubtraction(filteredData, config)
            }
            
            // Apply Wiener filter if enabled
            if (config.enableWienerFilter) {
                filteredData = applyWienerFilter(filteredData, config)
            }
            
            // Apply adaptive filter if enabled
            if (config.enableAdaptiveFilter) {
                filteredData = applyAdaptiveFilter(filteredData, config)
            }
            
            val processingTime = (System.nanoTime() - startTime) / 1_000_000 // Convert to ms
            totalProcessedFrames++
            totalProcessingTime += processingTime
            
            // Calculate noise level and SNR
            val noiseLevel = calculateNoiseLevel(filteredData)
            val snr = calculateSNR(audioData, filteredData)
            
            return FilterResult(
                success = true,
                filteredAudio = filteredData,
                noiseLevel = noiseLevel,
                snr = snr,
                processingTime = processingTime
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error applying audio filter", e)
            return FilterResult(
                success = false,
                error = "Filtering failed: ${e.message}"
            )
        }
    }
    
    /**
     * Creates a Flow for real-time audio filtering
     */
    fun createFilterFlow(
        audioFlow: Flow<FloatArray>,
        config: FilterConfig
    ): Flow<FilteredAudioFrame> = flow {
        audioFlow.collect { audioData ->
            val filterResult = applyFilter(audioData, config)
            
            if (filterResult.success && filterResult.filteredAudio != null) {
                emit(FilteredAudioFrame(
                    audioData = filterResult.filteredAudio,
                    timestamp = System.currentTimeMillis(),
                    noiseLevel = filterResult.noiseLevel,
                    snr = filterResult.snr,
                    filterType = getActiveFilterTypes(config),
                    processingTime = filterResult.processingTime
                ))
            }
        }
    }.flowOn(Dispatchers.Default)
    
    /**
     * Estimates noise profile from audio data
     */
    fun estimateNoiseProfile(
        audioData: FloatArray,
        config: FilterConfig
    ): FilterResult {
        try {
            val fft = performFFT(audioData)
            val magnitudeSpectrum = calculateMagnitudeSpectrum(fft)
            
            // Update noise spectrum using exponential averaging
            val alpha = 1.0f / config.noiseEstimationFrames
            for (i in magnitudeSpectrum.indices) {
                noiseSpectrum[i] = (1 - alpha) * noiseSpectrum[i] + alpha * magnitudeSpectrum[i]
            }
            
            noiseEstimationFrames++
            
            if (noiseEstimationFrames >= config.noiseEstimationFrames) {
                isNoiseProfileReady = true
                Log.d(TAG, "Noise profile estimated successfully")
            }
            
            return FilterResult(
                success = true,
                noiseLevel = calculateNoiseLevel(audioData)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error estimating noise profile", e)
            return FilterResult(
                success = false,
                error = "Noise estimation failed: ${e.message}"
            )
        }
    }
    
    /**
     * Applies high-pass filter
     */
    private fun applyHighPassFilter(
        audioData: FloatArray,
        config: FilterConfig
    ): FloatArray {
        val cutoff = config.highPassCutoff / config.sampleRate
        val rc = 1.0f / (2 * PI.toFloat() * cutoff)
        val dt = 1.0f / config.sampleRate
        val alpha = rc / (rc + dt)
        
        val filtered = FloatArray(audioData.size)
        filtered[0] = audioData[0]
        
        for (i in 1 until audioData.size) {
            filtered[i] = alpha * (filtered[i - 1] + audioData[i] - audioData[i - 1])
        }
        
        return filtered
    }
    
    /**
     * Applies low-pass filter
     */
    private fun applyLowPassFilter(
        audioData: FloatArray,
        config: FilterConfig
    ): FloatArray {
        val cutoff = config.lowPassCutoff / config.sampleRate
        val rc = 1.0f / (2 * PI.toFloat() * cutoff)
        val dt = 1.0f / config.sampleRate
        val alpha = dt / (rc + dt)
        
        val filtered = FloatArray(audioData.size)
        filtered[0] = audioData[0]
        
        for (i in 1 until audioData.size) {
            filtered[i] = filtered[i - 1] + alpha * (audioData[i] - filtered[i - 1])
        }
        
        return filtered
    }
    
    /**
     * Applies band-pass filter (high-pass + low-pass)
     */
    private fun applyBandPassFilter(
        audioData: FloatArray,
        config: FilterConfig
    ): FloatArray {
        val highPassFiltered = applyHighPassFilter(audioData, config)
        return applyLowPassFilter(highPassFiltered, config)
    }
    
    /**
     * Applies noise reduction using spectral subtraction
     */
    private fun applyNoiseReduction(
        audioData: FloatArray,
        config: FilterConfig
    ): FloatArray {
        if (!isNoiseProfileReady) {
            return audioData
        }
        
        val fft = performFFT(audioData)
        val magnitudeSpectrum = calculateMagnitudeSpectrum(fft)
        val phaseSpectrum = calculatePhaseSpectrum(fft)
        
        val filteredSpectrum = FloatArray(magnitudeSpectrum.size)
        
        for (i in magnitudeSpectrum.indices) {
            val signalPower = magnitudeSpectrum[i] * magnitudeSpectrum[i]
            val noisePower = noiseSpectrum[i] * noiseSpectrum[i]
            
            if (signalPower > noisePower) {
                val snr = signalPower / noisePower
                val gain = maxOf(
                    config.spectralFloorFactor,
                    1.0f - config.noiseReductionFactor / snr
                )
                filteredSpectrum[i] = magnitudeSpectrum[i] * gain
            } else {
                filteredSpectrum[i] = magnitudeSpectrum[i] * config.spectralFloorFactor
            }
        }
        
        return reconstructSignal(filteredSpectrum, phaseSpectrum)
    }
    
    /**
     * Applies spectral subtraction
     */
    private fun applySpectralSubtraction(
        audioData: FloatArray,
        config: FilterConfig
    ): FloatArray {
        val fft = performFFT(audioData)
        val magnitudeSpectrum = calculateMagnitudeSpectrum(fft)
        val phaseSpectrum = calculatePhaseSpectrum(fft)
        
        val filteredSpectrum = FloatArray(magnitudeSpectrum.size)
        
        for (i in magnitudeSpectrum.indices) {
            val signalMagnitude = magnitudeSpectrum[i]
            val noiseMagnitude = noiseSpectrum[i]
            
            // Calculate gain factor
            val gain = maxOf(
                config.spectralFloorFactor,
                (signalMagnitude - config.spectralOversubtractionFactor * noiseMagnitude) / signalMagnitude
            )
            
            filteredSpectrum[i] = signalMagnitude * gain
        }
        
        return reconstructSignal(filteredSpectrum, phaseSpectrum)
    }
    
    /**
     * Applies Wiener filter
     */
    private fun applyWienerFilter(
        audioData: FloatArray,
        config: FilterConfig
    ): FloatArray {
        val fft = performFFT(audioData)
        val magnitudeSpectrum = calculateMagnitudeSpectrum(fft)
        val phaseSpectrum = calculatePhaseSpectrum(fft)
        
        val filteredSpectrum = FloatArray(magnitudeSpectrum.size)
        
        for (i in magnitudeSpectrum.indices) {
            val signalPower = magnitudeSpectrum[i] * magnitudeSpectrum[i]
            val noisePower = noiseSpectrum[i] * noiseSpectrum[i]
            
            val gain = signalPower / (signalPower + config.wienerBeta * noisePower)
            filteredSpectrum[i] = magnitudeSpectrum[i] * gain
        }
        
        return reconstructSignal(filteredSpectrum, phaseSpectrum)
    }
    
    /**
     * Applies adaptive filter (LMS algorithm)
     */
    private fun applyAdaptiveFilter(
        audioData: FloatArray,
        config: FilterConfig
    ): FloatArray {
        val filtered = FloatArray(audioData.size)
        
        for (i in 0 until audioData.size) {
            var output = 0f
            
            // Calculate filter output
            for (j in 0 until minOf(i, adaptiveFilterCoefficients.size)) {
                output += adaptiveFilterCoefficients[j] * audioData[i - j]
            }
            
            filtered[i] = output
            
            // Update filter coefficients using LMS algorithm
            val error = audioData[i] - output
            for (j in 0 until minOf(i, adaptiveFilterCoefficients.size)) {
                adaptiveFilterCoefficients[j] += config.adaptiveMu * error * audioData[i - j]
            }
        }
        
        return filtered
    }
    
    /**
     * Performs FFT on audio data
     */
    private fun performFFT(audioData: FloatArray): FloatArray {
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
    
    /**
     * Calculates magnitude spectrum from FFT
     */
    private fun calculateMagnitudeSpectrum(fft: FloatArray): FloatArray {
        val size = fft.size / 2 + 1
        val magnitude = FloatArray(size)
        
        for (i in 0 until size) {
            val real = fft[i * 2]
            val imag = if (i * 2 + 1 < fft.size) fft[i * 2 + 1] else 0f
            magnitude[i] = sqrt(real * real + imag * imag)
        }
        
        return magnitude
    }
    
    /**
     * Calculates phase spectrum from FFT
     */
    private fun calculatePhaseSpectrum(fft: FloatArray): FloatArray {
        val size = fft.size / 2 + 1
        val phase = FloatArray(size)
        
        for (i in 0 until size) {
            val real = fft[i * 2]
            val imag = if (i * 2 + 1 < fft.size) fft[i * 2 + 1] else 0f
            phase[i] = atan2(imag, real)
        }
        
        return phase
    }
    
    /**
     * Reconstructs signal from magnitude and phase spectra
     */
    private fun reconstructSignal(
        magnitudeSpectrum: FloatArray,
        phaseSpectrum: FloatArray
    ): FloatArray {
        val size = magnitudeSpectrum.size
        val fft = FloatArray(size * 2)
        
        for (i in 0 until size) {
            fft[i * 2] = magnitudeSpectrum[i] * cos(phaseSpectrum[i])
            fft[i * 2 + 1] = magnitudeSpectrum[i] * sin(phaseSpectrum[i])
        }
        
        // Perform inverse FFT
        val signal = FloatArray(size)
        for (i in 0 until size) {
            signal[i] = fft[i * 2] / size
        }
        
        return signal
    }
    
    /**
     * Calculates noise level in audio data
     */
    private fun calculateNoiseLevel(audioData: FloatArray): Float {
        var sum = 0f
        for (sample in audioData) {
            sum += sample * sample
        }
        return sqrt(sum / audioData.size)
    }
    
    /**
     * Calculates Signal-to-Noise Ratio
     */
    private fun calculateSNR(original: FloatArray, filtered: FloatArray): Float {
        var signalPower = 0f
        var noisePower = 0f
        
        for (i in original.indices) {
            signalPower += filtered[i] * filtered[i]
            noisePower += (original[i] - filtered[i]) * (original[i] - filtered[i])
        }
        
        return if (noisePower > 0) 10 * log10(signalPower / noisePower) else 0f
    }
    
    /**
     * Gets active filter types for logging
     */
    private fun getActiveFilterTypes(config: FilterConfig): String {
        val types = mutableListOf<String>()
        
        if (config.enableHighPassFilter) types.add("HPF")
        if (config.enableLowPassFilter) types.add("LPF")
        if (config.enableBandPassFilter) types.add("BPF")
        if (config.enableNoiseReduction) types.add("NR")
        if (config.enableSpectralSubtraction) types.add("SS")
        if (config.enableWienerFilter) types.add("WF")
        if (config.enableAdaptiveFilter) types.add("AF")
        
        return types.joinToString(",")
    }
    
    /**
     * Gets filter statistics
     */
    fun getFilterStats(): Map<String, Any> {
        return mapOf(
            "fftSize" to fftSize,
            "overlap" to overlap,
            "hopSize" to hopSize,
            "windowSize" to windowSize,
            "isNoiseProfileReady" to isNoiseProfileReady,
            "noiseEstimationFrames" to noiseEstimationFrames,
            "totalProcessedFrames" to totalProcessedFrames,
            "totalProcessingTime" to totalProcessingTime,
            "averageProcessingTime" to if (totalProcessedFrames > 0) totalProcessingTime / totalProcessedFrames else 0
        )
    }
    
    /**
     * Resets the filter state
     */
    fun reset() {
        noiseProfile.clear()
        noiseSpectrum.fill(0f)
        previousFrame.fill(0f)
        previousNoiseSpectrum.fill(0f)
        adaptiveFilterCoefficients.fill(0f)
        
        noiseEstimationFrames = 0
        isNoiseProfileReady = false
        totalProcessedFrames = 0
        totalProcessingTime = 0L
        
        Log.d(TAG, "AudioFilter reset")
    }
}

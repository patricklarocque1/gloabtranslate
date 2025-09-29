package com.example.gloabtranslate.speech

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.*

/**
 * Audio quality monitoring and analysis implementation.
 * Provides real-time monitoring of audio quality metrics including SNR, distortion,
 * clipping detection, frequency response analysis, and overall audio health.
 */
class AudioQualityMonitor {
    
    companion object {
        private const val TAG = "AudioQualityMonitor"
        
        // Quality thresholds
        private const val EXCELLENT_SNR_THRESHOLD = 30f // dB
        private const val GOOD_SNR_THRESHOLD = 20f // dB
        private const val POOR_SNR_THRESHOLD = 10f // dB
        
        private const val EXCELLENT_THD_THRESHOLD = 0.1f // %
        private const val GOOD_THD_THRESHOLD = 0.5f // %
        private const val POOR_THD_THRESHOLD = 1.0f // %
        
        private const val CLIPPING_THRESHOLD = 0.95f
        private const val SILENCE_THRESHOLD = 0.001f
        
        // Analysis parameters
        private const val DEFAULT_FFT_SIZE = 1024
        private const val DEFAULT_WINDOW_SIZE = 512
        private const val DEFAULT_OVERLAP = 0.5f
        
        // Frequency bands for analysis
        private const val LOW_FREQ_MIN = 80f
        private const val LOW_FREQ_MAX = 250f
        private const val MID_FREQ_MIN = 250f
        private const val MID_FREQ_MAX = 2000f
        private const val HIGH_FREQ_MIN = 2000f
        private const val HIGH_FREQ_MAX = 8000f
    }
    
    private var sampleRate = 16000
    private var fftSize = DEFAULT_FFT_SIZE
    private var windowSize = DEFAULT_WINDOW_SIZE
    private var overlap = DEFAULT_OVERLAP
    
    // Quality metrics history
    private val snrHistory = mutableListOf<Float>()
    private val thdHistory = mutableListOf<Float>()
    private val clippingHistory = mutableListOf<Boolean>()
    private val silenceHistory = mutableListOf<Boolean>()
    
    // Frequency analysis
    private val frequencyResponse = FloatArray(fftSize / 2 + 1)
    private val frequencyHistory = mutableListOf<FloatArray>()
    
    // Quality state
    private var isMonitoring = false
    private var totalFramesAnalyzed = 0
    private var totalClippingFrames = 0
    private var totalSilenceFrames = 0
    
    /**
     * Configuration for audio quality monitoring
     */
    data class MonitoringConfig(
        val sampleRate: Int = 16000,
        val fftSize: Int = DEFAULT_FFT_SIZE,
        val windowSize: Int = DEFAULT_WINDOW_SIZE,
        val overlap: Float = DEFAULT_OVERLAP,
        val enableSNRMonitoring: Boolean = true,
        val enableTHDMonitoring: Boolean = true,
        val enableClippingDetection: Boolean = true,
        val enableSilenceDetection: Boolean = true,
        val enableFrequencyAnalysis: Boolean = true,
        val enableRealTimeMonitoring: Boolean = true,
        val historySize: Int = 100,
        val updateIntervalMs: Long = 100,
        val alertThresholds: QualityThresholds = QualityThresholds()
    )
    
    /**
     * Quality thresholds for alerts
     */
    data class QualityThresholds(
        val minSNR: Float = POOR_SNR_THRESHOLD,
        val maxTHD: Float = POOR_THD_THRESHOLD,
        val maxClippingPercentage: Float = 5f,
        val maxSilencePercentage: Float = 10f,
        val minFrequencyResponse: Float = -20f, // dB
        val maxFrequencyResponse: Float = 20f // dB
    )
    
    /**
     * Audio quality metrics
     */
    data class QualityMetrics(
        val snr: Float = 0f,
        val thd: Float = 0f,
        val clippingPercentage: Float = 0f,
        val silencePercentage: Float = 0f,
        val frequencyResponse: FloatArray = floatArrayOf(),
        val overallQuality: QualityLevel = QualityLevel.UNKNOWN,
        val timestamp: Long = System.currentTimeMillis(),
        val frameCount: Int = 0
    )
    
    /**
     * Quality level enumeration
     */
    enum class QualityLevel {
        EXCELLENT, GOOD, FAIR, POOR, UNKNOWN
    }
    
    /**
     * Quality alert
     */
    data class QualityAlert(
        val type: AlertType,
        val message: String,
        val severity: AlertSeverity,
        val timestamp: Long = System.currentTimeMillis(),
        val metrics: QualityMetrics
    )
    
    /**
     * Alert types
     */
    enum class AlertType {
        LOW_SNR, HIGH_THD, EXCESSIVE_CLIPPING, EXCESSIVE_SILENCE, 
        FREQUENCY_RESPONSE_ISSUE, OVERALL_QUALITY_DEGRADED
    }
    
    /**
     * Alert severity levels
     */
    enum class AlertSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    /**
     * Result of quality analysis
     */
    data class QualityAnalysisResult(
        val success: Boolean,
        val metrics: QualityMetrics? = null,
        val alerts: List<QualityAlert> = emptyList(),
        val processingTime: Long = 0,
        val error: String? = null
    )
    
    /**
     * Quality monitoring listener interface
     */
    interface QualityListener {
        fun onQualityUpdate(metrics: QualityMetrics)
        fun onQualityAlert(alert: QualityAlert)
        fun onQualityThresholdExceeded(threshold: String, value: Float)
    }
    
    private var qualityListener: QualityListener? = null
    
    /**
     * Sets the quality monitoring listener
     */
    fun setQualityListener(listener: QualityListener?) {
        qualityListener = listener
    }
    
    /**
     * Initializes the audio quality monitor
     */
    fun initialize(config: MonitoringConfig): Boolean {
        try {
            sampleRate = config.sampleRate
            fftSize = config.fftSize
            windowSize = config.windowSize
            overlap = config.overlap
            
            // Initialize arrays
            frequencyResponse.fill(0f)
            
            // Clear history
            snrHistory.clear()
            thdHistory.clear()
            clippingHistory.clear()
            silenceHistory.clear()
            frequencyHistory.clear()
            
            // Reset state
            isMonitoring = false
            totalFramesAnalyzed = 0
            totalClippingFrames = 0
            totalSilenceFrames = 0
            
            Log.d(TAG, "AudioQualityMonitor initialized")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioQualityMonitor", e)
            return false
        }
    }
    
    /**
     * Analyzes audio quality for a single frame
     */
    fun analyzeAudioQuality(
        audioData: FloatArray,
        config: MonitoringConfig
    ): QualityAnalysisResult {
        val startTime = System.nanoTime()
        
        try {
            val metrics = QualityMetrics(
                snr = calculateSNR(audioData),
                thd = calculateTHD(audioData),
                clippingPercentage = calculateClippingPercentage(audioData),
                silencePercentage = calculateSilencePercentage(audioData),
                frequencyResponse = analyzeFrequencyResponse(audioData, config),
                overallQuality = QualityLevel.UNKNOWN,
                timestamp = System.currentTimeMillis(),
                frameCount = totalFramesAnalyzed
            )
            
            // Determine overall quality
            val overallQuality = determineOverallQuality(metrics, config.alertThresholds)
            val updatedMetrics = metrics.copy(overallQuality = overallQuality)
            
            // Check for alerts
            val alerts = checkQualityAlerts(updatedMetrics, config.alertThresholds)
            
            // Update history
            updateQualityHistory(updatedMetrics, config)
            
            // Notify listener
            qualityListener?.onQualityUpdate(updatedMetrics)
            alerts.forEach { alert ->
                qualityListener?.onQualityAlert(alert)
            }
            
            val processingTime = (System.nanoTime() - startTime) / 1_000_000
            
            return QualityAnalysisResult(
                success = true,
                metrics = updatedMetrics,
                alerts = alerts,
                processingTime = processingTime
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing audio quality", e)
            return QualityAnalysisResult(
                success = false,
                error = "Analysis failed: ${e.message}"
            )
        }
    }
    
    /**
     * Creates a Flow for real-time quality monitoring
     */
    fun createQualityMonitoringFlow(
        audioFlow: Flow<FloatArray>,
        config: MonitoringConfig
    ): Flow<QualityMetrics> = flow {
        audioFlow.collect { audioData ->
            val result = analyzeAudioQuality(audioData, config)
            
            if (result.success && result.metrics != null) {
                emit(result.metrics)
            }
        }
    }.flowOn(Dispatchers.Default)
    
    /**
     * Calculates Signal-to-Noise Ratio
     */
    private fun calculateSNR(audioData: FloatArray): Float {
        // Calculate signal power
        var signalPower = 0f
        for (sample in audioData) {
            signalPower += sample * sample
        }
        signalPower /= audioData.size
        
        // Estimate noise power (assuming noise is in lower amplitude samples)
        val sortedSamples = audioData.map { abs(it) }.sorted()
        val noiseSamples = sortedSamples.take((sortedSamples.size * 0.1).toInt())
        var noisePower = 0f
        for (sample in noiseSamples) {
            noisePower += sample * sample
        }
        noisePower /= noiseSamples.size
        
        return if (noisePower > 0) 10 * log10(signalPower / noisePower) else 0f
    }
    
    /**
     * Calculates Total Harmonic Distortion
     */
    private fun calculateTHD(audioData: FloatArray): Float {
        // Simplified THD calculation using FFT
        val fft = performFFT(audioData)
        val magnitudeSpectrum = calculateMagnitudeSpectrum(fft)
        
        // Find fundamental frequency (highest peak)
        var fundamentalIndex = 0
        var fundamentalMagnitude = 0f
        
        for (i in 1 until magnitudeSpectrum.size / 2) {
            if (magnitudeSpectrum[i] > fundamentalMagnitude) {
                fundamentalMagnitude = magnitudeSpectrum[i]
                fundamentalIndex = i
            }
        }
        
        // Calculate harmonic distortion
        var harmonicPower = 0f
        var totalPower = 0f
        
        for (i in magnitudeSpectrum.indices) {
            totalPower += magnitudeSpectrum[i] * magnitudeSpectrum[i]
            
            // Check for harmonics (2x, 3x, 4x, 5x fundamental frequency)
            if (i == fundamentalIndex * 2 || i == fundamentalIndex * 3 || 
                i == fundamentalIndex * 4 || i == fundamentalIndex * 5) {
                harmonicPower += magnitudeSpectrum[i] * magnitudeSpectrum[i]
            }
        }
        
        return if (totalPower > 0) sqrt(harmonicPower / totalPower) * 100 else 0f
    }
    
    /**
     * Calculates clipping percentage
     */
    private fun calculateClippingPercentage(audioData: FloatArray): Float {
        var clippedSamples = 0
        for (sample in audioData) {
            if (abs(sample) >= CLIPPING_THRESHOLD) {
                clippedSamples++
            }
        }
        return (clippedSamples.toFloat() / audioData.size) * 100
    }
    
    /**
     * Calculates silence percentage
     */
    private fun calculateSilencePercentage(audioData: FloatArray): Float {
        var silentSamples = 0
        for (sample in audioData) {
            if (abs(sample) <= SILENCE_THRESHOLD) {
                silentSamples++
            }
        }
        return (silentSamples.toFloat() / audioData.size) * 100
    }
    
    /**
     * Analyzes frequency response
     */
    private fun analyzeFrequencyResponse(
        audioData: FloatArray,
        config: MonitoringConfig
    ): FloatArray {
        val fft = performFFT(audioData)
        val magnitudeSpectrum = calculateMagnitudeSpectrum(fft)
        
        // Convert to dB
        val frequencyResponse = FloatArray(magnitudeSpectrum.size)
        for (i in frequencyResponse.indices) {
            frequencyResponse[i] = if (magnitudeSpectrum[i] > 0) {
                20 * log10(magnitudeSpectrum[i])
            } else {
                -100f // Very low value for zero magnitude
            }
        }
        
        return frequencyResponse
    }
    
    /**
     * Determines overall quality level
     */
    private fun determineOverallQuality(
        metrics: QualityMetrics,
        thresholds: QualityThresholds
    ): QualityLevel {
        var score = 0
        
        // SNR scoring
        when {
            metrics.snr >= EXCELLENT_SNR_THRESHOLD -> score += 4
            metrics.snr >= GOOD_SNR_THRESHOLD -> score += 3
            metrics.snr >= POOR_SNR_THRESHOLD -> score += 2
            else -> score += 1
        }
        
        // THD scoring
        when {
            metrics.thd <= EXCELLENT_THD_THRESHOLD -> score += 4
            metrics.thd <= GOOD_THD_THRESHOLD -> score += 3
            metrics.thd <= POOR_THD_THRESHOLD -> score += 2
            else -> score += 1
        }
        
        // Clipping scoring
        when {
            metrics.clippingPercentage <= 1f -> score += 4
            metrics.clippingPercentage <= 3f -> score += 3
            metrics.clippingPercentage <= 5f -> score += 2
            else -> score += 1
        }
        
        // Silence scoring
        when {
            metrics.silencePercentage <= 5f -> score += 4
            metrics.silencePercentage <= 10f -> score += 3
            metrics.silencePercentage <= 20f -> score += 2
            else -> score += 1
        }
        
        return when (score) {
            in 14..16 -> QualityLevel.EXCELLENT
            in 11..13 -> QualityLevel.GOOD
            in 8..10 -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }
    }
    
    /**
     * Checks for quality alerts
     */
    private fun checkQualityAlerts(
        metrics: QualityMetrics,
        thresholds: QualityThresholds
    ): List<QualityAlert> {
        val alerts = mutableListOf<QualityAlert>()
        
        // Check SNR
        if (metrics.snr < thresholds.minSNR) {
            alerts.add(QualityAlert(
                type = AlertType.LOW_SNR,
                message = "Low SNR detected: ${metrics.snr}dB",
                severity = if (metrics.snr < POOR_SNR_THRESHOLD) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                metrics = metrics
            ))
        }
        
        // Check THD
        if (metrics.thd > thresholds.maxTHD) {
            alerts.add(QualityAlert(
                type = AlertType.HIGH_THD,
                message = "High THD detected: ${metrics.thd}%",
                severity = if (metrics.thd > POOR_THD_THRESHOLD) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                metrics = metrics
            ))
        }
        
        // Check clipping
        if (metrics.clippingPercentage > thresholds.maxClippingPercentage) {
            alerts.add(QualityAlert(
                type = AlertType.EXCESSIVE_CLIPPING,
                message = "Excessive clipping detected: ${metrics.clippingPercentage}%",
                severity = if (metrics.clippingPercentage > 10f) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                metrics = metrics
            ))
        }
        
        // Check silence
        if (metrics.silencePercentage > thresholds.maxSilencePercentage) {
            alerts.add(QualityAlert(
                type = AlertType.EXCESSIVE_SILENCE,
                message = "Excessive silence detected: ${metrics.silencePercentage}%",
                severity = if (metrics.silencePercentage > 20f) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                metrics = metrics
            ))
        }
        
        // Check overall quality
        if (metrics.overallQuality == QualityLevel.POOR) {
            alerts.add(QualityAlert(
                type = AlertType.OVERALL_QUALITY_DEGRADED,
                message = "Overall audio quality is poor",
                severity = AlertSeverity.CRITICAL,
                metrics = metrics
            ))
        }
        
        return alerts
    }
    
    /**
     * Updates quality history
     */
    private fun updateQualityHistory(
        metrics: QualityMetrics,
        config: MonitoringConfig
    ) {
        totalFramesAnalyzed++
        
        // Update SNR history
        snrHistory.add(metrics.snr)
        if (snrHistory.size > config.historySize) {
            snrHistory.removeAt(0)
        }
        
        // Update THD history
        thdHistory.add(metrics.thd)
        if (thdHistory.size > config.historySize) {
            thdHistory.removeAt(0)
        }
        
        // Update clipping history
        clippingHistory.add(metrics.clippingPercentage > 5f)
        if (clippingHistory.size > config.historySize) {
            clippingHistory.removeAt(0)
        }
        
        // Update silence history
        silenceHistory.add(metrics.silencePercentage > 10f)
        if (silenceHistory.size > config.historySize) {
            silenceHistory.removeAt(0)
        }
        
        // Update frequency response history
        frequencyHistory.add(metrics.frequencyResponse)
        if (frequencyHistory.size > config.historySize) {
            frequencyHistory.removeAt(0)
        }
        
        // Update counters
        if (metrics.clippingPercentage > 5f) totalClippingFrames++
        if (metrics.silencePercentage > 10f) totalSilenceFrames++
    }
    
    /**
     * Performs FFT on audio data
     */
    private fun performFFT(audioData: FloatArray): FloatArray {
        val n = audioData.size
        val fft = FloatArray(n * 2)
        
        // Copy real data
        for (i in 0 until n) {
            fft[i * 2] = audioData[i]
            fft[i * 2 + 1] = 0f
        }
        
        // Simple FFT implementation
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
     * Gets current quality statistics
     */
    fun getQualityStats(): Map<String, Any> {
        return mapOf(
            "totalFramesAnalyzed" to totalFramesAnalyzed,
            "totalClippingFrames" to totalClippingFrames,
            "totalSilenceFrames" to totalSilenceFrames,
            "clippingPercentage" to if (totalFramesAnalyzed > 0) (totalClippingFrames.toFloat() / totalFramesAnalyzed) * 100 else 0f,
            "silencePercentage" to if (totalFramesAnalyzed > 0) (totalSilenceFrames.toFloat() / totalFramesAnalyzed) * 100 else 0f,
            "averageSNR" to if (snrHistory.isNotEmpty()) snrHistory.average() else 0.0,
            "averageTHD" to if (thdHistory.isNotEmpty()) thdHistory.average() else 0.0,
            "isMonitoring" to isMonitoring,
            "historySize" to snrHistory.size
        )
    }
    
    /**
     * Gets quality trends
     */
    fun getQualityTrends(): Map<String, List<Float>> {
        return mapOf(
            "snrTrend" to snrHistory.toList(),
            "thdTrend" to thdHistory.toList(),
            "clippingTrend" to clippingHistory.map { if (it) 1f else 0f },
            "silenceTrend" to silenceHistory.map { if (it) 1f else 0f }
        )
    }
    
    /**
     * Resets the quality monitor
     */
    fun reset() {
        snrHistory.clear()
        thdHistory.clear()
        clippingHistory.clear()
        silenceHistory.clear()
        frequencyHistory.clear()
        
        frequencyResponse.fill(0f)
        
        isMonitoring = false
        totalFramesAnalyzed = 0
        totalClippingFrames = 0
        totalSilenceFrames = 0
        
        Log.d(TAG, "AudioQualityMonitor reset")
    }
}

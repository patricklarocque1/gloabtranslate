package com.example.gloabtranslate.speech

import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Parcelable
import android.os.Parcel
import android.util.Log

/**
 * Configuration class for audio recording and processing parameters.
 * Provides comprehensive audio format settings and validation.
 */
data class AudioConfig(
    // Basic audio parameters
    val sampleRate: Int = 16000, // 16kHz for speech recognition
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    
    // Buffer configuration
    val bufferSize: Int = 0, // 0 means auto-calculate
    val bufferSizeMultiplier: Int = 2,
    
    // Audio quality settings
    val enableNoiseSuppression: Boolean = true,
    val enableAutomaticGainControl: Boolean = true,
    val enableEchoCancellation: Boolean = true,
    
    // Processing parameters
    val enablePreemphasis: Boolean = true,
    val preemphasisCoeff: Float = 0.97f,
    val enableNormalization: Boolean = true,
    val normalizationFactor: Float = 0.95f,
    
    // Voice Activity Detection (VAD)
    val enableVAD: Boolean = true,
    val vadThreshold: Float = 0.01f,
    val vadSilenceTimeout: Int = 1000, // ms
    val vadMinSpeechDuration: Int = 200, // ms
    
    // Noise reduction
    val enableNoiseReduction: Boolean = true,
    val noiseReductionFactor: Float = 0.8f,
    val noiseProfileDuration: Int = 2000, // ms
    
    // Audio session management
    val audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE,
    val audioMode: Int = AudioManager.MODE_IN_COMMUNICATION,
    val audioStreamType: Int = AudioManager.STREAM_VOICE_CALL,
    
    // File recording settings
    val enableFileRecording: Boolean = false,
    val outputFormat: AudioOutputFormat = AudioOutputFormat.WAV,
    val compressionQuality: Int = 128, // kbps
    
    // Real-time processing
    val enableRealTimeProcessing: Boolean = true,
    val processingChunkSize: Int = 320, // 20ms at 16kHz
    val enableFeatureExtraction: Boolean = true,
    
    // Performance settings
    val enableLowLatency: Boolean = true,
    val enableHighQuality: Boolean = false,
    val cpuUsageLimit: Float = 0.7f // 70% max CPU usage
) : Parcelable {
    
    companion object {
        private const val TAG = "AudioConfig"
        
        // Predefined configurations for common use cases
        val SPEECH_RECOGNITION = AudioConfig(
            sampleRate = 16000,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            audioSource = MediaRecorder.AudioSource.MIC,
            enableNoiseSuppression = true,
            enableAutomaticGainControl = true,
            enableEchoCancellation = true,
            enableVAD = true,
            enableNoiseReduction = true,
            enableRealTimeProcessing = true,
            enableLowLatency = true
        )
        
        val HIGH_QUALITY_RECORDING = AudioConfig(
            sampleRate = 44100,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            audioSource = MediaRecorder.AudioSource.MIC,
            enableNoiseSuppression = true,
            enableAutomaticGainControl = true,
            enableEchoCancellation = true,
            enableVAD = false,
            enableNoiseReduction = true,
            enableRealTimeProcessing = false,
            enableHighQuality = true,
            enableFileRecording = true,
            outputFormat = AudioOutputFormat.WAV
        )
        
        val VOICE_CALL = AudioConfig(
            sampleRate = 8000,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            enableNoiseSuppression = true,
            enableAutomaticGainControl = true,
            enableEchoCancellation = true,
            enableVAD = true,
            enableNoiseReduction = true,
            enableRealTimeProcessing = true,
            enableLowLatency = true,
            audioMode = AudioManager.MODE_IN_COMMUNICATION,
            audioStreamType = AudioManager.STREAM_VOICE_CALL
        )
        
        val MUSIC_RECORDING = AudioConfig(
            sampleRate = 48000,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            audioSource = MediaRecorder.AudioSource.MIC,
            enableNoiseSuppression = false,
            enableAutomaticGainControl = false,
            enableEchoCancellation = false,
            enableVAD = false,
            enableNoiseReduction = false,
            enableRealTimeProcessing = false,
            enableHighQuality = true,
            enableFileRecording = true,
            outputFormat = AudioOutputFormat.WAV,
            compressionQuality = 320
        )
        
        // Supported sample rates
        val SUPPORTED_SAMPLE_RATES = listOf(8000, 11025, 16000, 22050, 44100, 48000)
        
        // Supported audio formats
        val SUPPORTED_AUDIO_FORMATS = listOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        
        // Supported channel configurations
        val SUPPORTED_CHANNEL_CONFIGS = listOf(
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.CHANNEL_IN_STEREO
        )
        
        // Supported audio sources
        val SUPPORTED_AUDIO_SOURCES = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_UPLINK,
            MediaRecorder.AudioSource.VOICE_DOWNLINK
        )
        
        @JvmField
        val CREATOR: Parcelable.Creator<AudioConfig> = object : Parcelable.Creator<AudioConfig> {
            override fun createFromParcel(parcel: Parcel): AudioConfig = AudioConfig(parcel)
            override fun newArray(size: Int): Array<AudioConfig?> = arrayOfNulls(size)
        }
    }
    
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readFloat(),
        parcel.readByte() != 0.toByte(),
        parcel.readFloat(),
        parcel.readByte() != 0.toByte(),
        parcel.readFloat(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readFloat(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        AudioOutputFormat.valueOf(parcel.readString() ?: "WAV"),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readFloat()
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(sampleRate)
        parcel.writeInt(channelConfig)
        parcel.writeInt(audioFormat)
        parcel.writeInt(audioSource)
        parcel.writeInt(bufferSize)
        parcel.writeInt(bufferSizeMultiplier)
        parcel.writeByte(if (enableNoiseSuppression) 1 else 0)
        parcel.writeByte(if (enableAutomaticGainControl) 1 else 0)
        parcel.writeByte(if (enableEchoCancellation) 1 else 0)
        parcel.writeByte(if (enablePreemphasis) 1 else 0)
        parcel.writeFloat(preemphasisCoeff)
        parcel.writeByte(if (enableNormalization) 1 else 0)
        parcel.writeFloat(normalizationFactor)
        parcel.writeByte(if (enableVAD) 1 else 0)
        parcel.writeFloat(vadThreshold)
        parcel.writeInt(vadSilenceTimeout)
        parcel.writeInt(vadMinSpeechDuration)
        parcel.writeByte(if (enableNoiseReduction) 1 else 0)
        parcel.writeFloat(noiseReductionFactor)
        parcel.writeInt(noiseProfileDuration)
        parcel.writeInt(audioSessionId)
        parcel.writeInt(audioMode)
        parcel.writeInt(audioStreamType)
        parcel.writeByte(if (enableFileRecording) 1 else 0)
        parcel.writeString(outputFormat.name)
        parcel.writeInt(compressionQuality)
        parcel.writeByte(if (enableRealTimeProcessing) 1 else 0)
        parcel.writeInt(processingChunkSize)
        parcel.writeByte(if (enableFeatureExtraction) 1 else 0)
        parcel.writeByte(if (enableLowLatency) 1 else 0)
        parcel.writeByte(if (enableHighQuality) 1 else 0)
        parcel.writeFloat(cpuUsageLimit)
    }
    
    override fun describeContents(): Int = 0
    
    
    /**
     * Validates the audio configuration
     */
    fun validate(): AudioConfigValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate sample rate
        if (sampleRate !in SUPPORTED_SAMPLE_RATES) {
            errors.add("Unsupported sample rate: $sampleRate. Supported: $SUPPORTED_SAMPLE_RATES")
        }
        
        // Validate audio format
        if (audioFormat !in SUPPORTED_AUDIO_FORMATS) {
            errors.add("Unsupported audio format: $audioFormat")
        }
        
        // Validate channel configuration
        if (channelConfig !in SUPPORTED_CHANNEL_CONFIGS) {
            errors.add("Unsupported channel configuration: $channelConfig")
        }
        
        // Validate audio source
        if (audioSource !in SUPPORTED_AUDIO_SOURCES) {
            warnings.add("Audio source $audioSource may not be supported on all devices")
        }
        
        // Validate buffer size
        if (bufferSize < 0) {
            errors.add("Buffer size cannot be negative: $bufferSize")
        }
        
        // Validate processing parameters
        if (preemphasisCoeff < 0f || preemphasisCoeff > 1f) {
            errors.add("Preemphasis coefficient must be between 0 and 1: $preemphasisCoeff")
        }
        
        if (normalizationFactor <= 0f || normalizationFactor > 1f) {
            errors.add("Normalization factor must be between 0 and 1: $normalizationFactor")
        }
        
        if (vadThreshold < 0f || vadThreshold > 1f) {
            errors.add("VAD threshold must be between 0 and 1: $vadThreshold")
        }
        
        if (noiseReductionFactor < 0f || noiseReductionFactor > 1f) {
            errors.add("Noise reduction factor must be between 0 and 1: $noiseReductionFactor")
        }
        
        if (cpuUsageLimit <= 0f || cpuUsageLimit > 1f) {
            errors.add("CPU usage limit must be between 0 and 1: $cpuUsageLimit")
        }
        
        // Performance warnings
        if (enableHighQuality && enableLowLatency) {
            warnings.add("High quality and low latency may conflict")
        }
        
        if (sampleRate > 16000 && enableRealTimeProcessing) {
            warnings.add("High sample rate with real-time processing may cause performance issues")
        }
        
        if (enableFeatureExtraction && !enableRealTimeProcessing) {
            warnings.add("Feature extraction without real-time processing may not be useful")
        }
        
        return AudioConfigValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    /**
     * Calculates the optimal buffer size based on audio parameters
     */
    fun calculateOptimalBufferSize(): Int {
        if (bufferSize > 0) return bufferSize
        
        val bytesPerSample = when (audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        
        val channels = when (channelConfig) {
            AudioFormat.CHANNEL_IN_MONO -> 1
            AudioFormat.CHANNEL_IN_STEREO -> 2
            else -> 1
        }
        
        // Calculate buffer size for 100ms of audio
        val bufferSizeMs = 100
        val frameSize = bytesPerSample * channels
        val framesPerSecond = sampleRate
        val framesPerBuffer = (framesPerSecond * bufferSizeMs / 1000)
        
        return framesPerBuffer * frameSize * bufferSizeMultiplier
    }
    
    /**
     * Gets the bit depth in bits
     */
    fun getBitDepth(): Int {
        return when (audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_32BIT -> 32
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
    }
    
    /**
     * Gets the number of channels
     */
    fun getChannelCount(): Int {
        return when (channelConfig) {
            AudioFormat.CHANNEL_IN_MONO -> 1
            AudioFormat.CHANNEL_IN_STEREO -> 2
            else -> 1
        }
    }
    
    /**
     * Gets the audio source name for logging
     */
    fun getAudioSourceName(): String {
        return when (audioSource) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            else -> "UNKNOWN($audioSource)"
        }
    }
    
    /**
     * Creates a copy with updated parameters
     */
    fun copyWith(
        sampleRate: Int = this.sampleRate,
        channelConfig: Int = this.channelConfig,
        audioFormat: Int = this.audioFormat,
        audioSource: Int = this.audioSource,
        bufferSize: Int = this.bufferSize,
        bufferSizeMultiplier: Int = this.bufferSizeMultiplier,
        enableNoiseSuppression: Boolean = this.enableNoiseSuppression,
        enableAutomaticGainControl: Boolean = this.enableAutomaticGainControl,
        enableEchoCancellation: Boolean = this.enableEchoCancellation,
        enablePreemphasis: Boolean = this.enablePreemphasis,
        preemphasisCoeff: Float = this.preemphasisCoeff,
        enableNormalization: Boolean = this.enableNormalization,
        normalizationFactor: Float = this.normalizationFactor,
        enableVAD: Boolean = this.enableVAD,
        vadThreshold: Float = this.vadThreshold,
        vadSilenceTimeout: Int = this.vadSilenceTimeout,
        vadMinSpeechDuration: Int = this.vadMinSpeechDuration,
        enableNoiseReduction: Boolean = this.enableNoiseReduction,
        noiseReductionFactor: Float = this.noiseReductionFactor,
        noiseProfileDuration: Int = this.noiseProfileDuration,
        audioSessionId: Int = this.audioSessionId,
        audioMode: Int = this.audioMode,
        audioStreamType: Int = this.audioStreamType,
        enableFileRecording: Boolean = this.enableFileRecording,
        outputFormat: AudioOutputFormat = this.outputFormat,
        compressionQuality: Int = this.compressionQuality,
        enableRealTimeProcessing: Boolean = this.enableRealTimeProcessing,
        processingChunkSize: Int = this.processingChunkSize,
        enableFeatureExtraction: Boolean = this.enableFeatureExtraction,
        enableLowLatency: Boolean = this.enableLowLatency,
        enableHighQuality: Boolean = this.enableHighQuality,
        cpuUsageLimit: Float = this.cpuUsageLimit
    ): AudioConfig {
        return AudioConfig(
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioFormat = audioFormat,
            audioSource = audioSource,
            bufferSize = bufferSize,
            bufferSizeMultiplier = bufferSizeMultiplier,
            enableNoiseSuppression = enableNoiseSuppression,
            enableAutomaticGainControl = enableAutomaticGainControl,
            enableEchoCancellation = enableEchoCancellation,
            enablePreemphasis = enablePreemphasis,
            preemphasisCoeff = preemphasisCoeff,
            enableNormalization = enableNormalization,
            normalizationFactor = normalizationFactor,
            enableVAD = enableVAD,
            vadThreshold = vadThreshold,
            vadSilenceTimeout = vadSilenceTimeout,
            vadMinSpeechDuration = vadMinSpeechDuration,
            enableNoiseReduction = enableNoiseReduction,
            noiseReductionFactor = noiseReductionFactor,
            noiseProfileDuration = noiseProfileDuration,
            audioSessionId = audioSessionId,
            audioMode = audioMode,
            audioStreamType = audioStreamType,
            enableFileRecording = enableFileRecording,
            outputFormat = outputFormat,
            compressionQuality = compressionQuality,
            enableRealTimeProcessing = enableRealTimeProcessing,
            processingChunkSize = processingChunkSize,
            enableFeatureExtraction = enableFeatureExtraction,
            enableLowLatency = enableLowLatency,
            enableHighQuality = enableHighQuality,
            cpuUsageLimit = cpuUsageLimit
        )
    }
    
    override fun toString(): String {
        return "AudioConfig(" +
                "sampleRate=$sampleRate, " +
                "channels=${getChannelCount()}, " +
                "format=${getBitDepth()}bit, " +
                "source=${getAudioSourceName()}, " +
                "bufferSize=${calculateOptimalBufferSize()}, " +
                "vad=$enableVAD, " +
                "noiseReduction=$enableNoiseReduction, " +
                "realTime=$enableRealTimeProcessing" +
                ")"
    }
}

/**
 * Audio output format enumeration
 */
enum class AudioOutputFormat {
    WAV, MP3, AAC, OGG, FLAC, M4A
}

/**
 * Result of audio configuration validation
 */
data class AudioConfigValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
) {
    fun hasErrors(): Boolean = errors.isNotEmpty()
    fun hasWarnings(): Boolean = warnings.isNotEmpty()
    
    fun getErrorMessage(): String? {
        return if (hasErrors()) errors.joinToString("; ") else null
    }
    
    fun getWarningMessage(): String? {
        return if (hasWarnings()) warnings.joinToString("; ") else null
    }
    
    fun logResults(tag: String = "AudioConfig") {
        if (hasErrors()) {
            Log.e(tag, "Audio configuration validation errors: ${getErrorMessage()}")
        }
        if (hasWarnings()) {
            Log.w(tag, "Audio configuration validation warnings: ${getWarningMessage()}")
        }
        if (isValid && !hasWarnings()) {
            Log.d(tag, "Audio configuration validation passed")
        }
    }
}

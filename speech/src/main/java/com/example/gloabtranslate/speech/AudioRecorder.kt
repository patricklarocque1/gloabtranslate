package com.example.gloabtranslate.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.os.Parcel
import android.util.Log
import com.example.gloabtranslate.core.logging.DebugLogger
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import com.example.gloabtranslate.core.data.config.AudioConfig as PreferencesAudioConfig
import com.example.gloabtranslate.speech.config.AudioConfigurationObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio recorder implementation for capturing audio data from microphone.
 * Provides both real-time audio streaming and file-based recording capabilities.
 */
class AudioRecorder(
    private val context: Context,
    private val configurationManager: ConfigurationManager,
    private val debugLogger: DebugLogger
) {
    
    companion object {
        private const val TAG = "AudioRecorder"
        
        // Audio configuration constants
        private const val SAMPLE_RATE = 16000 // 16kHz sample rate
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
        
        // Calculate buffer size based on audio configuration
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR
    }
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val configurationObserver = AudioConfigurationObserver(context)
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activePreferencesConfig: PreferencesAudioConfig? = null
    private var activeRecordingConfig: RecordingConfig = RecordingConfig()

    init {
        configurationObserver.setOnConfigChanged { config: PreferencesAudioConfig ->
            applyNonCriticalConfiguration(config)
        }
        configurationObserver.setOnCriticalConfigChanged { config: PreferencesAudioConfig ->
            handleCriticalConfigurationChange(config)
        }
        configurationObserver.start()
    }
    
    /**
     * Configuration for audio recording
     */
    data class RecordingConfig(
        val sampleRate: Int = SAMPLE_RATE,
        val channelConfig: Int = CHANNEL_CONFIG,
        val audioFormat: Int = AUDIO_FORMAT,
        val bufferSize: Int = BUFFER_SIZE,
        val audioSource: Int = MediaRecorder.AudioSource.MIC
    ) : Parcelable {
        constructor(parcel: Parcel) : this(
            parcel.readInt(),
            parcel.readInt(),
            parcel.readInt(),
            parcel.readInt(),
            parcel.readInt()
        )
        
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(sampleRate)
            parcel.writeInt(channelConfig)
            parcel.writeInt(audioFormat)
            parcel.writeInt(bufferSize)
            parcel.writeInt(audioSource)
        }
        
        override fun describeContents(): Int = 0
        
        companion object CREATOR : Parcelable.Creator<RecordingConfig> {
            override fun createFromParcel(parcel: Parcel): RecordingConfig = RecordingConfig(parcel)
            override fun newArray(size: Int): Array<RecordingConfig?> = arrayOfNulls(size)
        }
    }
    
    /**
     * Result of audio recording operations
     */
    data class RecordingResult(
        val success: Boolean,
        val message: String? = null,
        val audioData: ByteArray? = null,
        val duration: Long = 0,
        val error: String? = null
    )
    
    /**
     * Audio frame data for real-time processing
     */
    data class AudioFrame(
        val data: ByteArray,
        val timestamp: Long = System.currentTimeMillis(),
        val size: Int = data.size
    )
    
    private fun applyNonCriticalConfiguration(config: PreferencesAudioConfig) {
        activePreferencesConfig = config
        if (!isRecording) {
            val buffer = if (config.audioBufferSize > 0) config.audioBufferSize else activeRecordingConfig.bufferSize
            activeRecordingConfig = activeRecordingConfig.copy(
                sampleRate = config.sampleRate,
                bufferSize = buffer
            )
        }
    }

    private fun handleCriticalConfigurationChange(config: PreferencesAudioConfig) {
        activePreferencesConfig = config
        recorderScope.launch {
            val wasRecording = isRecording
            if (wasRecording) {
                stopRecording()
            }

            activeRecordingConfig = activeRecordingConfig.copy(
                sampleRate = config.sampleRate,
                bufferSize = if (config.audioBufferSize > 0) config.audioBufferSize else activeRecordingConfig.bufferSize
            )

            audioRecord = null
            if (wasRecording) {
                Log.d(TAG, "Recording interrupted to apply new configuration; upstream should restart stream")
            }
        }
    }

    /**
     * Checks if RECORD_AUDIO permission is granted
     */
    fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Initializes the audio recorder with default configuration
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun initialize(config: RecordingConfig = RecordingConfig()): RecordingResult = withContext(Dispatchers.IO) {
        try {
            // Check for RECORD_AUDIO permission before initializing AudioRecord
            if (!hasRecordAudioPermission()) {
                return@withContext RecordingResult(
                    success = false,
                    error = "RECORD_AUDIO permission not granted"
                )
            }

            val preferences = configurationManager.currentAudioConfig()
            activePreferencesConfig = preferences
            configurationObserver.updateCurrentConfig(preferences)

            if (!preferences.enableAudioRecording) {
                return@withContext RecordingResult(
                    success = false,
                    error = "Audio recording disabled in settings"
                )
            }

            if (!hasRecordAudioPermission()) {
                return@withContext RecordingResult(
                    success = false,
                    error = "RECORD_AUDIO permission not granted"
                )
            }

            val resolvedBufferSize = if (preferences.audioBufferSize > 0) {
                preferences.audioBufferSize
            } else {
                config.bufferSize
            }

            val resolvedConfig = config.copy(
                sampleRate = preferences.sampleRate,
                bufferSize = resolvedBufferSize
            )

            // Validate audio configuration
            val minBufferSize = AudioRecord.getMinBufferSize(
                resolvedConfig.sampleRate,
                resolvedConfig.channelConfig,
                resolvedConfig.audioFormat
            )

            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
                return@withContext RecordingResult(
                    success = false,
                    error = "Invalid audio configuration"
                )
            }

            val bufferSize = maxOf(minBufferSize, resolvedConfig.bufferSize)

            @Suppress("MissingPermission")
            audioRecord = AudioRecord(
                resolvedConfig.audioSource,
                resolvedConfig.sampleRate,
                resolvedConfig.channelConfig,
                resolvedConfig.audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext RecordingResult(
                    success = false,
                    error = "Failed to initialize AudioRecord"
                )
            }

            activeRecordingConfig = resolvedConfig.copy(bufferSize = bufferSize)

            debugLogger.d(TAG, "AudioRecorder initialized successfully")
            RecordingResult(
                success = true,
                message = "AudioRecorder initialized with sample rate: ${resolvedConfig.sampleRate}Hz"
            )
            
        } catch (e: Exception) {
            debugLogger.e(TAG, "Failed to initialize AudioRecorder: ${e.message}", e)
            RecordingResult(
                success = false,
                error = "Initialization failed: ${e.message}"
            )
        }
    }
    
    /**
     * Starts real-time audio recording and returns a Flow of audio frames
     */
    fun startRecording(config: RecordingConfig = getRecordingConfig()): Flow<AudioFrame> = flow {
        try {
            if (!hasRecordAudioPermission()) {
                throw SecurityException("RECORD_AUDIO permission not granted")
            }

            if (activePreferencesConfig?.enableAudioRecording == false) {
                throw IllegalStateException("Audio recording disabled in settings")
            }

            val audioRec = audioRecord ?: throw IllegalStateException("AudioRecorder not initialized")
            
            if (isRecording) {
                throw IllegalStateException("Recording already in progress")
            }
            
            audioRec.startRecording()
            isRecording = true
            
            debugLogger.d(TAG, "Started audio recording")
            
            val buffer = ByteArray(config.bufferSize)
            
            while (isRecording && audioRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRec.read(buffer, 0, buffer.size)
                
                if (bytesRead > 0) {
                    val frameData = ByteArray(bytesRead)
                    System.arraycopy(buffer, 0, frameData, 0, bytesRead)
                    
                    emit(AudioFrame(
                        data = frameData,
                        timestamp = System.currentTimeMillis(),
                        size = bytesRead
                    ))
                } else if (bytesRead < 0) {
                    debugLogger.w(TAG, "Error reading audio data: $bytesRead")
                    break
                }
            }
            
        } catch (e: Exception) {
            debugLogger.e(TAG, "Error during audio recording: ${e.message}", e)
            throw e
        } finally {
            stopRecording()
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Records audio to a file
     */
    suspend fun recordToFile(
        outputFile: File,
        durationMs: Long = 10000L, // 10 seconds default
        config: RecordingConfig = getRecordingConfig()
    ): RecordingResult = withContext(Dispatchers.IO) {
        try {
            if (!hasRecordAudioPermission()) {
                return@withContext RecordingResult(
                    success = false,
                    error = "RECORD_AUDIO permission not granted"
                )
            }

            if (activePreferencesConfig?.enableAudioRecording == false) {
                return@withContext RecordingResult(
                    success = false,
                    error = "Audio recording disabled in settings"
                )
            }

            val audioRec = audioRecord ?: return@withContext RecordingResult(
                success = false,
                error = "AudioRecorder not initialized"
            )
            
            if (isRecording) {
                return@withContext RecordingResult(
                    success = false,
                    error = "Recording already in progress"
                )
            }
            
            // Ensure output directory exists
            outputFile.parentFile?.mkdirs()
            
            audioRec.startRecording()
            isRecording = true
            
            debugLogger.d(TAG, "Started recording to file: ${outputFile.absolutePath}")
            
            val buffer = ByteArray(config.bufferSize)
            val outputStream = FileOutputStream(outputFile)
            val startTime = System.currentTimeMillis()
            var totalBytesRecorded = 0L
            
            try {
                while (isRecording && audioRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= durationMs) {
                        break
                    }
                    
                    val bytesRead = audioRec.read(buffer, 0, buffer.size)
                    
                    if (bytesRead > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRecorded += bytesRead
                    } else if (bytesRead < 0) {
                        debugLogger.w(TAG, "Error reading audio data (file): $bytesRead")
                        break
                    }
                }
                
                val actualDuration = System.currentTimeMillis() - startTime
                
                debugLogger.d(TAG, "Recording completed. Duration: ${actualDuration}ms, Bytes: $totalBytesRecorded")
                
                RecordingResult(
                    success = true,
                    message = "Recording completed successfully",
                    duration = actualDuration
                )
                
            } finally {
                outputStream.close()
                stopRecording()
            }
            
        } catch (e: Exception) {
            debugLogger.e(TAG, "Error recording to file: ${e.message}", e)
            RecordingResult(
                success = false,
                error = "Recording failed: ${e.message}"
            )
        }
    }
    
    /**
     * Records audio and returns raw audio data
     */
    suspend fun recordAudioData(
        durationMs: Long = 5000L, // 5 seconds default
        config: RecordingConfig = getRecordingConfig()
    ): RecordingResult = withContext(Dispatchers.IO) {
        try {
            if (!hasRecordAudioPermission()) {
                return@withContext RecordingResult(
                    success = false,
                    error = "RECORD_AUDIO permission not granted"
                )
            }

            if (activePreferencesConfig?.enableAudioRecording == false) {
                return@withContext RecordingResult(
                    success = false,
                    error = "Audio recording disabled in settings"
                )
            }

            val audioRec = audioRecord ?: return@withContext RecordingResult(
                success = false,
                error = "AudioRecorder not initialized"
            )
            
            if (isRecording) {
                return@withContext RecordingResult(
                    success = false,
                    error = "Recording already in progress"
                )
            }
            
            audioRec.startRecording()
            isRecording = true
            
            debugLogger.d(TAG, "Started recording audio data")
            
            val buffer = ByteArray(config.bufferSize)
            val audioData = mutableListOf<Byte>()
            val startTime = System.currentTimeMillis()
            
            try {
                while (isRecording && audioRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= durationMs) {
                        break
                    }
                    
                    val bytesRead = audioRec.read(buffer, 0, buffer.size)
                    
                    if (bytesRead > 0) {
                        audioData.addAll(buffer.sliceArray(0 until bytesRead).asList())
                    } else if (bytesRead < 0) {
                        debugLogger.w(TAG, "Error reading audio data (buffer): $bytesRead")
                        break
                    }
                }
                
                val actualDuration = System.currentTimeMillis() - startTime
                val resultData = audioData.toByteArray()
                
                debugLogger.d(TAG, "Audio data recording completed. Duration: ${actualDuration}ms, Bytes: ${resultData.size}")
                
                RecordingResult(
                    success = true,
                    message = "Audio data recorded successfully",
                    audioData = resultData,
                    duration = actualDuration
                )
                
            } finally {
                stopRecording()
            }
            
        } catch (e: Exception) {
            debugLogger.e(TAG, "Error recording audio data: ${e.message}", e)
            RecordingResult(
                success = false,
                error = "Recording failed: ${e.message}"
            )
        }
    }
    
    /**
     * Stops the current recording
     */
    fun stopRecording() {
        if (isRecording) {
            isRecording = false
            
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                // Critical fix: Always release resources
                if (state == AudioRecord.STATE_INITIALIZED) {
                    release()
                }
            }
            audioRecord = null
            
            debugLogger.d(TAG, "Stopped audio recording and released resources")
        }
    }
    
    /**
     * Pauses the current recording
     */
    fun pauseRecording() {
        if (isRecording) {
            isRecording = false
            audioRecord?.stop()
            debugLogger.d(TAG, "Paused audio recording")
        }
    }
    
    /**
     * Resumes the current recording
     */
    fun resumeRecording() {
        if (!isRecording && audioRecord != null) {
            isRecording = true
            audioRecord?.startRecording()
            debugLogger.d(TAG, "Resumed audio recording")
        }
    }
    
    /**
     * Checks if currently recording
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Gets the current recording state
     */
    fun getRecordingState(): Int {
        return audioRecord?.recordingState ?: AudioRecord.RECORDSTATE_STOPPED
    }
    
    /**
     * Gets the audio session ID
     */
    fun getAudioSessionId(): Int {
        return audioRecord?.audioSessionId ?: -1
    }
    
    /**
     * Gets the current recording configuration
     */
    fun getRecordingConfig(): RecordingConfig {
        return activeRecordingConfig
    }
    
    /**
     * Converts PCM audio data to WAV format
     */
    fun convertPcmToWav(
        pcmData: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val wavData = ByteBuffer.allocate(44 + pcmData.size)
        wavData.order(ByteOrder.LITTLE_ENDIAN)
        
        // WAV header
        wavData.put("RIFF".toByteArray())
        wavData.putInt(36 + pcmData.size)
        wavData.put("WAVE".toByteArray())
        wavData.put("fmt ".toByteArray())
        wavData.putInt(16) // PCM format chunk size
        wavData.putShort(1.toShort()) // PCM format
        wavData.putShort(channels.toShort())
        wavData.putInt(sampleRate)
        wavData.putInt(sampleRate * channels * bitsPerSample / 8) // Byte rate
        wavData.putShort((channels * bitsPerSample / 8).toShort()) // Block align
        wavData.putShort(bitsPerSample.toShort())
        wavData.put("data".toByteArray())
        wavData.putInt(pcmData.size)
        wavData.put(pcmData)
        
        return wavData.array()
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        stopRecording()
        
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                release()
            }
        }
        audioRecord = null
        configurationObserver.cleanup()
        recorderScope.cancel()
        
    debugLogger.d(TAG, "AudioRecorder cleaned up")
    }
}

/**
 * Foreground service for continuous audio recording.
 * Provides persistent audio recording capabilities with proper lifecycle management.
 */
    class AudioRecordingService : dagger.android.DaggerService() {
    
    companion object {
        private const val TAG = "AudioRecordingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_recording_channel"
        private const val ACTION_START_RECORDING = "start_recording"
        private const val ACTION_STOP_RECORDING = "stop_recording"
        private const val ACTION_PAUSE_RECORDING = "pause_recording"
        private const val ACTION_RESUME_RECORDING = "resume_recording"
        
        // Intent extras
        const val EXTRA_RECORDING_CONFIG = "recording_config"
        const val EXTRA_OUTPUT_FILE = "output_file"
        const val EXTRA_DURATION_MS = "duration_ms"
        
        fun startRecording(
            context: Context,
            config: AudioRecorder.RecordingConfig = AudioRecorder.RecordingConfig(),
            outputFile: File? = null,
            durationMs: Long = 0L
        ) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_RECORDING_CONFIG, config)
                outputFile?.let { putExtra(EXTRA_OUTPUT_FILE, it.absolutePath) }
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopRecording(context: Context) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
        
        fun pauseRecording(context: Context) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_PAUSE_RECORDING
            }
            context.startService(intent)
        }
        
        fun resumeRecording(context: Context) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_RESUME_RECORDING
            }
            context.startService(intent)
        }
    }
    
    private val binder = AudioRecordingBinder()
    @javax.inject.Inject
    lateinit var audioRecorder: AudioRecorder
    @javax.inject.Inject
    lateinit var errorRecoverySystem: com.example.gloabtranslate.core.error.ErrorRecoverySystem
    @javax.inject.Inject
    lateinit var externalServiceStateReporter: com.example.gloabtranslate.core.external.ExternalServiceStateReporter
    @javax.inject.Inject
    lateinit var configurationManager: com.example.gloabtranslate.core.data.config.ConfigurationManager

    private var audioConfigCollectionJob: Job? = null
    private var recordingJob: Job? = null
    private var isServiceRunning = false
    
    // Service state
    private var currentConfig: AudioRecorder.RecordingConfig? = null
    private var outputFile: File? = null
    private var durationMs: Long = 0L
    private var startTime: Long = 0L
    
    /**
     * Binder for service communication
     */
    inner class AudioRecordingBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }
    
    /**
     * Service state listener interface
     */
    interface ServiceStateListener {
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onRecordingPaused()
        fun onRecordingResumed()
        fun onRecordingError(error: String)
        fun onRecordingProgress(progress: Float, duration: Long)
    }
    
    private var stateListener: ServiceStateListener? = null
    
    /**
     * Sets the service state listener
     */
    fun setStateListener(listener: ServiceStateListener?) {
        stateListener = listener
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // audioRecorder provided by DI
        Log.d(TAG, "AudioRecordingService created (DI)")
        externalServiceStateReporter.report(
            com.example.gloabtranslate.core.external.ExternalServiceType.AUDIO_RECORDING,
            com.example.gloabtranslate.core.external.ExternalServiceStatus.INITIALIZING
        )
        startAudioConfigCollection()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val config = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RECORDING_CONFIG, AudioRecorder.RecordingConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<AudioRecorder.RecordingConfig>(EXTRA_RECORDING_CONFIG)
                } ?: AudioRecorder.RecordingConfig()
                val outputPath = intent.getStringExtra(EXTRA_OUTPUT_FILE)
                val duration = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                
                startRecording(config, outputPath?.let { File(it) }, duration)
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
            }
            ACTION_PAUSE_RECORDING -> {
                pauseRecording()
            }
            ACTION_RESUME_RECORDING -> {
                resumeRecording()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        audioRecorder.cleanup()
        audioConfigCollectionJob?.cancel()
        isServiceRunning = false
        Log.d(TAG, "AudioRecordingService destroyed")
        externalServiceStateReporter.report(
            com.example.gloabtranslate.core.external.ExternalServiceType.AUDIO_RECORDING,
            com.example.gloabtranslate.core.external.ExternalServiceStatus.STOPPED
        )
    }

    private fun startAudioConfigCollection() {
        if (audioConfigCollectionJob != null) return
        audioConfigCollectionJob = CoroutineScope(Dispatchers.IO).launch {
            var lastSampleRate: Int? = null
            var lastNoiseReduction: Boolean? = null
            var lastAudioQuality: String? = null
            configurationManager.audioConfig.collect { cfg ->
                try {
                    val sampleChanged = lastSampleRate != null && lastSampleRate != cfg.sampleRate
                    val nrChanged = lastNoiseReduction != null && lastNoiseReduction != cfg.enableNoiseReduction
                    val qualityChanged = lastAudioQuality != null && lastAudioQuality != cfg.audioQuality
                    lastSampleRate = cfg.sampleRate
                    lastNoiseReduction = cfg.enableNoiseReduction
                    lastAudioQuality = cfg.audioQuality
                    if (isServiceRunning && audioRecorder.isRecording() && (sampleChanged || nrChanged || qualityChanged)) {
                        Log.d(TAG, "Runtime audio config change detected (restart): sampleRate=${cfg.sampleRate}, noiseReduction=${cfg.enableNoiseReduction}, quality=${cfg.audioQuality}")
                        safeRestartWithConfig(cfg)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed applying audio config update", t)
                }
            }
        }
    }

    private suspend fun safeRestartWithConfig(cfg: com.example.gloabtranslate.core.data.config.AudioConfig) {
        // Capture current high-level recording parameters
        val activeConfig = currentConfig ?: return
        try {
            pauseRecording()
            // Map config changes into a new RecordingConfig (placeholder: sampleRate may be used internally already)
            // For now we simply resume; deeper re-init would require exposing reconfigure() in AudioRecorder.
            resumeRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "Error during audio recorder restart for config update", t)
            stateListener?.onRecordingError("Audio config update failed: ${t.message}")
        }
    }
    
    /**
     * Starts recording with the given configuration
     */
    private fun startRecording(
        config: AudioRecorder.RecordingConfig,
        outputFile: File?,
        durationMs: Long
    ) {
        if (isServiceRunning) {
            Log.w(TAG, "Recording already in progress")
            return
        }
        
        currentConfig = config
        this.outputFile = outputFile
        this.durationMs = durationMs
        startTime = System.currentTimeMillis()
        
        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification("Recording..."))
        
        // Initialize and start recording
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                @Suppress("MissingPermission")
                val recoveryResult = errorRecoverySystem.executeWithRecovery(
                    source = com.example.gloabtranslate.core.error.ErrorRecoverySystem.ErrorSource.AUDIO_RECORDING,
                    operation = {
                        val initResult = audioRecorder.initialize(config)
                        if (!initResult.success) {
                            throw RuntimeException(initResult.error ?: "Initialization failed")
                        }
                        true
                    },
                    recoveryAction = {
                        // Basic backoff before retrying initialization
                        kotlinx.coroutines.delay(1000)
                        true
                    }
                )
                if (recoveryResult.isSuccess) {
                    isServiceRunning = true
                    stateListener?.onRecordingStarted()
                    externalServiceStateReporter.report(
                        com.example.gloabtranslate.core.external.ExternalServiceType.AUDIO_RECORDING,
                        com.example.gloabtranslate.core.external.ExternalServiceStatus.READY
                    )
                    if (outputFile != null && durationMs > 0) {
                        val result = audioRecorder.recordToFile(outputFile!!, durationMs, config)
                        if (result.success) {
                            updateNotification("Recording completed")
                        } else {
                            stateListener?.onRecordingError(result.error ?: "Recording failed")
                            externalServiceStateReporter.report(
                                com.example.gloabtranslate.core.external.ExternalServiceType.AUDIO_RECORDING,
                                com.example.gloabtranslate.core.external.ExternalServiceStatus.ERROR,
                                result.error
                            )
                        }
                    } else {
                        startContinuousRecording(config)
                    }
                } else {
                    stateListener?.onRecordingError(recoveryResult.exceptionOrNull()?.message ?: "Initialization failed")
                    externalServiceStateReporter.report(
                        com.example.gloabtranslate.core.external.ExternalServiceType.AUDIO_RECORDING,
                        com.example.gloabtranslate.core.external.ExternalServiceStatus.ERROR,
                        recoveryResult.exceptionOrNull()?.message
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                stateListener?.onRecordingError("Recording error: ${e.message}")
            }
        }
    }
    
    /**
     * Starts continuous recording
     */
    private suspend fun startContinuousRecording(config: AudioRecorder.RecordingConfig) {
        try {
            audioRecorder.startRecording(config).collect { audioFrame ->
                // Handle continuous audio frames
                // This could be extended to process audio in real-time
                
                // Update progress if duration is specified
                if (durationMs > 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceAtMost(1f)
                    stateListener?.onRecordingProgress(progress, elapsed)
                    
                    if (elapsed >= durationMs) {
                        stopRecording()
                    }
                }
                
                // Update notification with recording status
                updateNotification("Recording... ${audioFrame.size} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in continuous recording", e)
            stateListener?.onRecordingError("Continuous recording error: ${e.message}")
        }
    }
    
    /**
     * Stops the current recording
     */
    private fun stopRecording() {
        if (!isServiceRunning) return
        
        recordingJob?.cancel()
    audioRecorder.stopRecording()
        isServiceRunning = false
        
        stateListener?.onRecordingStopped()
        updateNotification("Recording stopped")
        
        // Stop foreground service
        stopForeground(true)
        stopSelf()
        
        Log.d(TAG, "Recording stopped")
        externalServiceStateReporter.report(
            com.example.gloabtranslate.core.external.ExternalServiceType.AUDIO_RECORDING,
            com.example.gloabtranslate.core.external.ExternalServiceStatus.STOPPED
        )
    }
    
    /**
     * Pauses the current recording
     */
    private fun pauseRecording() {
        if (!isServiceRunning) return
        
    audioRecorder.pauseRecording()
        stateListener?.onRecordingPaused()
        updateNotification("Recording paused")
        
        Log.d(TAG, "Recording paused")
    }
    
    /**
     * Resumes the current recording
     */
    private fun resumeRecording() {
        if (!isServiceRunning) return
        
    audioRecorder.resumeRecording()
        stateListener?.onRecordingResumed()
        updateNotification("Recording resumed")
        
        Log.d(TAG, "Recording resumed")
    }
    
    /**
     * Gets the current recording state
     */
    fun getRecordingState(): String {
        return when {
            !isServiceRunning -> "STOPPED"
            audioRecorder.isRecording() -> "RECORDING"
            else -> "PAUSED"
        }
    }
    
    /**
     * Gets recording statistics
     */
    fun getRecordingStats(): Map<String, Any> {
        val elapsed = System.currentTimeMillis() - startTime
        return mapOf(
            "isRunning" to isServiceRunning,
            "elapsedMs" to elapsed,
            "durationMs" to durationMs,
            "audioSessionId" to (audioRecorder.getAudioSessionId() ?: -1),
            "recordingState" to getRecordingState()
        )
    }
    
    /**
     * Creates the notification channel for Android O and above
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio recording service notification"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Creates a notification for the foreground service
     */
    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, AudioRecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Recording")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                createActionPendingIntent(ACTION_PAUSE_RECORDING)
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                createActionPendingIntent(ACTION_STOP_RECORDING)
            )
            .build()
    }
    
    /**
     * Updates the notification content
     */
    private fun updateNotification(contentText: String) {
        if (isServiceRunning) {
            val notification = createNotification(contentText)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
    
    /**
     * Creates a pending intent for notification actions
     */
    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioRecordingService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Service manager for audio recording operations.
 * Provides high-level interface for managing audio recording services.
 */
class AudioRecordingManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioRecordingManager"
    }
    
    private var boundService: AudioRecordingService? = null
    private var isBound = false
    
    /**
     * Service connection callback
     */
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as AudioRecordingService.AudioRecordingBinder
            boundService = binder.getService()
            isBound = true
            Log.d(TAG, "Service connected")
        }
        
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            boundService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }
    
    /**
     * Starts audio recording service
     */
    fun startRecording(
        config: AudioRecorder.RecordingConfig = AudioRecorder.RecordingConfig(),
        outputFile: File? = null,
        durationMs: Long = 0L
    ) {
        AudioRecordingService.startRecording(context, config, outputFile, durationMs)
    }
    
    /**
     * Stops audio recording service
     */
    fun stopRecording() {
        AudioRecordingService.stopRecording(context)
    }
    
    /**
     * Pauses audio recording
     */
    fun pauseRecording() {
        AudioRecordingService.pauseRecording(context)
    }
    
    /**
     * Resumes audio recording
     */
    fun resumeRecording() {
        AudioRecordingService.resumeRecording(context)
    }
    
    /**
     * Binds to the audio recording service
     */
    fun bindService() {
        if (!isBound) {
            val intent = Intent(context, AudioRecordingService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    /**
     * Unbinds from the audio recording service
     */
    fun unbindService() {
        if (isBound) {
            context.unbindService(serviceConnection!!)
            isBound = false
            boundService = null
        }
    }
    
    /**
     * Gets the current recording state
     */
    fun getRecordingState(): String? {
        return boundService?.getRecordingState()
    }
    
    /**
     * Gets recording statistics
     */
    fun getRecordingStats(): Map<String, Any>? {
        return boundService?.getRecordingStats()
    }
    
    /**
     * Sets the service state listener
     */
    fun setStateListener(listener: AudioRecordingService.ServiceStateListener?) {
        boundService?.setStateListener(listener)
    }
    
    /**
     * Checks if service is bound
     */
    fun isServiceBound(): Boolean = isBound
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        unbindService()
    }
}

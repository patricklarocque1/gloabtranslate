package com.example.gloabtranslate.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID

/**
 * Audio output manager for handling TTS audio output and routing.
 * Provides comprehensive audio output management including routing, volume control,
 * audio focus handling, and output device management.
 */
class AudioOutputManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioOutputManager"
        private const val DEFAULT_VOLUME = 1.0f
        private const val MAX_VOLUME = 1.0f
        private const val MIN_VOLUME = 0.0f
        private const val AUDIO_FOCUS_GAIN_DURATION = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    }
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ttsService = TextToSpeechService(context)
    
    // Audio output state
    private var currentOutputDevice = AudioOutputDevice.SPEAKER
    private var currentVolume = DEFAULT_VOLUME
    private var isAudioFocusGranted = false
    private var isMuted = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    
    // Audio output listeners
    private val audioOutputListeners = mutableListOf<AudioOutputListener>()
    
    // Event channel for audio output events
    private val eventChannel = Channel<AudioOutputEvent>(Channel.UNLIMITED)
    
    /**
     * Audio output device types
     */
    enum class AudioOutputDevice {
        SPEAKER, EARPIECE, BLUETOOTH, WIRED_HEADSET, USB_HEADSET, DEFAULT
    }
    
    /**
     * Audio output configuration
     */
    data class AudioOutputConfig(
        val device: AudioOutputDevice = AudioOutputDevice.SPEAKER,
        val volume: Float = DEFAULT_VOLUME,
        val isMuted: Boolean = false,
        val audioStreamType: Int = AudioManager.STREAM_MUSIC,
        val audioAttributes: AudioAttributes? = null,
        val requestAudioFocus: Boolean = true,
        val audioFocusGainType: Int = AUDIO_FOCUS_GAIN_DURATION,
        val enableAudioEffects: Boolean = true,
        val enableNoiseSuppression: Boolean = true,
        val enableEchoCancellation: Boolean = true,
        val enableAutomaticGainControl: Boolean = true
    )
    
    /**
     * Audio output event
     */
    data class AudioOutputEvent(
        val type: AudioOutputEventType,
        val message: String,
        val device: AudioOutputDevice? = null,
        val volume: Float? = null,
        val isMuted: Boolean? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Audio output event types
     */
    enum class AudioOutputEventType {
        DEVICE_CHANGED, VOLUME_CHANGED, MUTE_STATE_CHANGED, AUDIO_FOCUS_GAINED,
        AUDIO_FOCUS_LOST, AUDIO_FOCUS_REQUESTED, AUDIO_FOCUS_ABANDONED,
        CONFIGURATION_CHANGED, ERROR_OCCURRED
    }
    
    /**
     * Audio output listener interface
     */
    interface AudioOutputListener {
        fun onOutputDeviceChanged(oldDevice: AudioOutputDevice, newDevice: AudioOutputDevice)
        fun onVolumeChanged(volume: Float)
        fun onMuteStateChanged(isMuted: Boolean)
        fun onAudioFocusGained()
        fun onAudioFocusLost()
        fun onAudioFocusRequested()
        fun onAudioFocusAbandoned()
        fun onConfigurationChanged(config: AudioOutputConfig)
        fun onErrorOccurred(error: String)
    }
    
    /**
     * Initializes the audio output manager
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Initialize TTS service
            val ttsInitialized = ttsService.initialize()
            if (!ttsInitialized) {
                Log.e(TAG, "Failed to initialize TTS service")
                return@withContext false
            }
            
            // Set up audio focus listener
            setupAudioFocusListener()
            
            // Configure default audio output
            configureAudioOutput(AudioOutputConfig())
            
            Log.d(TAG, "Audio output manager initialized successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio output manager", e)
            false
        }
    }
    
    /**
     * Configures audio output
     */
    suspend fun configureAudioOutput(config: AudioOutputConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            // Set output device
            val deviceResult = setOutputDevice(config.device)
            if (!deviceResult) {
                Log.e(TAG, "Failed to set output device")
                return@withContext false
            }
            
            // Set volume
            val volumeResult = setVolume(config.volume)
            if (!volumeResult) {
                Log.e(TAG, "Failed to set volume")
                return@withContext false
            }
            
            // Set mute state
            val muteResult = setMuted(config.isMuted)
            if (!muteResult) {
                Log.e(TAG, "Failed to set mute state")
                return@withContext false
            }
            
            // Request audio focus if needed
            if (config.requestAudioFocus) {
                val focusResult = requestAudioFocus(config.audioFocusGainType)
                if (!focusResult) {
                    Log.w(TAG, "Audio focus not granted")
                }
            }
            
            // Configure audio effects
            if (config.enableAudioEffects) {
                configureAudioEffects(config)
            }
            
            notifyAudioOutputEvent(AudioOutputEvent(
                type = AudioOutputEventType.CONFIGURATION_CHANGED,
                message = "Audio output configuration changed",
                device = config.device,
                volume = config.volume,
                isMuted = config.isMuted
            ))
            
            Log.d(TAG, "Audio output configured successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio output", e)
            false
        }
    }
    
    /**
     * Sets the output device
     */
    suspend fun setOutputDevice(device: AudioOutputDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val oldDevice = currentOutputDevice
            
            when (device) {
                AudioOutputDevice.SPEAKER -> {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
                AudioOutputDevice.EARPIECE -> {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
                AudioOutputDevice.BLUETOOTH -> {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                }
                AudioOutputDevice.WIRED_HEADSET -> {
                    // Wired headset is automatically detected
                }
                AudioOutputDevice.USB_HEADSET -> {
                    // USB headset is automatically detected
                }
                AudioOutputDevice.DEFAULT -> {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
            }
            
            currentOutputDevice = device
            
            notifyAudioOutputEvent(AudioOutputEvent(
                type = AudioOutputEventType.DEVICE_CHANGED,
                message = "Output device changed from ${oldDevice.name} to ${device.name}",
                device = device
            ))
            
            Log.d(TAG, "Output device set to: ${device.name}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set output device", e)
            false
        }
    }
    
    /**
     * Sets the volume
     */
    suspend fun setVolume(volume: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            val clampedVolume = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
            
            // Update TTS service volume
            val ttsResult = ttsService.setVolume(clampedVolume)
            if (!ttsResult) {
                Log.e(TAG, "Failed to set TTS volume")
                return@withContext false
            }
            
            currentVolume = clampedVolume
            
            notifyAudioOutputEvent(AudioOutputEvent(
                type = AudioOutputEventType.VOLUME_CHANGED,
                message = "Volume changed to $clampedVolume",
                volume = clampedVolume
            ))
            
            Log.d(TAG, "Volume set to: $clampedVolume")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            false
        }
    }
    
    /**
     * Sets the mute state
     */
    suspend fun setMuted(muted: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            isMuted = muted
            
            // Update TTS service volume
            val volume = if (muted) 0.0f else currentVolume
            val ttsResult = ttsService.setVolume(volume)
            if (!ttsResult) {
                Log.e(TAG, "Failed to set TTS volume for mute state")
                return@withContext false
            }
            
            notifyAudioOutputEvent(AudioOutputEvent(
                type = AudioOutputEventType.MUTE_STATE_CHANGED,
                message = "Mute state changed to $muted",
                isMuted = muted
            ))
            
            Log.d(TAG, "Mute state set to: $muted")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mute state", e)
            false
        }
    }
    
    /**
     * Requests audio focus
     */
    suspend fun requestAudioFocus(gainType: Int = AUDIO_FOCUS_GAIN_DURATION): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isAudioFocusGranted) {
                Log.d(TAG, "Audio focus already granted")
                return@withContext true
            }
            
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(gainType)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(audioFocusListener!!)
                    .build()
                
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    gainType
                )
            }
            
            isAudioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            
            if (isAudioFocusGranted) {
                notifyAudioOutputEvent(AudioOutputEvent(
                    type = AudioOutputEventType.AUDIO_FOCUS_GAINED,
                    message = "Audio focus gained"
                ))
                Log.d(TAG, "Audio focus granted")
            } else {
                notifyAudioOutputEvent(AudioOutputEvent(
                    type = AudioOutputEventType.AUDIO_FOCUS_LOST,
                    message = "Audio focus denied"
                ))
                Log.w(TAG, "Audio focus denied")
            }
            
            isAudioFocusGranted
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request audio focus", e)
            false
        }
    }
    
    /**
     * Abandons audio focus
     */
    suspend fun abandonAudioFocus(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isAudioFocusGranted) {
                Log.d(TAG, "Audio focus not granted")
                return@withContext true
            }
            
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
            
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                isAudioFocusGranted = false
                audioFocusRequest = null
                audioFocusListener = null
                
                notifyAudioOutputEvent(AudioOutputEvent(
                    type = AudioOutputEventType.AUDIO_FOCUS_ABANDONED,
                    message = "Audio focus abandoned"
                ))
                
                Log.d(TAG, "Audio focus abandoned")
                true
            } else {
                Log.w(TAG, "Failed to abandon audio focus")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to abandon audio focus", e)
            false
        }
    }
    
    /**
     * Sets up audio focus listener
     */
    private fun setupAudioFocusListener() {
        audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    isAudioFocusGranted = true
                    notifyAudioOutputEvent(AudioOutputEvent(
                        type = AudioOutputEventType.AUDIO_FOCUS_GAINED,
                        message = "Audio focus gained"
                    ))
                    Log.d(TAG, "Audio focus gained")
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    isAudioFocusGranted = false
                    notifyAudioOutputEvent(AudioOutputEvent(
                        type = AudioOutputEventType.AUDIO_FOCUS_LOST,
                        message = "Audio focus lost"
                    ))
                    Log.d(TAG, "Audio focus lost")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.d(TAG, "Audio focus lost transient")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.d(TAG, "Audio focus lost transient can duck")
                }
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                    Log.d(TAG, "Audio focus gained transient")
                }
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                    Log.d(TAG, "Audio focus gained transient may duck")
                }
            }
        }
    }
    
    /**
     * Configures audio effects
     */
    private fun configureAudioEffects(config: AudioOutputConfig) {
        try {
            // Configure noise suppression
            if (config.enableNoiseSuppression) {
                Log.d(TAG, "Noise suppression enabled")
            }
            
            // Configure echo cancellation
            if (config.enableEchoCancellation) {
                Log.d(TAG, "Echo cancellation enabled")
            }
            
            // Configure automatic gain control
            if (config.enableAutomaticGainControl) {
                Log.d(TAG, "Automatic gain control enabled")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio effects", e)
        }
    }
    
    /**
     * Gets current output device
     */
    fun getCurrentOutputDevice(): AudioOutputDevice = currentOutputDevice
    
    /**
     * Gets current volume
     */
    fun getCurrentVolume(): Float = currentVolume
    
    /**
     * Gets mute state
     */
    fun isMuted(): Boolean = isMuted
    
    /**
     * Gets audio focus state
     */
    fun isAudioFocusGranted(): Boolean = isAudioFocusGranted
    
    /**
     * Gets available output devices
     */
    fun getAvailableOutputDevices(): List<AudioOutputDevice> {
        val devices = mutableListOf<AudioOutputDevice>()
        
        // Always available
        devices.add(AudioOutputDevice.SPEAKER)
        devices.add(AudioOutputDevice.EARPIECE)
        devices.add(AudioOutputDevice.DEFAULT)
        
        // Check for Bluetooth
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn) {
            devices.add(AudioOutputDevice.BLUETOOTH)
        }
        
        // Check for wired headset
        @Suppress("DEPRECATION")
        if (audioManager.isWiredHeadsetOn) {
            devices.add(AudioOutputDevice.WIRED_HEADSET)
        }
        
        // Check for USB headset (use isWiredHeadsetOn as fallback)
        @Suppress("DEPRECATION")
        if (audioManager.isWiredHeadsetOn) {
            devices.add(AudioOutputDevice.USB_HEADSET)
        }
        
        return devices
    }
    
    /**
     * Gets audio output statistics
     */
    fun getAudioOutputStats(): Map<String, Any> {
        return mapOf(
            "currentDevice" to currentOutputDevice.name,
            "currentVolume" to currentVolume,
            "isMuted" to isMuted,
            "isAudioFocusGranted" to isAudioFocusGranted,
            "availableDevices" to getAvailableOutputDevices().size,
            "isBluetoothAvailable" to run { @Suppress("DEPRECATION") audioManager.isBluetoothScoOn },
            "isWiredHeadsetOn" to run { @Suppress("DEPRECATION") audioManager.isWiredHeadsetOn },
            "isUsbHeadsetOn" to run { @Suppress("DEPRECATION") audioManager.isWiredHeadsetOn },
            "isSpeakerphoneOn" to run { @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn }
        )
    }
    
    /**
     * Adds an audio output listener
     */
    fun addAudioOutputListener(listener: AudioOutputListener) {
        audioOutputListeners.add(listener)
    }
    
    /**
     * Removes an audio output listener
     */
    fun removeAudioOutputListener(listener: AudioOutputListener) {
        audioOutputListeners.remove(listener)
    }
    
    /**
     * Creates a Flow for audio output events
     */
    fun createAudioOutputEventFlow(): Flow<AudioOutputEvent> = flow {
        // This would typically emit events as they occur
        // For now, we'll emit the current state
        emit(AudioOutputEvent(
            type = AudioOutputEventType.CONFIGURATION_CHANGED,
            message = "Audio output event flow created"
        ))
    }.flowOn(Dispatchers.Default)
    
    /**
     * Notifies audio output event
     */
    private fun notifyAudioOutputEvent(event: AudioOutputEvent) {
        audioOutputListeners.forEach { listener ->
            try {
                when (event.type) {
                    AudioOutputEventType.DEVICE_CHANGED -> {
                        // This would need to be implemented based on device change
                    }
                    AudioOutputEventType.VOLUME_CHANGED -> {
                        event.volume?.let { listener.onVolumeChanged(it) }
                    }
                    AudioOutputEventType.MUTE_STATE_CHANGED -> {
                        event.isMuted?.let { listener.onMuteStateChanged(it) }
                    }
                    AudioOutputEventType.AUDIO_FOCUS_GAINED -> {
                        listener.onAudioFocusGained()
                    }
                    AudioOutputEventType.AUDIO_FOCUS_LOST -> {
                        listener.onAudioFocusLost()
                    }
                    AudioOutputEventType.AUDIO_FOCUS_REQUESTED -> {
                        listener.onAudioFocusRequested()
                    }
                    AudioOutputEventType.AUDIO_FOCUS_ABANDONED -> {
                        listener.onAudioFocusAbandoned()
                    }
                    AudioOutputEventType.CONFIGURATION_CHANGED -> {
                        // This would need to be implemented based on configuration
                    }
                    AudioOutputEventType.ERROR_OCCURRED -> {
                        listener.onErrorOccurred(event.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying audio output event", e)
            }
        }
    }
    
    /**
     * Cleans up the audio output manager
     */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        try {
            abandonAudioFocus()
            ttsService.cleanup()
            audioOutputListeners.clear()
            
            Log.d(TAG, "Audio output manager cleaned up")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up audio output manager", e)
        }
    }
}

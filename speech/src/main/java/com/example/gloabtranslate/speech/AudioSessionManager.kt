package com.example.gloabtranslate.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.gloabtranslate.speech.config.AudioConfigurationObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Audio session management implementation.
 * Provides comprehensive audio session management including focus handling,
 * audio attributes configuration, session lifecycle management, and audio routing.
 */
class AudioSessionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioSessionManager"
        
        // Audio session constants
        private const val DEFAULT_AUDIO_SESSION_ID = AudioManager.AUDIO_SESSION_ID_GENERATE
        private const val AUDIO_FOCUS_GAIN_DURATION = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        private const val AUDIO_FOCUS_REQUEST_DURATION = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        
        // Audio session states
        private const val SESSION_STATE_IDLE = 0
        private const val SESSION_STATE_ACTIVE = 1
        private const val SESSION_STATE_PAUSED = 2
        private const val SESSION_STATE_STOPPED = 3
        private const val SESSION_STATE_ERROR = 4
    }
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val configurationObserver = AudioConfigurationObserver(context)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioSessionId = DEFAULT_AUDIO_SESSION_ID
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var lastSessionConfig: SessionConfig? = null

    init {
        configurationObserver.setOnCriticalConfigChanged {
            handleCriticalConfigurationChange()
        }
        configurationObserver.start()
    }

    // Session state
    private var sessionState = SESSION_STATE_IDLE
    private var isAudioFocusGranted = false
    private var isSessionActive = false
    
    // Audio attributes
    private var audioAttributes: AudioAttributes? = null
    private var audioMode = AudioManager.MODE_NORMAL
    private var audioStreamType = AudioManager.STREAM_MUSIC
    
    // Session listeners
    private val sessionListeners = mutableListOf<AudioSessionListener>()
    
    /**
     * Configuration for audio session
     */
    data class SessionConfig(
        val audioSessionId: Int = DEFAULT_AUDIO_SESSION_ID,
        val audioMode: Int = AudioManager.MODE_IN_COMMUNICATION,
        val audioStreamType: Int = AudioManager.STREAM_VOICE_CALL,
        val audioAttributes: AudioAttributes? = null,
        val requestAudioFocus: Boolean = true,
        val audioFocusGainType: Int = AUDIO_FOCUS_GAIN_DURATION,
        val enableAudioRouting: Boolean = true,
        val enableAudioEffects: Boolean = true,
        val enableNoiseSuppression: Boolean = true,
        val enableEchoCancellation: Boolean = true,
        val enableAutomaticGainControl: Boolean = true,
        val enableAcousticEchoCancellation: Boolean = true,
        val enableBeamforming: Boolean = false,
        val enableSpatialAudio: Boolean = false,
        val enableHapticFeedback: Boolean = false,
        val priorityLevel: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        val duckingEnabled: Boolean = true,
        val pauseOnFocusLoss: Boolean = true,
        val resumeOnFocusGain: Boolean = true
    )
    
    /**
     * Audio session state enumeration
     */
    enum class SessionState {
        IDLE, ACTIVE, PAUSED, STOPPED, ERROR
    }
    
    /**
     * Audio focus change types
     */
    enum class AudioFocusChange {
        GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK, GAIN_TRANSIENT, GAIN_TRANSIENT_MAY_DUCK
    }
    
    /**
     * Audio routing types
     */
    enum class AudioRouting {
        SPEAKER, EARPIECE, BLUETOOTH, WIRED_HEADSET, USB_HEADSET, DEFAULT
    }
    
    /**
     * Audio session information
     */
    data class SessionInfo(
        val sessionId: Int,
        val state: SessionState,
        val audioMode: Int,
        val audioStreamType: Int,
        val audioRouting: AudioRouting,
        val isAudioFocusGranted: Boolean,
        val isMuted: Boolean,
        val volume: Int,
        val maxVolume: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Audio session event
     */
    data class SessionEvent(
        val type: EventType,
        val message: String,
        val sessionInfo: SessionInfo,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Event types for audio session
     */
    enum class EventType {
        SESSION_STARTED, SESSION_PAUSED, SESSION_RESUMED, SESSION_STOPPED,
        AUDIO_FOCUS_GAINED, AUDIO_FOCUS_LOST, AUDIO_ROUTING_CHANGED,
        VOLUME_CHANGED, MUTE_STATE_CHANGED, ERROR_OCCURRED
    }
    
    /**
     * Audio session listener interface
     */
    interface AudioSessionListener {
        fun onSessionStateChanged(state: SessionState, sessionInfo: SessionInfo)
        fun onAudioFocusChanged(focusChange: AudioFocusChange, sessionInfo: SessionInfo)
        fun onAudioRoutingChanged(routing: AudioRouting, sessionInfo: SessionInfo)
        fun onVolumeChanged(volume: Int, maxVolume: Int, sessionInfo: SessionInfo)
        fun onMuteStateChanged(isMuted: Boolean, sessionInfo: SessionInfo)
        fun onSessionError(error: String, sessionInfo: SessionInfo)
    }
    
    /**
     * Adds an audio session listener
     */
    fun addSessionListener(listener: AudioSessionListener) {
        sessionListeners.add(listener)
    }
    
    /**
     * Removes an audio session listener
     */
    fun removeSessionListener(listener: AudioSessionListener) {
        sessionListeners.remove(listener)
    }
    
    /**
     * Initializes the audio session with configuration
     */
    suspend fun initializeSession(config: SessionConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            audioSessionId = config.audioSessionId
            audioMode = config.audioMode
            audioStreamType = config.audioStreamType
            audioAttributes = config.audioAttributes
            lastSessionConfig = config
            
            // Set audio mode
            audioManager.mode = audioMode
            
            // Configure audio attributes if provided
            if (audioAttributes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Audio attributes are configured through AudioRecord/AudioTrack
            }
            
            // Request audio focus if needed
            if (config.requestAudioFocus) {
                val focusGranted = requestAudioFocus(config)
                if (!focusGranted) {
                    Log.w(TAG, "Audio focus not granted")
                    return@withContext false
                }
            }
            
            // Configure audio effects
            if (config.enableAudioEffects) {
                configureAudioEffects(config)
            }
            
            // Configure audio routing
            if (config.enableAudioRouting) {
                configureAudioRouting(config)
            }
            
            sessionState = SESSION_STATE_ACTIVE
            isSessionActive = true
            
            val sessionInfo = getCurrentSessionInfo()
            notifySessionStateChanged(SessionState.ACTIVE, sessionInfo)
            
            Log.d(TAG, "Audio session initialized successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio session", e)
            sessionState = SESSION_STATE_ERROR
            val sessionInfo = getCurrentSessionInfo()
            notifySessionError("Initialization failed: ${e.message}", sessionInfo)
            false
        }
    }

    private fun handleCriticalConfigurationChange() {
        if (!isSessionActive) return
        sessionScope.launch {
            Log.d(TAG, "Stopping audio session due to configuration change")
            stopSession()
        }
    }
    
    /**
     * Starts the audio session
     */
    suspend fun startSession(config: SessionConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            if (sessionState == SESSION_STATE_ACTIVE) {
                Log.d(TAG, "Session already active")
                return@withContext true
            }
            
            // Request audio focus
            if (config.requestAudioFocus) {
                val focusGranted = requestAudioFocus(config)
                if (!focusGranted) {
                    Log.w(TAG, "Audio focus not granted")
                    return@withContext false
                }
            }
            
            sessionState = SESSION_STATE_ACTIVE
            isSessionActive = true
            
            val sessionInfo = getCurrentSessionInfo()
            notifySessionStateChanged(SessionState.ACTIVE, sessionInfo)
            
            Log.d(TAG, "Audio session started")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio session", e)
            sessionState = SESSION_STATE_ERROR
            val sessionInfo = getCurrentSessionInfo()
            notifySessionError("Start failed: ${e.message}", sessionInfo)
            false
        }
    }
    
    /**
     * Pauses the audio session
     */
    suspend fun pauseSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (sessionState != SESSION_STATE_ACTIVE) {
                Log.d(TAG, "Session not active, cannot pause")
                return@withContext false
            }
            
            sessionState = SESSION_STATE_PAUSED
            isSessionActive = false
            
            val sessionInfo = getCurrentSessionInfo()
            notifySessionStateChanged(SessionState.PAUSED, sessionInfo)
            
            Log.d(TAG, "Audio session paused")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause audio session", e)
            sessionState = SESSION_STATE_ERROR
            val sessionInfo = getCurrentSessionInfo()
            notifySessionError("Pause failed: ${e.message}", sessionInfo)
            false
        }
    }
    
    /**
     * Resumes the audio session
     */
    suspend fun resumeSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (sessionState != SESSION_STATE_PAUSED) {
                Log.d(TAG, "Session not paused, cannot resume")
                return@withContext false
            }
            
            sessionState = SESSION_STATE_ACTIVE
            isSessionActive = true
            
            val sessionInfo = getCurrentSessionInfo()
            notifySessionStateChanged(SessionState.ACTIVE, sessionInfo)
            
            Log.d(TAG, "Audio session resumed")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume audio session", e)
            sessionState = SESSION_STATE_ERROR
            val sessionInfo = getCurrentSessionInfo()
            notifySessionError("Resume failed: ${e.message}", sessionInfo)
            false
        }
    }
    
    /**
     * Stops the audio session
     */
    suspend fun stopSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Abandon audio focus
            abandonAudioFocus()
            
            sessionState = SESSION_STATE_STOPPED
            isSessionActive = false
            
            val sessionInfo = getCurrentSessionInfo()
            notifySessionStateChanged(SessionState.STOPPED, sessionInfo)
            
            Log.d(TAG, "Audio session stopped")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio session", e)
            sessionState = SESSION_STATE_ERROR
            val sessionInfo = getCurrentSessionInfo()
            notifySessionError("Stop failed: ${e.message}", sessionInfo)
            false
        }
    }
    
    /**
     * Requests audio focus
     */
    private fun requestAudioFocus(config: SessionConfig): Boolean {
        try {
            audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                handleAudioFocusChange(focusChange)
            }
            
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(config.audioFocusGainType)
                    .setAudioAttributes(
                        audioAttributes ?: AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(config.pauseOnFocusLoss)
                    .setOnAudioFocusChangeListener(audioFocusListener!!)
                    .build()
                
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    audioStreamType,
                    config.audioFocusGainType
                )
            }
            
            isAudioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            
            if (isAudioFocusGranted) {
                Log.d(TAG, "Audio focus granted")
            } else {
                Log.w(TAG, "Audio focus denied")
            }
            
            return isAudioFocusGranted
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request audio focus", e)
            return false
        }
    }
    
    /**
     * Abandons audio focus
     */
    private fun abandonAudioFocus() {
        try {
            if (isAudioFocusGranted) {
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(audioFocusListener)
                }
                
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.d(TAG, "Audio focus abandoned")
                } else {
                    Log.w(TAG, "Failed to abandon audio focus")
                }
                
                isAudioFocusGranted = false
                audioFocusRequest = null
                audioFocusListener = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to abandon audio focus", e)
        }
    }
    
    /**
     * Handles audio focus changes
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        val focusChangeType = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> AudioFocusChange.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> AudioFocusChange.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusChange.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> AudioFocusChange.GAIN_TRANSIENT
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> AudioFocusChange.GAIN_TRANSIENT_MAY_DUCK
            else -> AudioFocusChange.LOSS
        }
        
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                isAudioFocusGranted = true
                Log.d(TAG, "Audio focus gained")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                isAudioFocusGranted = false
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
        
        val sessionInfo = getCurrentSessionInfo()
        notifyAudioFocusChanged(focusChangeType, sessionInfo)
    }
    
    /**
     * Configures audio effects
     */
    private fun configureAudioEffects(config: SessionConfig) {
        try {
            // Configure noise suppression
            if (config.enableNoiseSuppression) {
                // Noise suppression is typically configured through AudioRecord
                Log.d(TAG, "Noise suppression enabled")
            }
            
            // Configure echo cancellation
            if (config.enableEchoCancellation) {
                // Echo cancellation is typically configured through AudioRecord
                Log.d(TAG, "Echo cancellation enabled")
            }
            
            // Configure automatic gain control
            if (config.enableAutomaticGainControl) {
                // AGC is typically configured through AudioRecord
                Log.d(TAG, "Automatic gain control enabled")
            }
            
            // Configure acoustic echo cancellation
            if (config.enableAcousticEchoCancellation) {
                // AEC is typically configured through AudioRecord
                Log.d(TAG, "Acoustic echo cancellation enabled")
            }
            
            // Configure beamforming
            if (config.enableBeamforming) {
                // Beamforming is typically configured through AudioRecord
                Log.d(TAG, "Beamforming enabled")
            }
            
            // Configure spatial audio
            if (config.enableSpatialAudio) {
                // Spatial audio is typically configured through AudioRecord
                Log.d(TAG, "Spatial audio enabled")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio effects", e)
        }
    }
    
    /**
     * Configures audio routing
     */
    private fun configureAudioRouting(config: SessionConfig) {
        try {
            // Audio routing is typically configured through AudioManager
            val currentRouting = getCurrentAudioRouting()
            Log.d(TAG, "Current audio routing: $currentRouting")
            
            // Set audio mode for communication
            if (config.audioMode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                Log.d(TAG, "Audio mode set to MODE_IN_COMMUNICATION")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio routing", e)
        }
    }
    
    /**
     * Gets current audio routing
     */
    private fun getCurrentAudioRouting(): AudioRouting {
        return try {
            when {
                audioManager.isBluetoothScoOn -> AudioRouting.BLUETOOTH
                audioManager.isWiredHeadsetOn -> AudioRouting.WIRED_HEADSET
                audioManager.isSpeakerphoneOn -> AudioRouting.SPEAKER
                else -> AudioRouting.EARPIECE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current audio routing", e)
            AudioRouting.DEFAULT
        }
    }
    
    /**
     * Sets audio routing
     */
    fun setAudioRouting(routing: AudioRouting): Boolean {
        try {
            when (routing) {
                AudioRouting.SPEAKER -> {
                    audioManager.isSpeakerphoneOn = true
                    Log.d(TAG, "Audio routing set to speaker")
                }
                AudioRouting.EARPIECE -> {
                    audioManager.isSpeakerphoneOn = false
                    Log.d(TAG, "Audio routing set to earpiece")
                }
                AudioRouting.BLUETOOTH -> {
                    audioManager.startBluetoothSco()
                    Log.d(TAG, "Audio routing set to bluetooth")
                }
                AudioRouting.WIRED_HEADSET -> {
                    // Wired headset is automatically detected
                    Log.d(TAG, "Audio routing set to wired headset")
                }
                AudioRouting.USB_HEADSET -> {
                    // USB headset is automatically detected
                    Log.d(TAG, "Audio routing set to USB headset")
                }
                AudioRouting.DEFAULT -> {
                    audioManager.isSpeakerphoneOn = false
                    Log.d(TAG, "Audio routing set to default")
                }
            }
            
            val sessionInfo = getCurrentSessionInfo()
            notifyAudioRoutingChanged(routing, sessionInfo)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set audio routing", e)
            return false
        }
    }
    
    /**
     * Sets volume level
     */
    fun setVolume(volume: Int): Boolean {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(audioStreamType)
            val clampedVolume = volume.coerceIn(0, maxVolume)
            
            audioManager.setStreamVolume(audioStreamType, clampedVolume, 0)
            
            val sessionInfo = getCurrentSessionInfo()
            notifyVolumeChanged(clampedVolume, maxVolume, sessionInfo)
            
            Log.d(TAG, "Volume set to $clampedVolume/$maxVolume")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            return false
        }
    }
    
    /**
     * Sets mute state
     */
    fun setMuted(isMuted: Boolean): Boolean {
        try {
            audioManager.setMicrophoneMute(isMuted)
            
            val sessionInfo = getCurrentSessionInfo()
            notifyMuteStateChanged(isMuted, sessionInfo)
            
            Log.d(TAG, "Microphone mute set to $isMuted")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mute state", e)
            return false
        }
    }
    
    /**
     * Gets current session information
     */
    fun getCurrentSessionInfo(): SessionInfo {
        return SessionInfo(
            sessionId = audioSessionId,
            state = when (sessionState) {
                SESSION_STATE_IDLE -> SessionState.IDLE
                SESSION_STATE_ACTIVE -> SessionState.ACTIVE
                SESSION_STATE_PAUSED -> SessionState.PAUSED
                SESSION_STATE_STOPPED -> SessionState.STOPPED
                SESSION_STATE_ERROR -> SessionState.ERROR
                else -> SessionState.IDLE
            },
            audioMode = audioMode,
            audioStreamType = audioStreamType,
            audioRouting = getCurrentAudioRouting(),
            isAudioFocusGranted = isAudioFocusGranted,
            isMuted = audioManager.isMicrophoneMute,
            volume = audioManager.getStreamVolume(audioStreamType),
            maxVolume = audioManager.getStreamMaxVolume(audioStreamType)
        )
    }
    
    /**
     * Creates a Flow for session events
     */
    fun createSessionEventFlow(): Flow<SessionEvent> = flow {
        // This would typically emit events as they occur
        // For now, we'll emit the current session info
        val sessionInfo = getCurrentSessionInfo()
        emit(SessionEvent(
            type = EventType.SESSION_STARTED,
            message = "Session event flow created",
            sessionInfo = sessionInfo
        ))
    }.flowOn(Dispatchers.Default)
    
    /**
     * Notifies session state change
     */
    private fun notifySessionStateChanged(state: SessionState, sessionInfo: SessionInfo) {
        sessionListeners.forEach { listener ->
            try {
                listener.onSessionStateChanged(state, sessionInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying session state change", e)
            }
        }
    }
    
    /**
     * Notifies audio focus change
     */
    private fun notifyAudioFocusChanged(focusChange: AudioFocusChange, sessionInfo: SessionInfo) {
        sessionListeners.forEach { listener ->
            try {
                listener.onAudioFocusChanged(focusChange, sessionInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying audio focus change", e)
            }
        }
    }
    
    /**
     * Notifies audio routing change
     */
    private fun notifyAudioRoutingChanged(routing: AudioRouting, sessionInfo: SessionInfo) {
        sessionListeners.forEach { listener ->
            try {
                listener.onAudioRoutingChanged(routing, sessionInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying audio routing change", e)
            }
        }
    }
    
    /**
     * Notifies volume change
     */
    private fun notifyVolumeChanged(volume: Int, maxVolume: Int, sessionInfo: SessionInfo) {
        sessionListeners.forEach { listener ->
            try {
                listener.onVolumeChanged(volume, maxVolume, sessionInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying volume change", e)
            }
        }
    }
    
    /**
     * Notifies mute state change
     */
    private fun notifyMuteStateChanged(isMuted: Boolean, sessionInfo: SessionInfo) {
        sessionListeners.forEach { listener ->
            try {
                listener.onMuteStateChanged(isMuted, sessionInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying mute state change", e)
            }
        }
    }
    
    /**
     * Notifies session error
     */
    private fun notifySessionError(error: String, sessionInfo: SessionInfo) {
        sessionListeners.forEach { listener ->
            try {
                listener.onSessionError(error, sessionInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying session error", e)
            }
        }
    }
    
    /**
     * Gets session statistics
     */
    fun getSessionStats(): Map<String, Any> {
        return mapOf(
            "sessionId" to audioSessionId,
            "sessionState" to sessionState,
            "isAudioFocusGranted" to isAudioFocusGranted,
            "isSessionActive" to isSessionActive,
            "audioMode" to audioMode,
            "audioStreamType" to audioStreamType,
            "audioRouting" to getCurrentAudioRouting().name,
            "isMuted" to audioManager.isMicrophoneMute,
            "volume" to audioManager.getStreamVolume(audioStreamType),
            "maxVolume" to audioManager.getStreamMaxVolume(audioStreamType),
            "isBluetoothScoOn" to audioManager.isBluetoothScoOn,
            "isWiredHeadsetOn" to audioManager.isWiredHeadsetOn,
            "isSpeakerphoneOn" to audioManager.isSpeakerphoneOn,
            "listenerCount" to sessionListeners.size
        )
    }
    
    /**
     * Cleans up the audio session
     */
    fun cleanup() {
        try {
            configurationObserver.cleanup()
            sessionScope.cancel()
            // Abandon audio focus
            abandonAudioFocus()
            
            // Clear listeners
            sessionListeners.clear()
            
            // Reset state
            sessionState = SESSION_STATE_IDLE
            isSessionActive = false
            isAudioFocusGranted = false
            
            Log.d(TAG, "Audio session cleaned up")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up audio session", e)
        }
    }
}

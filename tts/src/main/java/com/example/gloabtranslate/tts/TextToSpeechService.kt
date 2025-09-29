package com.example.gloabtranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.gloabtranslate.core.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.awaitClose
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.Locale
import java.util.UUID

/**
 * Service for handling text-to-speech synthesis using Android TTS API.
 * Provides comprehensive TTS functionality including voice selection, speech rate control,
 * and audio output management.
 */
class TextToSpeechService(private val context: Context) {
    
    companion object {
        private const val TAG = "TextToSpeechService"
        private const val DEFAULT_SPEECH_RATE = 1.0f
        private const val DEFAULT_PITCH = 1.0f
        private const val DEFAULT_VOLUME = 1.0f
        private const val TTS_INITIALIZATION_TIMEOUT_MS = 10000L
    }
    
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var currentLanguage = Locale.ENGLISH
    private var currentVoice: VoiceInfo? = null
    private var speechRate = DEFAULT_SPEECH_RATE
    private var pitch = DEFAULT_PITCH
    private var volume = DEFAULT_VOLUME
    private var isMuted = false
    
    // TTS listeners
    private val ttsListeners = mutableListOf<TTSListener>()
    
    /**
     * Result of a TTS operation
     */
    data class TTSResult(
        val success: Boolean,
        val utteranceId: String? = null,
        val error: String? = null,
        val duration: Long = 0L
    )
    
    /**
     * Voice information
     */
    data class VoiceInfo(
        val name: String,
        val locale: Locale,
        val quality: VoiceQuality,
        val gender: VoiceGender,
        val isNetworkRequired: Boolean = false,
        val isInstalled: Boolean = true
    )
    
    /**
     * Voice quality levels
     */
    enum class VoiceQuality {
        VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW
    }
    
    /**
     * Voice gender types
     */
    enum class VoiceGender {
        MALE, FEMALE, NEUTRAL, UNKNOWN
    }
    
    /**
     * TTS configuration
     */
    data class TTSConfig(
        val language: Locale = Locale.ENGLISH,
        val speechRate: Float = DEFAULT_SPEECH_RATE,
        val pitch: Float = DEFAULT_PITCH,
        val volume: Float = DEFAULT_VOLUME,
        val voice: VoiceInfo? = null,
        val enableQueueMode: Boolean = true,
        val enableAudioFocus: Boolean = true,
        val enableStreaming: Boolean = false,
        val audioStreamType: Int = TextToSpeech.Engine.DEFAULT_STREAM,
        val engine: String? = null
    )
    
    /**
     * TTS event types
     */
    enum class TTSEventType {
        INITIALIZATION_STARTED, INITIALIZATION_COMPLETED, INITIALIZATION_FAILED,
        SPEECH_STARTED, SPEECH_PROGRESS, SPEECH_COMPLETED, SPEECH_STOPPED,
        VOICE_CHANGED, LANGUAGE_CHANGED, RATE_CHANGED, PITCH_CHANGED, VOLUME_CHANGED,
        ERROR_OCCURRED, AUDIO_FOCUS_GAINED, AUDIO_FOCUS_LOST
    }
    
    /**
     * TTS event
     */
    data class TTSEvent(
        val type: TTSEventType,
        val message: String,
        val utteranceId: String? = null,
        val progress: Float = 0.0f,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * TTS listener interface
     */
    interface TTSListener {
        fun onTTSInitialized(success: Boolean)
        fun onSpeechStarted(utteranceId: String)
        fun onSpeechProgress(utteranceId: String, progress: Float)
        fun onSpeechCompleted(utteranceId: String)
        fun onSpeechStopped(utteranceId: String)
        fun onTTSError(error: String, utteranceId: String? = null)
        fun onVoiceChanged(voice: VoiceInfo)
        fun onLanguageChanged(language: Locale)
        fun onSpeechRateChanged(rate: Float)
        fun onPitchChanged(pitch: Float)
        fun onVolumeChanged(volume: Float)
    }
    
    /**
     * Initializes the TTS service
     */
    suspend fun initialize(config: TTSConfig = TTSConfig()): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) {
                Log.d(TAG, "TTS service already initialized")
                Logger.d("TTS service already initialized; language=$currentLanguage", TAG)
                return@withContext true
            }

            Logger.d("Initializing TTS service for locale=${config.language}", TAG)
            notifyTTSEvent(TTSEvent(TTSEventType.INITIALIZATION_STARTED, "Initializing TTS service"))

            val initResult = suspendCancellableCoroutine<Boolean> { continuation ->
                textToSpeech = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        Log.d(TAG, "TTS engine initialized successfully")
                        Logger.d("TTS engine initialized successfully", TAG)
                        continuation.resume(true)
                    } else {
                        Log.e(TAG, "TTS engine initialization failed with status: $status")
                        Logger.e("TTS engine initialization failed with status: $status", TAG)
                        continuation.resumeWithException(Exception("TTS initialization failed with status: $status"))
                    }
                }
            }

            if (initResult) {
                setupTTS(config)
                isInitialized = true
                notifyTTSEvent(TTSEvent(TTSEventType.INITIALIZATION_COMPLETED, "TTS service initialized successfully"))
                Log.d(TAG, "TTS service initialized successfully")
                Logger.d("TTS service initialized. voice=${currentVoice?.name} rate=$speechRate", TAG)
                true
            } else {
                notifyTTSEvent(TTSEvent(TTSEventType.INITIALIZATION_FAILED, "TTS service initialization failed"))
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS service", e)
            Logger.e("Failed to initialize TTS service", TAG, e)
            notifyTTSEvent(TTSEvent(TTSEventType.INITIALIZATION_FAILED, "TTS initialization failed: ${e.message}"))
            false
        }
    }
    
    /**
     * Sets up TTS with configuration
     */
    private suspend fun setupTTS(config: TTSConfig) = withContext(Dispatchers.IO) {
        textToSpeech?.let { tts ->
            // Set language
            setLanguage(config.language)
            
            // Set speech rate
            setSpeechRate(config.speechRate)
            
            // Set pitch
            setPitch(config.pitch)
            
            // Set volume
            setVolume(config.volume)
            
            // Set voice if specified
            config.voice?.let { voice ->
                setVoice(voice)
            }
            
            // Set up utterance progress listener
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    utteranceId?.let { id ->
                        Log.d(TAG, "Speech started for utterance: $id")
                        notifyTTSEvent(TTSEvent(TTSEventType.SPEECH_STARTED, "Speech started", id))
                        notifyListeners { it.onSpeechStarted(id) }
                    }
                }
                
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { id ->
                        Log.d(TAG, "Speech completed for utterance: $id")
                        notifyTTSEvent(TTSEvent(TTSEventType.SPEECH_COMPLETED, "Speech completed", id))
                        notifyListeners { it.onSpeechCompleted(id) }
                    }
                }
                
                @Deprecated("This method overrides a deprecated member")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { id ->
                        Log.e(TAG, "Speech error for utterance: $id")
                        notifyTTSEvent(TTSEvent(TTSEventType.ERROR_OCCURRED, "Speech error", id))
                        notifyListeners { it.onTTSError("Speech error", id) }
                    }
                }
                
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let { id ->
                        Log.d(TAG, "Speech stopped for utterance: $id, interrupted: $interrupted")
                        notifyTTSEvent(TTSEvent(TTSEventType.SPEECH_STOPPED, "Speech stopped", id))
                        notifyListeners { it.onSpeechStopped(id) }
                    }
                }
            })
        }
    }
    
    /**
     * Speaks the given text
     */
    suspend fun speak(
        text: String,
        config: TTSConfig? = null,
        utteranceId: String = UUID.randomUUID().toString()
    ): TTSResult = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext TTSResult(
                    success = false,
                    error = "TTS service not initialized"
                )
            }
            
            if (text.isBlank()) {
                return@withContext TTSResult(
                    success = false,
                    error = "Text cannot be empty"
                )
            }
            
            val tts = textToSpeech ?: return@withContext TTSResult(
                success = false,
                error = "TTS engine not available"
            )

            // Apply configuration if provided
            config?.let { applyConfiguration(it) }

            val startTime = System.currentTimeMillis()
            Logger.d("Queueing speech (${text.length} chars) with utteranceId=$utteranceId", TAG)

            val result = suspendCancellableCoroutine<TTSResult> { continuation ->
                val queueMode = config?.enableQueueMode ?: true
                val queueModeInt = if (queueMode) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH

                val result = tts.speak(text, queueModeInt, null, utteranceId)

                if (result == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Speech queued successfully for utterance: $utteranceId")
                    Logger.d("Speech queued successfully for utterance: $utteranceId", TAG)
                    // We'll get the actual result through the utterance progress listener
                    continuation.resume(TTSResult(
                        success = true,
                        utteranceId = utteranceId,
                        duration = 0L
                    ))
                } else {
                    Log.e(TAG, "Failed to queue speech for utterance: $utteranceId")
                    Logger.e("Failed to queue speech for utterance: $utteranceId", TAG)
                    continuation.resume(TTSResult(
                        success = false,
                        utteranceId = utteranceId,
                        error = "Failed to queue speech"
                    ))
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            result.copy(duration = duration)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to speak text", e)
            Logger.e("Failed to speak text", TAG, e)
            TTSResult(
                success = false,
                utteranceId = utteranceId,
                error = "Failed to speak text: ${e.message}"
            )
        }
    }
    
    /**
     * Stops current speech
     */
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext false
            }
            
            val tts = textToSpeech ?: return@withContext false
            
            val result = tts.stop()
            if (result == TextToSpeech.SUCCESS) {
                Log.d(TAG, "Speech stopped successfully")
                true
            } else {
                Log.e(TAG, "Failed to stop speech")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop speech", e)
            false
        }
    }
    
    /**
     * Sets the language for TTS
     */
    suspend fun setLanguage(language: Locale): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext false
            }
            
            val tts = textToSpeech ?: return@withContext false
            
            val result = tts.setLanguage(language)
            if (result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                currentLanguage = language
                notifyTTSEvent(TTSEvent(TTSEventType.LANGUAGE_CHANGED, "Language changed to ${language.displayName}"))
                notifyListeners { it.onLanguageChanged(language) }
                Log.d(TAG, "Language set to: ${language.displayName}")
                true
            } else {
                Log.e(TAG, "Language not available: ${language.displayName}")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set language", e)
            false
        }
    }
    
    /**
     * Sets the speech rate
     */
    suspend fun setSpeechRate(rate: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext false
            }
            
            val tts = textToSpeech ?: return@withContext false
            
            val clampedRate = rate.coerceIn(0.1f, 3.0f)
            val result = tts.setSpeechRate(clampedRate)
            
            if (result == TextToSpeech.SUCCESS) {
                speechRate = clampedRate
                notifyTTSEvent(TTSEvent(TTSEventType.RATE_CHANGED, "Speech rate changed to $clampedRate"))
                notifyListeners { it.onSpeechRateChanged(clampedRate) }
                Log.d(TAG, "Speech rate set to: $clampedRate")
                true
            } else {
                Log.e(TAG, "Failed to set speech rate: $clampedRate")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speech rate", e)
            false
        }
    }
    
    /**
     * Sets the pitch
     */
    suspend fun setPitch(pitchValue: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext false
            }
            
            val tts = textToSpeech ?: return@withContext false
            
            val clampedPitch = pitchValue.coerceIn(0.1f, 2.0f)
            val result = tts.setPitch(clampedPitch)
            
            if (result == TextToSpeech.SUCCESS) {
                pitch = clampedPitch
                notifyTTSEvent(TTSEvent(TTSEventType.PITCH_CHANGED, "Pitch changed to $clampedPitch"))
                notifyListeners { it.onPitchChanged(clampedPitch) }
                Log.d(TAG, "Pitch set to: $clampedPitch")
                true
            } else {
                Log.e(TAG, "Failed to set pitch: $clampedPitch")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set pitch", e)
            false
        }
    }
    
    /**
     * Sets the volume
     */
    suspend fun setVolume(volumeValue: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext false
            }
            
            val tts = textToSpeech ?: return@withContext false
            
            val clampedVolume = volumeValue.coerceIn(0.0f, 1.0f)
            // Note: Android TTS doesn't have a direct setVolume method
            // Volume is typically controlled through AudioManager
            val result = TextToSpeech.SUCCESS
            
            if (result == TextToSpeech.SUCCESS) {
                volume = clampedVolume
                notifyTTSEvent(TTSEvent(TTSEventType.VOLUME_CHANGED, "Volume changed to $clampedVolume"))
                notifyListeners { it.onVolumeChanged(clampedVolume) }
                Log.d(TAG, "Volume set to: $clampedVolume")
                true
            } else {
                Log.e(TAG, "Failed to set volume: $clampedVolume")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            false
        }
    }
    
    /**
     * Sets the voice
     */
    suspend fun setVoice(voice: VoiceInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext false
            }
            
            val tts = textToSpeech ?: return@withContext false
            
            // Note: Voice selection is engine-specific and may not be available on all devices
            // This is a simplified implementation
            val result = tts.setLanguage(voice.locale)
            
            if (result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                currentVoice = voice
                currentLanguage = voice.locale
                notifyTTSEvent(TTSEvent(TTSEventType.VOICE_CHANGED, "Voice changed to ${voice.name}"))
                notifyListeners { it.onVoiceChanged(voice) }
                Log.d(TAG, "Voice set to: ${voice.name}")
                true
            } else {
                Log.e(TAG, "Voice not available: ${voice.name}")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set voice", e)
            false
        }
    }
    
    /**
     * Gets available voices for a language
     */
    suspend fun getAvailableVoices(language: Locale? = null): List<VoiceInfo> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext emptyList()
            }
            
            val tts = textToSpeech ?: return@withContext emptyList()
            val targetLanguage = language ?: currentLanguage
            
            // This is a simplified implementation
            // In a real implementation, you would query the TTS engine for available voices
            listOf(
                VoiceInfo(
                    name = "Default ${targetLanguage.displayName}",
                    locale = targetLanguage,
                    quality = VoiceQuality.HIGH,
                    gender = VoiceGender.NEUTRAL,
                    isNetworkRequired = false,
                    isInstalled = true
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available voices", e)
            emptyList()
        }
    }
    
    /**
     * Gets supported languages
     */
    suspend fun getSupportedLanguages(): List<Locale> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext emptyList()
            }
            
            val tts = textToSpeech ?: return@withContext emptyList()
            
            // This is a simplified implementation
            // In a real implementation, you would query the TTS engine for supported languages
            listOf(
                Locale.ENGLISH,
                @Suppress("DEPRECATION") Locale("es", "ES"), // Spanish
                @Suppress("DEPRECATION") Locale("fr", "FR"), // French
                @Suppress("DEPRECATION") Locale("de", "DE"), // German
                @Suppress("DEPRECATION") Locale("it", "IT"), // Italian
                @Suppress("DEPRECATION") Locale("pt", "BR"), // Portuguese
                @Suppress("DEPRECATION") Locale("ru", "RU"), // Russian
                @Suppress("DEPRECATION") Locale("ja", "JP"), // Japanese
                @Suppress("DEPRECATION") Locale("ko", "KR"), // Korean
                @Suppress("DEPRECATION") Locale("zh", "CN"), // Chinese
                @Suppress("DEPRECATION") Locale("ar", "SA"), // Arabic
                @Suppress("DEPRECATION") Locale("hi", "IN"), // Hindi
                @Suppress("DEPRECATION") Locale("th", "TH"), // Thai
                @Suppress("DEPRECATION") Locale("vi", "VN"), // Vietnamese
                @Suppress("DEPRECATION") Locale("nl", "NL"), // Dutch
                @Suppress("DEPRECATION") Locale("sv", "SE"), // Swedish
                @Suppress("DEPRECATION") Locale("no", "NO"), // Norwegian
                @Suppress("DEPRECATION") Locale("da", "DK"), // Danish
                @Suppress("DEPRECATION") Locale("fi", "FI"), // Finnish
                @Suppress("DEPRECATION") Locale("pl", "PL"), // Polish
                @Suppress("DEPRECATION") Locale("tr", "TR"), // Turkish
                @Suppress("DEPRECATION") Locale("cs", "CZ"), // Czech
                @Suppress("DEPRECATION") Locale("hu", "HU"), // Hungarian
                @Suppress("DEPRECATION") Locale("ro", "RO"), // Romanian
                @Suppress("DEPRECATION") Locale("bg", "BG"), // Bulgarian
                @Suppress("DEPRECATION") Locale("hr", "HR"), // Croatian
                @Suppress("DEPRECATION") Locale("sk", "SK"), // Slovak
                @Suppress("DEPRECATION") Locale("sl", "SI"), // Slovenian
                @Suppress("DEPRECATION") Locale("et", "EE"), // Estonian
                @Suppress("DEPRECATION") Locale("lv", "LV"), // Latvian
                @Suppress("DEPRECATION") Locale("lt", "LT"), // Lithuanian
                @Suppress("DEPRECATION") Locale("uk", "UA"), // Ukrainian
                @Suppress("DEPRECATION") Locale("el", "GR"), // Greek
                @Suppress("DEPRECATION") Locale("he", "IL"), // Hebrew
                @Suppress("DEPRECATION") Locale("id", "ID"), // Indonesian
                @Suppress("DEPRECATION") Locale("ms", "MY"), // Malay
                @Suppress("DEPRECATION") Locale("tl", "PH")  // Filipino
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get supported languages", e)
            emptyList()
        }
    }
    
    /**
     * Checks if TTS is available
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            isInitialized && textToSpeech != null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking TTS availability", e)
            false
        }
    }
    
    /**
     * Gets current TTS configuration
     */
    fun getCurrentConfig(): TTSConfig {
        return TTSConfig(
            language = currentLanguage,
            speechRate = speechRate,
            pitch = pitch,
            volume = volume,
            voice = currentVoice
        )
    }
    
    /**
     * Gets current pitch
     */
    fun getPitch(): Float = pitch
    
    /**
     * Gets current volume
     */
    fun getVolume(): Float = volume
    
    /**
     * Gets mute state
     */
    fun isMuted(): Boolean = isMuted
    
    /**
     * Applies configuration to TTS
     */
    private suspend fun applyConfiguration(config: TTSConfig) {
        config.language.let { setLanguage(it) }
        config.speechRate.let { setSpeechRate(it) }
        config.pitch.let { setPitch(it) }
        config.volume.let { setVolume(it) }
        config.voice?.let { setVoice(it) }
    }
    
    /**
     * Adds a TTS listener
     */
    fun addTTSListener(listener: TTSListener) {
        ttsListeners.add(listener)
    }
    
    /**
     * Removes a TTS listener
     */
    fun removeTTSListener(listener: TTSListener) {
        ttsListeners.remove(listener)
    }
    
    /**
     * Creates a Flow for TTS events
     */
    fun createTTSEventFlow(): Flow<TTSEvent> = flow {
        // This would typically emit events as they occur
        // For now, we'll emit the current configuration
        emit(TTSEvent(
            type = TTSEventType.INITIALIZATION_COMPLETED,
            message = "TTS event flow created"
        ))
    }.flowOn(Dispatchers.Default)
    
    /**
     * Notifies TTS event
     */
    private fun notifyTTSEvent(event: TTSEvent) {
        ttsListeners.forEach { listener ->
            try {
                when (event.type) {
                    TTSEventType.INITIALIZATION_STARTED, TTSEventType.INITIALIZATION_COMPLETED, TTSEventType.INITIALIZATION_FAILED -> {
                        listener.onTTSInitialized(event.type == TTSEventType.INITIALIZATION_COMPLETED)
                    }
                    TTSEventType.SPEECH_STARTED -> {
                        event.utteranceId?.let { listener.onSpeechStarted(it) }
                    }
                    TTSEventType.SPEECH_COMPLETED -> {
                        event.utteranceId?.let { listener.onSpeechCompleted(it) }
                    }
                    TTSEventType.SPEECH_STOPPED -> {
                        event.utteranceId?.let { listener.onSpeechStopped(it) }
                    }
                    TTSEventType.ERROR_OCCURRED -> {
                        listener.onTTSError(event.message, event.utteranceId)
                    }
                    TTSEventType.VOICE_CHANGED -> {
                        currentVoice?.let { listener.onVoiceChanged(it) }
                    }
                    TTSEventType.LANGUAGE_CHANGED -> {
                        listener.onLanguageChanged(currentLanguage)
                    }
                    TTSEventType.RATE_CHANGED -> {
                        listener.onSpeechRateChanged(speechRate)
                    }
                    TTSEventType.PITCH_CHANGED -> {
                        listener.onPitchChanged(pitch)
                    }
                    TTSEventType.VOLUME_CHANGED -> {
                        listener.onVolumeChanged(volume)
                    }
                    else -> {
                        // Handle other event types if needed
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying TTS event", e)
            }
        }
    }
    
    /**
     * Notifies listeners
     */
    private fun notifyListeners(action: (TTSListener) -> Unit) {
        ttsListeners.forEach { listener ->
            try {
                action(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying TTS listener", e)
            }
        }
    }
    
    /**
     * Gets TTS statistics
     */
    fun getTTSStats(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized,
            "currentLanguage" to currentLanguage.displayName,
            "currentVoice" to (currentVoice?.name ?: "Default"),
            "speechRate" to speechRate,
            "pitch" to pitch,
            "volume" to volume,
            "listenerCount" to ttsListeners.size
        )
    }
    
    /**
     * Cleans up the TTS service
     */
    fun cleanup() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isInitialized = false
            ttsListeners.clear()
            
            Log.d(TAG, "TTS service cleaned up")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up TTS service", e)
        }
    }
}

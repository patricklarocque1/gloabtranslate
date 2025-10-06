package com.example.gloabtranslate.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.gloabtranslate.core.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service for handling text-to-speech synthesis using Android TTS API.
 * Provides comprehensive TTS functionality including voice selection, speech rate control,
 * and audio output management.
 */
class TextToSpeechService @JvmOverloads internal constructor(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ttsEngineProvider: (Context, TextToSpeech.OnInitListener, String?) -> TextToSpeech?
) {

    constructor(context: Context) : this(
        context,
        Dispatchers.IO,
        { ctx, listener, engine -> TextToSpeech(ctx, listener, engine) }
    )

    companion object {
        private const val TAG = "TextToSpeechService"
        private const val DEFAULT_SPEECH_RATE = 1.0f
        private const val DEFAULT_PITCH = 1.0f
        private const val DEFAULT_VOLUME = 1.0f
    }

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var currentLanguage: Locale = Locale.ENGLISH
    private var currentVoice: VoiceInfo? = null
    private var speechRate = DEFAULT_SPEECH_RATE
    private var pitch = DEFAULT_PITCH
    private var volume = DEFAULT_VOLUME

    private val ttsListeners = mutableListOf<TTSListener>()

    data class TTSResult(
        val success: Boolean,
        val utteranceId: String? = null,
        val error: String? = null
    )

    data class VoiceInfo(
        val name: String,
        val locale: Locale,
        val quality: VoiceQuality,
        val gender: VoiceGender,
        val isNetworkRequired: Boolean = false
    )

    enum class VoiceQuality { VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW }
    enum class VoiceGender { MALE, FEMALE, NEUTRAL, UNKNOWN }

    data class TTSConfig(
        val language: Locale = Locale.ENGLISH,
        val speechRate: Float = DEFAULT_SPEECH_RATE,
        val pitch: Float = DEFAULT_PITCH,
        val volume: Float = DEFAULT_VOLUME,
        val voice: VoiceInfo? = null,
        val audioStream: Int = TextToSpeech.Engine.DEFAULT_STREAM,
        val engine: String? = null
    )

    interface TTSListener {
        fun onTTSInitialized(success: Boolean)
        fun onSpeechStarted(utteranceId: String)
        fun onSpeechCompleted(utteranceId: String)
        fun onTTSError(utteranceId: String?, error: String)
    }

    suspend fun initialize(config: TTSConfig = TTSConfig()): Boolean = withContext(dispatcher) {
        if (isInitialized) {
            Logger.d("TTS service already initialized", TAG)
            return@withContext true
        }

        Logger.d("Initializing TTS service for locale=${config.language}", TAG)
        try {
            val initSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
                val listener = TextToSpeech.OnInitListener { status ->
                    if (continuation.isActive) {
                        if (status == TextToSpeech.SUCCESS) {
                            continuation.resume(true)
                        } else {
                            val errorMsg = "TTS engine initialization failed with status: $status"
                            Log.e(TAG, errorMsg)
                            Logger.e(errorMsg, TAG)
                            continuation.resumeWithException(IllegalStateException(errorMsg))
                        }
                    }
                }
                textToSpeech = ttsEngineProvider(context, listener, config.engine)
                if (textToSpeech == null && continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("TTS engine provider returned null"))
                }
            }

            if (initSuccess) {
                isInitialized = true
                setupTTS(config)
                Logger.d("TTS service initialized successfully", TAG)
                ttsListeners.forEach { it.onTTSInitialized(true) }
                true
            } else {
                cleanup()
                ttsListeners.forEach { it.onTTSInitialized(false) }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS service", e)
            Logger.e("Failed to initialize TTS service", TAG, e)
            cleanup()
            ttsListeners.forEach { it.onTTSInitialized(false) }
            false
        }
    }

    private fun setupTTS(config: TTSConfig) {
        textToSpeech?.let {
            it.setOnUtteranceProgressListener(utteranceListener)
            setLanguage(config.language)
            setSpeechRate(config.speechRate)
            setPitch(config.pitch)
            config.voice?.let { voiceInfo -> setVoice(voiceInfo) }
        }
    }

    suspend fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH): TTSResult = withContext(dispatcher) {
        if (!isInitialized) return@withContext TTSResult(false, error = "TTS service not initialized")
        if (text.isBlank()) return@withContext TTSResult(false, error = "Text cannot be empty")
        val tts = textToSpeech ?: return@withContext TTSResult(false, error = "TTS engine not available")

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }

        Logger.d("Queueing speech with utteranceId=$utteranceId", TAG)
        when (val result = tts.speak(text, queueMode, params, utteranceId)) {
            TextToSpeech.SUCCESS -> TTSResult(true, utteranceId)
            else -> {
                val errorMsg = "Failed to speak text, error code: $result"
                Logger.e(errorMsg, TAG)
                TTSResult(false, utteranceId, errorMsg)
            }
        }
    }

    fun stop(): Boolean {
        if (!isInitialized) return false
        return textToSpeech?.stop() == TextToSpeech.SUCCESS
    }

    fun setLanguage(locale: Locale): Boolean {
        if (!isInitialized) return false
        val result = textToSpeech?.setLanguage(locale)
        val isLanguageAvailable = result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        if (isLanguageAvailable) {
            currentLanguage = locale
        }
        return isLanguageAvailable
    }

    fun setSpeechRate(rate: Float): Boolean {
        if (!isInitialized) return false
        val clampedRate = rate.coerceIn(0.1f, 3.0f)
        val result = textToSpeech?.setSpeechRate(clampedRate)
        if (result == TextToSpeech.SUCCESS) {
            speechRate = clampedRate
            return true
        }
        return false
    }

    fun setPitch(pitchValue: Float): Boolean {
        if (!isInitialized) return false
        val clampedPitch = pitchValue.coerceIn(0.1f, 2.0f)
        val result = textToSpeech?.setPitch(clampedPitch)
        if (result == TextToSpeech.SUCCESS) {
            pitch = clampedPitch
            return true
        }
        return false
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0.0f, 1.0f)
    }

    fun setVoice(voiceInfo: VoiceInfo): Boolean {
        if (!isInitialized) return false
        val ttsVoice = textToSpeech?.voices?.firstOrNull { it.name == voiceInfo.name }
        return if (ttsVoice != null) {
            currentVoice = voiceInfo
            textToSpeech?.voice = ttsVoice
            true
        } else {
            false
        }
    }

    fun getAvailableVoices(): List<VoiceInfo> {
        if (!isInitialized) return emptyList()
        return textToSpeech?.voices?.mapNotNull { mapToVoiceInfo(it) } ?: emptyList()
    }

    fun getSupportedLanguages(): List<Locale> {
        if (!isInitialized) return emptyList()
        return textToSpeech?.availableLanguages?.toList() ?: emptyList()
    }

    fun isAvailable(): Boolean = isInitialized && textToSpeech != null

    fun getCurrentConfig(): TTSConfig {
        return TTSConfig(
            language = currentLanguage,
            speechRate = speechRate,
            pitch = pitch,
            volume = volume,
            voice = currentVoice
        )
    }

    fun addTTSListener(listener: TTSListener) {
        if (!ttsListeners.contains(listener)) ttsListeners.add(listener)
    }

    fun removeTTSListener(listener: TTSListener) {
        ttsListeners.remove(listener)
    }

    fun cleanup() {
        Logger.d("Cleaning up TTS service", TAG)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
        ttsListeners.clear()
    }
    
    private fun mapToVoiceInfo(voice: Voice): VoiceInfo? {
        return VoiceInfo(
            name = voice.name,
            locale = voice.locale,
            quality = VoiceQuality.values().getOrElse(voice.quality - 1) { VoiceQuality.MEDIUM },
            gender = when (voice.features.firstOrNull { it.startsWith("gender=") }?.substringAfter('=')) {
                "male" -> VoiceGender.MALE
                "female" -> VoiceGender.FEMALE
                else -> VoiceGender.UNKNOWN
            },
            isNetworkRequired = voice.isNetworkConnectionRequired
        )
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            Logger.d("Speech started for utterance: $utteranceId", TAG)
            ttsListeners.forEach { it.onSpeechStarted(utteranceId) }
        }

        override fun onDone(utteranceId: String) {
            Logger.d("Speech completed for utterance: $utteranceId", TAG)
            ttsListeners.forEach { it.onSpeechCompleted(utteranceId) }
        }

        override fun onError(utteranceId: String) {
            val errorMsg = "Speech error for utterance: $utteranceId"
            Logger.e(errorMsg, TAG)
            ttsListeners.forEach { it.onTTSError(utteranceId, errorMsg) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            val errorMsg = "Speech error for utterance: $utteranceId, code: $errorCode"
            Logger.e(errorMsg, TAG)
            ttsListeners.forEach { it.onTTSError(utteranceId, errorMsg) }
        }
    }

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
}

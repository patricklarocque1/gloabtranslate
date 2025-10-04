package com.example.gloabtranslate.tts.shadows

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom Shadow for TextToSpeech to handle SDK 34+ compatibility and provide
 * comprehensive mocking capabilities for TTS functionality.
 */
@Implements(TextToSpeech::class)
class ShadowTextToSpeech {
    
    @RealObject
    private lateinit var realTextToSpeech: TextToSpeech
    
    private var initStatus = TextToSpeech.SUCCESS
    private var language: Locale = Locale.ENGLISH
    private var speechRate = 1.0f
    private var pitch = 1.0f
    private var utteranceProgressListener: UtteranceProgressListener? = null
    private val spokenText = mutableListOf<String>()
    private val utteranceParams = ConcurrentHashMap<String, Bundle>()
    
    companion object {
        private var globalInitStatus = TextToSpeech.SUCCESS
        private val allInstances = mutableListOf<ShadowTextToSpeech>()
        
        /**
         * Set the global initialization status for all TextToSpeech instances
         */
        fun setGlobalInitStatus(status: Int) {
            globalInitStatus = status
        }
        
        /**
         * Get all spoken text from all instances
         */
        fun getAllSpokenText(): List<String> {
            return allInstances.flatMap { it.spokenText }
        }
        
        @Resetter
        fun reset() {
            globalInitStatus = TextToSpeech.SUCCESS
            allInstances.clear()
        }
    }
    
    fun __constructor__(context: Context, listener: TextToSpeech.OnInitListener) {
        allInstances.add(this)
        initStatus = globalInitStatus
        
        // Simulate async initialization
        listener.onInit(initStatus)
    }
    
    @Implementation
    fun setLanguage(locale: Locale): Int {
        language = locale
        return when {
            locale == Locale.ENGLISH -> TextToSpeech.LANG_AVAILABLE
            locale.language == "en" -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }
    
    @Implementation
    fun setSpeechRate(speechRate: Float): Int {
        this.speechRate = speechRate.coerceIn(0.1f, 3.0f)
        return TextToSpeech.SUCCESS
    }
    
    @Implementation
    fun setPitch(pitch: Float): Int {
        this.pitch = pitch.coerceIn(0.1f, 2.0f)
        return TextToSpeech.SUCCESS
    }
    
    @Implementation
    fun speak(text: CharSequence?, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
        if (text.isNullOrBlank()) {
            return TextToSpeech.ERROR
        }
        
        spokenText.add(text.toString())
        utteranceId?.let { id ->
            params?.let { utteranceParams[id] = it }
        }
        
        // Simulate successful speech
        utteranceProgressListener?.let { listener ->
            utteranceId?.let { id ->
                listener.onStart(id)
                listener.onDone(id)
            }
        }
        
        return TextToSpeech.SUCCESS
    }
    
    @Implementation
    fun stop(): Int {
        return TextToSpeech.SUCCESS
    }
    
    @Implementation
    fun shutdown() {
        // Cleanup
        allInstances.remove(this)
    }
    
    @Implementation
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?) {
        utteranceProgressListener = listener
    }
    
    @Implementation
    fun isLanguageAvailable(locale: Locale): Int {
        return when {
            locale == Locale.ENGLISH -> TextToSpeech.LANG_AVAILABLE
            locale.language == "en" -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }
    
    @Implementation
    fun getLanguage(): Locale {
        return language
    }
    
    @Implementation
    fun getVoices(): Set<android.speech.tts.Voice>? {
        // Return empty set for testing
        return emptySet()
    }
    
    @Implementation
    fun getAvailableLanguages(): Set<Locale>? {
        return setOf(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN, Locale("es", "ES"))
    }
    
    /**
     * Handle SDK 34+ specific methods that might be called internally
     */
    @Implementation
    protected fun addDeviceSpecificSessionIdToParams(context: Context, params: Bundle) {
        // No-op implementation for SDK 34+ compatibility
    }
    
    // Getters for testing
    fun getSpokenText(): List<String> = spokenText.toList()
    fun getCurrentLanguage(): Locale = language
    fun getCurrentSpeechRate(): Float = speechRate
    fun getCurrentPitch(): Float = pitch
    fun getUtteranceParams(): Map<String, Bundle> = utteranceParams.toMap()
}
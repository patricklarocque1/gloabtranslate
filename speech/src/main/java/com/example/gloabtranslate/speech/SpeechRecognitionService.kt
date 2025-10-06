package com.example.gloabtranslate.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.gloabtranslate.core.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service for handling speech-to-text recognition using Android Speech API.
 * Provides both one-time recognition and continuous recognition capabilities.
 */
class SpeechRecognitionService(
    private val context: Context,
    private val platform: SpeechPlatform = AndroidSpeechPlatform(context)
) {
    
    companion object {
        private const val TAG = "SpeechRecognitionService"
        private const val RECOGNITION_TIMEOUT_MS = 30000L // 30 seconds
        private const val CONTINUOUS_RECOGNITION_TIMEOUT_MS = 60000L // 60 seconds for continuous
    }
    
    private var speechRecognizer: SpeechRecognizerAdapter? = null
    private val serviceStatus = AtomicReference(ServiceStatus.UNINITIALIZED)
    
    /**
     * Result of a speech recognition operation
     */
    data class SpeechRecognitionResult(
        val success: Boolean,
        val text: String? = null,
        val confidence: Float? = null,
        val isPartial: Boolean = false,
        val error: String? = null
    )
    
    /**
     * Configuration for speech recognition
     */
    data class RecognitionConfig(
        val languageCode: String = "en-US",
        val enablePartialResults: Boolean = true,
        val enableOnDeviceRecognition: Boolean = true,
        val timeoutMs: Long = RECOGNITION_TIMEOUT_MS
    )
    
    /**
     * Health status of the service
     */
    enum class ServiceStatus {
        UNINITIALIZED,
        INITIALIZING,
        AVAILABLE,
        UNAVAILABLE,
        ERROR
    }
    
    /**
     * Initializes the speech recognition service
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        serviceStatus.set(ServiceStatus.INITIALIZING)
        try {
            if (!platform.hasRecordAudioPermission()) {
                Log.w(TAG, "RECORD_AUDIO permission not granted")
                Logger.w("RECORD_AUDIO permission not granted", TAG)
                serviceStatus.set(ServiceStatus.ERROR)
                return@withContext false
            }

            if (!platform.isRecognitionAvailable()) {
                Log.w(TAG, "Speech recognition not available on this device")
                Logger.w("Speech recognition not available on this device", TAG)
                serviceStatus.set(ServiceStatus.UNAVAILABLE)
                return@withContext false
            }

            speechRecognizer = platform.createSpeechRecognizer()

            Log.d(TAG, "Speech recognition service initialized successfully")
            Logger.d("Speech recognition service initialized successfully", TAG)
            serviceStatus.set(ServiceStatus.AVAILABLE)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech recognition service", e)
            Logger.e("Failed to initialize speech recognition service", TAG, e)
            serviceStatus.set(ServiceStatus.ERROR)
            false
        }
    }
    
    /**
     * Gets the current health status of the service
     */
    fun getStatus(): ServiceStatus = serviceStatus.get()
    
    /**
     * Performs one-time speech recognition
     */
    suspend fun recognizeSpeech(config: RecognitionConfig = RecognitionConfig()): SpeechRecognitionResult = 
        withContext(Dispatchers.IO) {
            try {
                val recognizer = speechRecognizer
                if (recognizer == null || serviceStatus.get() != ServiceStatus.AVAILABLE) {
                    return@withContext SpeechRecognitionResult(
                        success = false,
                        error = "Speech recognition not initialized or unavailable"
                    )
                }
                
                if (!platform.hasRecordAudioPermission()) {
                    return@withContext SpeechRecognitionResult(
                        success = false,
                        error = "RECORD_AUDIO permission not granted"
                    )
                }
                
                val result = withTimeoutOrNull(config.timeoutMs) {
                    performRecognition(recognizer, config)
                }
                
                if (result != null) {
                    SpeechRecognitionResult(
                        success = true,
                        text = result.text,
                        confidence = result.confidence,
                        isPartial = false
                    )
                } else {
                    SpeechRecognitionResult(
                        success = false,
                        error = "Speech recognition timed out"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speech recognition failed", e)
                Logger.e("Speech recognition failed", TAG, e)
                SpeechRecognitionResult(
                    success = false,
                    error = "Speech recognition failed: ${e.message}"
                )
            }
        }
    
    /**
     * Starts continuous speech recognition and returns a Flow of results
     */
    fun startContinuousRecognition(
        config: RecognitionConfig = RecognitionConfig()
    ): Flow<SpeechRecognitionResult> = callbackFlow {
        try {
            val recognizer = speechRecognizer
            if (recognizer == null || serviceStatus.get() != ServiceStatus.AVAILABLE) {
                trySend(SpeechRecognitionResult(
                    success = false,
                    error = "Speech recognition not initialized or unavailable"
                ))
                close()
                return@callbackFlow
            }
            
            if (!platform.hasRecordAudioPermission()) {
                trySend(SpeechRecognitionResult(
                    success = false,
                    error = "RECORD_AUDIO permission not granted"
                ))
                close()
                return@callbackFlow
            }
            
            val resultListener = object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    Log.d(TAG, "Ready for continuous speech")
                }
                
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Beginning of continuous speech")
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // Handle volume changes if needed
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {
                    // Handle buffer if needed
                }
                
                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of continuous speech")
                }
                
                override fun onError(error: Int) {
                    Log.e(TAG, "Continuous recognition error: $error")
                    trySend(SpeechRecognitionResult(
                        success = false,
                        error = "Continuous recognition error: $error"
                    ))
                    close()
                }
                
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        val conf = if (confidence != null && confidence.isNotEmpty()) confidence[0] else 0.0f
                        
                        trySend(SpeechRecognitionResult(
                            success = true,
                            text = text,
                            confidence = conf,
                            isPartial = false
                        ))
                    }
                }
                
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        trySend(SpeechRecognitionResult(
                            success = true,
                            text = matches[0],
                            confidence = 0.0f,
                            isPartial = true
                        ))
                    }
                }
                
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                    // Handle events if needed
                }
            }
            
            recognizer.setRecognitionListener(resultListener)
            recognizer.startListening(config)
            
            // Auto-stop after timeout
            withTimeoutOrNull(config.timeoutMs) {
                // Keep the flow alive
            }
            
            recognizer.stopListening()
            close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Continuous recognition failed", e)
            trySend(SpeechRecognitionResult(
                success = false,
                error = "Continuous recognition failed: ${e.message}"
            ))
            close()
        }
        
        awaitClose {
            speechRecognizer?.stopListening()
        }
    }
    
    /**
     * Checks if speech recognition is available
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!platform.hasRecordAudioPermission()) {
                return@withContext false
            }

            platform.isRecognitionAvailable()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking speech recognition availability", e)
            false
        }
    }
    
    /**
     * Gets the list of supported languages
     */
    suspend fun getSupportedLanguages(): List<String> = withContext(Dispatchers.IO) {
        try {
            // ML Kit Speech Recognition supports a wide range of languages
            // This is a subset of commonly supported languages
            listOf(
                "en-US", "en-GB", "en-AU", "en-CA", "en-IN",
                "es-ES", "es-MX", "es-AR", "es-CO", "es-PE",
                "fr-FR", "fr-CA", "fr-CH",
                "de-DE", "de-AT", "de-CH",
                "it-IT", "it-CH",
                "pt-BR", "pt-PT",
                "ru-RU",
                "ja-JP",
                "ko-KR",
                "zh-CN", "zh-TW", "zh-HK",
                "ar-SA", "ar-EG",
                "hi-IN",
                "th-TH",
                "vi-VN",
                "nl-NL",
                "sv-SE",
                "no-NO",
                "da-DK",
                "fi-FI",
                "pl-PL",
                "tr-TR",
                "cs-CZ",
                "hu-HU",
                "ro-RO",
                "bg-BG",
                "hr-HR",
                "sk-SK",
                "sl-SI",
                "et-EE",
                "lv-LV",
                "lt-LT",
                "uk-UA",
                "el-GR",
                "he-IL",
                "id-ID",
                "ms-MY",
                "tl-PH"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting supported languages", e)
            emptyList()
        }
    }
    
    /**
     * Checks if the device has RECORD_AUDIO permission
     */
    /**
     * Performs the actual speech recognition
     */
    private suspend fun performRecognition(
        recognizer: SpeechRecognizerAdapter,
        config: RecognitionConfig
    ): SpeechRecognitionResult = suspendCancellableCoroutine { continuation ->
        
        val resultListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.d(TAG, "Ready for speech")
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // Handle volume changes if needed
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // Handle buffer if needed
            }
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
            }
            
            override fun onError(error: Int) {
                Log.e(TAG, "Speech recognition error: $error")
                continuation.resumeWithException(Exception("Speech recognition error: $error"))
            }
            
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    val conf = if (confidence != null && confidence.isNotEmpty()) confidence[0] else 0.0f
                    
                    Log.d(TAG, "Speech recognition result: $text")
                    continuation.resume(SpeechRecognitionResult(
                        success = true,
                        text = text,
                        confidence = conf,
                        isPartial = false
                    ))
                } else {
                    continuation.resumeWithException(Exception("No speech recognition results"))
                }
            }
            
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "Partial result: ${matches[0]}")
                }
            }
            
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                // Handle events if needed
            }
        }
        
        recognizer.setRecognitionListener(resultListener)
        recognizer.startListening(config)
        
        // Stop listening after a short duration for one-time recognition
        continuation.invokeOnCancellation {
            recognizer.stopListening()
        }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        serviceStatus.set(ServiceStatus.UNINITIALIZED)
    }
}

interface SpeechPlatform {
    fun hasRecordAudioPermission(): Boolean
    fun isRecognitionAvailable(): Boolean
    fun createSpeechRecognizer(): SpeechRecognizerAdapter
}

interface SpeechRecognizerAdapter {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(config: SpeechRecognitionService.RecognitionConfig)
    fun stopListening()
    fun destroy()
}

internal class AndroidSpeechPlatform(private val context: Context) : SpeechPlatform {
    override fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun createSpeechRecognizer(): SpeechRecognizerAdapter {
        return AndroidSpeechRecognizerAdapter(
            SpeechRecognizer.createSpeechRecognizer(context)
        )
    }
}

private class AndroidSpeechRecognizerAdapter(
    private val delegate: SpeechRecognizer
) : SpeechRecognizerAdapter {
    override fun setRecognitionListener(listener: RecognitionListener) {
        delegate.setRecognitionListener(listener)
    }

    override fun startListening(config: SpeechRecognitionService.RecognitionConfig) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).also {
            val extras = android.os.Bundle()
            extras.putString(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            extras.putString(RecognizerIntent.EXTRA_LANGUAGE, config.languageCode)
            extras.putBoolean(RecognizerIntent.EXTRA_PARTIAL_RESULTS, config.enablePartialResults)
            extras.putInt(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            it.putExtras(extras)
        }
        delegate.startListening(intent)
    }

    override fun stopListening() {
        delegate.stopListening()
    }

    override fun destroy() {
        delegate.destroy()
    }
}

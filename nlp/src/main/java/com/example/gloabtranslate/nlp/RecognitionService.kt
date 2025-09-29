package com.example.gloabtranslate.nlp

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.utils.Logger
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service for handling speech recognition and translation with graceful fallback.
 * Automatically switches between on-device and cloud-based recognition based on availability.
 */
class RecognitionService(private val context: Context) {
    
    companion object {
        private const val TAG = "RecognitionService"
        private const val RECOGNITION_TIMEOUT_MS = 10000L // 10 seconds
    }
    
    private val availabilityManager = RecognizerAvailabilityManager(context)
    private val modelManager by lazy { ModelManager.getInstance(context) }
    private var languageIdentifier: LanguageIdentifier? = null
    private val activeTranslators = mutableMapOf<String, Translator>()
    
    /**
     * Result of a recognition operation
     */
    data class RecognitionResult(
        val success: Boolean,
        val text: String? = null,
        val sourceLanguage: String? = null,
        val translatedText: String? = null,
        val targetLanguage: String? = null,
        val isOnDevice: Boolean = false,
        val confidence: Float? = null,
        val isPartial: Boolean = false,
        val error: String? = null
    )
    
    /**
     * Initializes the recognition service and checks availability
     */
    suspend fun initialize(): RecognitionResult = withContext(Dispatchers.IO) {
        try {
            Logger.d("Initializing recognition service", TAG)
            val capability = availabilityManager.determineRecognitionCapability()

            when (capability) {
                RecognizerAvailabilityManager.RecognitionCapability.UNAVAILABLE -> {
                    RecognitionResult(
                        success = false,
                        error = "Recognition not available: ${availabilityManager.getCapabilityMessage()}"
                    )
                }
                RecognizerAvailabilityManager.RecognitionCapability.CLOUD_ONLY,
                RecognizerAvailabilityManager.RecognitionCapability.ON_DEVICE_AVAILABLE -> {
                    initializeLanguageIdentifier()
                    Logger.d(
                        "Recognition service ready. On-device available=${availabilityManager.isOnDeviceRecognitionAvailable()}",
                        TAG
                    )

                    RecognitionResult(
                        success = true,
                        text = "Recognition service initialized",
                        isOnDevice = availabilityManager.isOnDeviceRecognitionAvailable()
                    )
                }
                RecognizerAvailabilityManager.RecognitionCapability.CHECKING -> {
                    RecognitionResult(
                        success = false,
                        error = "Still checking recognition availability..."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognition service", e)
            Logger.e("Failed to initialize recognition service", TAG, e)
            RecognitionResult(
                success = false,
                error = "Initialization failed: ${e.message}"
            )
        }
    }
    
    /**
     * Identifies the language of the given text
     */
    suspend fun identifyLanguage(text: String): RecognitionResult = withContext(Dispatchers.IO) {
        try {
            if (languageIdentifier == null) {
                initializeLanguageIdentifier()
            }
            Logger.d("Identifying language for snippet='${text.take(40)}'", TAG)

            val identifier = languageIdentifier ?: return@withContext RecognitionResult(
                success = false,
                error = "Language identifier not available"
            )
            
            // Use real ML Kit language identification with callback-based approach
            val identifiedLanguage = withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
                identifyLanguageWithCallback(identifier, text)
            }
            
            if (identifiedLanguage != null) {
                RecognitionResult(
                    success = true,
                    text = text,
                    sourceLanguage = identifiedLanguage,
                    isOnDevice = availabilityManager.isOnDeviceRecognitionAvailable()
                )
            } else {
                RecognitionResult(
                    success = false,
                    error = "Language identification timed out"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Language identification failed", e)
            Logger.e("Language identification failed", TAG, e)
            RecognitionResult(
                success = false,
                error = "Language identification failed: ${e.message}"
            )
        }
    }
    
    /**
     * Translates text from source language to target language
     */
    suspend fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): RecognitionResult = withContext(Dispatchers.IO) {
        try {
            val translatorKey = "${sourceLanguage}_$targetLanguage"
            var translator = activeTranslators[translatorKey]

            if (translator == null) {
                modelManager.ensureLanguagePairAvailable(sourceLanguage, targetLanguage)
                translator = createTranslator(sourceLanguage, targetLanguage)
                activeTranslators[translatorKey] = translator
            }
            Logger.d(
                "Translating ${text.length} chars from $sourceLanguage to $targetLanguage",
                TAG
            )

            // Use real ML Kit translation with callback-based approach
            val translatedText = withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
                translateTextWithCallback(translator, text)
            }
            
            if (translatedText != null) {
                RecognitionResult(
                    success = true,
                    text = text,
                    sourceLanguage = sourceLanguage,
                    translatedText = translatedText,
                    targetLanguage = targetLanguage,
                    isOnDevice = availabilityManager.isOnDeviceRecognitionAvailable()
                )
            } else {
                RecognitionResult(
                    success = false,
                    error = "Translation timed out"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            Logger.e("Translation failed", TAG, e)
            RecognitionResult(
                success = false,
                error = "Translation failed: ${e.message}"
            )
        }
    }
    
    /**
     * Gets the current recognition capability status
     */
    fun getCapabilityStatus(): String {
        return availabilityManager.getCapabilityMessage()
    }
    
    /**
     * Gets the recommended action based on current capability
     */
    fun getRecommendedAction(): String {
        return availabilityManager.getRecommendedAction()
    }
    
    /**
     * Checks if on-device recognition is available
     */
    fun isOnDeviceAvailable(): Boolean {
        return availabilityManager.isOnDeviceRecognitionAvailable()
    }
    
    /**
     * Checks if cloud-based recognition is available
     */
    fun isCloudAvailable(): Boolean {
        return availabilityManager.isCloudRecognitionAvailable()
    }
    
    private fun initializeLanguageIdentifier() {
        try {
            val options = LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.5f)
                .build()
            
            languageIdentifier = LanguageIdentification.getClient(options)
            Log.d(TAG, "Language identifier initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize language identifier", e)
        }
    }
    
    private fun createTranslator(sourceLanguage: String, targetLanguage: String): Translator {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        
        return Translation.getClient(options)
    }
    
    /**
     * Helper function to handle ML Kit language identification callback
     */
    private suspend fun identifyLanguageWithCallback(
        identifier: LanguageIdentifier,
        text: String
    ): String = suspendCancellableCoroutine { continuation ->
        identifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                Log.d(TAG, "Language identified: $languageCode")
                continuation.resume(languageCode)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Language identification failed", exception)
                continuation.resumeWithException(exception)
            }
    }
    
    /**
     * Helper function to handle ML Kit translation callback
     */
    private suspend fun translateTextWithCallback(
        translator: Translator,
        text: String
    ): String = suspendCancellableCoroutine { continuation ->
        translator.translate(text)
            .addOnSuccessListener { translatedText ->
                Log.d(TAG, "Translation successful: $text -> $translatedText")
                continuation.resume(translatedText)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Translation failed", exception)
                continuation.resumeWithException(exception)
            }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        // LanguageIdentifier doesn't need explicit cleanup
        activeTranslators.values.forEach { it.close() }
        activeTranslators.clear()
    }
}

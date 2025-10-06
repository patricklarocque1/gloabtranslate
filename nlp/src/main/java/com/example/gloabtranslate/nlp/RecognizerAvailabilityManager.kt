package com.example.gloabtranslate.nlp

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages recognizer availability and provides graceful fallback mechanisms.
 * Handles on-device vs cloud-based recognition based on Google component availability.
 */
class RecognizerAvailabilityManager(
    private val context: Context,
    private val modelManager: ModelManager
) {
    
    companion object {
        private const val TAG = "RecognizerAvailability"
        private const val DOWNLOAD_TIMEOUT_MS = 30000L // 30 seconds
    }
    
    private val googleApiAvailability = GoogleApiAvailability.getInstance()
    // ModelManager is now injected via constructor
    
    /**
     * Represents the current recognition capability status
     */
    enum class RecognitionCapability {
        ON_DEVICE_AVAILABLE,    // Google components available, on-device models ready
        CLOUD_ONLY,             // Google components available, but on-device models not ready
        UNAVAILABLE,            // Google components not available
        CHECKING               // Currently checking availability
    }
    
    private var currentCapability = RecognitionCapability.CHECKING
    
    /**
     * Checks if Google Play Services are available and properly configured
     */
    suspend fun checkGooglePlayServicesAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
            when (resultCode) {
                ConnectionResult.SUCCESS -> {
                    Log.d(TAG, "Google Play Services available")
                    true
                }
                ConnectionResult.SERVICE_MISSING,
                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
                ConnectionResult.SERVICE_DISABLED -> {
                    Log.w(TAG, "Google Play Services not available: $resultCode")
                    false
                }
                else -> {
                    Log.w(TAG, "Google Play Services error: $resultCode")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Google Play Services", e)
            false
        }
    }
    
    /**
     * Checks if on-device language identification is available
     */
    suspend fun checkOnDeviceLanguageIdAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            val languageIdentifier = LanguageIdentification.getClient()
            
            // Test actual functionality with a simple language identification
            val testResult = withTimeoutOrNull(5000L) {
                testLanguageIdentification(languageIdentifier)
            }
            
            if (testResult == true) {
                Log.d(TAG, "ML Kit Language Identification client tested successfully")
                true
            } else {
                Log.w(TAG, "ML Kit Language Identification test failed or timed out")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "On-device language ID not available", e)
            false
        }
    }
    
    /**
     * Checks if on-device translation is available for given language pair
     */
    suspend fun checkOnDeviceTranslationAvailability(
        sourceLanguage: String,
        targetLanguage: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking translation availability for $sourceLanguage -> $targetLanguage")
            
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
            
            modelManager.ensureLanguagePairAvailable(sourceLanguage, targetLanguage)

            val translator = Translation.getClient(options)

            val testResult = try {
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    testTranslation(translator)
                }
            } finally {
                translator.close()
            }

            if (testResult == true) {
                Log.d(TAG, "ML Kit Translation client tested successfully for $sourceLanguage -> $targetLanguage")
                true
            } else {
                Log.w(TAG, "ML Kit Translation test failed or timed out for $sourceLanguage -> $targetLanguage")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "On-device translation check failed for $sourceLanguage -> $targetLanguage", e)
            false
        }
    }
    
    /**
     * Determines the best available recognition capability
     */
    suspend fun determineRecognitionCapability(): RecognitionCapability = withContext(Dispatchers.IO) {
        currentCapability = RecognitionCapability.CHECKING
        
        // First check if Google Play Services are available
        val playServicesAvailable = checkGooglePlayServicesAvailability()
        if (!playServicesAvailable) {
            currentCapability = RecognitionCapability.UNAVAILABLE
            return@withContext currentCapability
        }
        
        // Check if on-device language identification is available
        val languageIdAvailable = checkOnDeviceLanguageIdAvailability()
        if (!languageIdAvailable) {
            currentCapability = RecognitionCapability.CLOUD_ONLY
            return@withContext currentCapability
        }
        
        // Check if on-device translation is available for common language pairs
        val commonLanguagePairs = listOf(
            "en" to "es", // English to Spanish
            "es" to "en", // Spanish to English
            "en" to "fr", // English to French
            "fr" to "en"  // French to English
        )
        
        val translationAvailable = commonLanguagePairs.any { (source, target) ->
            checkOnDeviceTranslationAvailability(source, target)
        }
        
        currentCapability = if (translationAvailable) {
            RecognitionCapability.ON_DEVICE_AVAILABLE
        } else {
            RecognitionCapability.CLOUD_ONLY
        }
        
        Log.d(TAG, "Recognition capability determined: $currentCapability (translation: $translationAvailable)")
        currentCapability
    }
    
    /**
     * Gets the current recognition capability
     */
    fun getCurrentCapability(): RecognitionCapability = currentCapability
    
    /**
     * Checks if on-device recognition is currently available
     */
    fun isOnDeviceRecognitionAvailable(): Boolean {
        return currentCapability == RecognitionCapability.ON_DEVICE_AVAILABLE
    }
    
    /**
     * Checks if cloud-based recognition is available as fallback
     */
    fun isCloudRecognitionAvailable(): Boolean {
        return currentCapability == RecognitionCapability.CLOUD_ONLY || 
               currentCapability == RecognitionCapability.ON_DEVICE_AVAILABLE
    }
    
    /**
     * Gets a user-friendly message about the current recognition capability
     */
    fun getCapabilityMessage(): String {
        return when (currentCapability) {
            RecognitionCapability.ON_DEVICE_AVAILABLE -> 
                "On-device translation available - works offline"
            RecognitionCapability.CLOUD_ONLY -> 
                "Cloud-based translation available - requires internet connection"
            RecognitionCapability.UNAVAILABLE -> 
                "Translation not available - Google Play Services required"
            RecognitionCapability.CHECKING -> 
                "Checking translation availability..."
        }
    }
    
    /**
     * Gets recommended action based on current capability
     */
    fun getRecommendedAction(): String {
        return when (currentCapability) {
            RecognitionCapability.ON_DEVICE_AVAILABLE -> 
                "You can use translation offline"
            RecognitionCapability.CLOUD_ONLY -> 
                "Ensure you have an internet connection for translation"
            RecognitionCapability.UNAVAILABLE -> 
                "Please install or update Google Play Services"
            RecognitionCapability.CHECKING -> 
                "Please wait while we check availability..."
        }
    }
    
    /**
     * Helper function to test language identification functionality
     */
    private suspend fun testLanguageIdentification(
        identifier: LanguageIdentifier
    ): Boolean = suspendCancellableCoroutine { continuation ->
        identifier.identifyLanguage("Hello")
            .addOnSuccessListener { languageCode ->
                Log.d(TAG, "Language identification test successful: $languageCode")
                continuation.resume(true)
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Language identification test failed", exception)
                continuation.resume(false)
            }
    }
    
    /**
     * Helper function to test translation functionality
     */
    private suspend fun testTranslation(translator: Translator): Boolean = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "Starting translation test")

        translator.translate("Hello")
            .addOnSuccessListener { translatedText ->
                Log.d(TAG, "Translation test successful: Hello -> $translatedText")
                continuation.resume(true)
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Translation test failed", exception)
                if (exception is MlKitException) {
                    Log.w(TAG, "ML Kit exception details: ${exception.message}")
                }
                continuation.resume(false)
            }
    }
}

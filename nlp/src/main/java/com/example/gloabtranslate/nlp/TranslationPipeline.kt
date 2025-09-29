package com.example.gloabtranslate.nlp

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.data.models.TranslationResult
import com.example.gloabtranslate.core.data.models.LanguagePair
import com.example.gloabtranslate.core.data.models.SupportedLanguage
import com.example.gloabtranslate.core.data.config.PerformanceConfig
import com.example.gloabtranslate.core.data.config.TranslationConfig
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import com.example.gloabtranslate.core.data.repository.TranslationHistoryRepository
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Continuous translation pipeline for real-time speech translation.
 * Handles language detection, translation, caching, and batch processing.
 */
class TranslationPipeline(private val context: Context) {
    
    companion object {
        private const val TAG = "TranslationPipeline"
        private const val DEFAULT_TIMEOUT_MS = 10000L
        private const val BATCH_SIZE = 5
        private const val BATCH_TIMEOUT_MS = 2000L
        private const val CACHE_SIZE = 100
        private const val MIN_CONFIDENCE_THRESHOLD = 0.5f
    }
    
    // Core components
    private val modelManager by lazy { ModelManager.getInstance(context) }
    private var languageIdentifier: LanguageIdentifier? = null
    private val activeTranslators = ConcurrentHashMap<String, Translator>()
    
    // Pipeline state
    private val isInitialized = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private val translationCounter = AtomicLong(0)
    
    // Batch processing
    private val batchChannel = Channel<TranslationRequest>(Channel.UNLIMITED)
    private val batchJob = SupervisorJob()
    private val batchScope = CoroutineScope(Dispatchers.IO + batchJob)
    
    // Caching
    private val translationCache = ConcurrentHashMap<String, TranslationResult>()
    private val languageDetectionCache = ConcurrentHashMap<String, String>()
    
    // Configuration
    private var defaultSourceLanguage = "auto"
    private var defaultTargetLanguage = "en"
    private var enableCaching = true
    private var enableBatchProcessing = true
    private val configurationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var configurationJob: Job? = null
    private var confidenceThreshold = MIN_CONFIDENCE_THRESHOLD
    private val configurationManager = ConfigurationManager.getInstance(context)
    private val historyRepository = TranslationHistoryRepository.getInstance(context)
    private var historyEnabled = true

    
    /**
     * Translation request for batch processing
     */
    private data class TranslationRequest(
        val text: String,
        val sourceLanguage: String? = null,
        val targetLanguage: String,
        val isPartial: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
        val callback: suspend (TranslationResult) -> Unit
    )
    
    /**
     * Pipeline configuration
     */
    data class PipelineConfig(
        val defaultSourceLanguage: String = "auto",
        val defaultTargetLanguage: String = "en",
        val enableCaching: Boolean = true,
        val enableBatchProcessing: Boolean = true,
        val confidenceThreshold: Float = MIN_CONFIDENCE_THRESHOLD,
        val batchSize: Int = BATCH_SIZE,
        val batchTimeoutMs: Long = BATCH_TIMEOUT_MS,
        val cacheSize: Int = CACHE_SIZE,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS
    )
    
    /**
     * Pipeline statistics
     */
    data class PipelineStats(
        val totalTranslations: Long,
        val cacheHits: Long,
        val cacheMisses: Long,
        val batchProcessingCount: Long,
        val averageProcessingTime: Double,
        val activeTranslators: Int,
        val cacheSize: Int,
        val isInitialized: Boolean,
        val isProcessing: Boolean
    )
    
    /**
     * Initializes the translation pipeline
     */
    suspend fun initialize(config: PipelineConfig = PipelineConfig()): TranslationResult = withContext(Dispatchers.IO) {
        try {
            val translationPreferences = configurationManager.currentTranslationConfig()
            val performancePreferences = configurationManager.currentPerformanceConfig()

            if (!translationPreferences.enableOnDeviceTranslation) {
                return@withContext TranslationResult(
                    success = false,
                    error = "On-device translation disabled in settings",
                    timestamp = System.currentTimeMillis()
                )
            }

            val resolvedConfig = config.copy(
                defaultSourceLanguage = translationPreferences.defaultSourceLanguage,
                defaultTargetLanguage = translationPreferences.defaultTargetLanguage,
                enableCaching = performancePreferences.enableCaching,
                enableBatchProcessing = config.enableBatchProcessing && translationPreferences.enableAutoTranslation,
                confidenceThreshold = translationPreferences.confidenceThreshold.coerceAtLeast(MIN_CONFIDENCE_THRESHOLD)
            )

            applyConfigurationUpdates(translationPreferences, performancePreferences, resolvedConfig.confidenceThreshold)

            // Initialize language identifier
            rebuildLanguageIdentifier()

            // Start batch processing if enabled
            if (enableBatchProcessing) {
                startBatchProcessor(resolvedConfig.batchSize, resolvedConfig.batchTimeoutMs)
            }

            isInitialized.set(true)
            startConfigurationObservers()

            Log.d(TAG, "TranslationPipeline initialized successfully")
            TranslationResult(
                success = true,
                originalText = "Translation pipeline initialized",
                isOnDevice = true,
                timestamp = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TranslationPipeline", e)
            TranslationResult(
                success = false,
                error = "Initialization failed: ${e.message}",
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Creates a continuous translation flow for real-time processing
     */
    fun createTranslationFlow(
        sourceLanguage: String? = null,
        targetLanguage: String? = null
    ): Flow<TranslationResult> = flow {
        val targetLang = targetLanguage ?: defaultTargetLanguage
        val sourceLang = sourceLanguage ?: defaultSourceLanguage
        
        try {
            while (currentCoroutineContext().isActive) {
                // This flow will be used with external data sources
                // The actual translation happens when data is emitted to this flow
                delay(100) // Prevent busy waiting
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in translation flow", e)
            emit(TranslationResult(
                success = false,
                error = "Translation flow error: ${e.message}",
                timestamp = System.currentTimeMillis()
            ))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Processes text through the translation pipeline
     */
    suspend fun processText(
        text: String,
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
        isPartial: Boolean = false
    ): TranslationResult = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized.get()) {
                return@withContext TranslationResult(
                    success = false,
                    error = "Pipeline not initialized",
                    timestamp = System.currentTimeMillis()
                )
            }

            val translationPreferences = configurationManager.currentTranslationConfig()
            val performancePreferences = configurationManager.currentPerformanceConfig()

            if (!translationPreferences.enableOnDeviceTranslation) {
                return@withContext TranslationResult(
                    success = false,
                    error = "On-device translation disabled in settings",
                    timestamp = System.currentTimeMillis()
                )
            }

            applyConfigurationUpdates(translationPreferences, performancePreferences, translationPreferences.confidenceThreshold)

            val targetLang = targetLanguage ?: defaultTargetLanguage
            val sourceLang = sourceLanguage ?: defaultSourceLanguage

            if (enableCaching && !isPartial) {
                val cacheKey = generateCacheKey(text, sourceLang, targetLang)
                val cachedResult = translationCache[cacheKey]
                if (cachedResult != null) {
                    Log.d(TAG, "Cache hit for: $text")
                    return@withContext cachedResult.copy(timestamp = System.currentTimeMillis())
                }
            }

            val detectedSourceLang = if (sourceLang == "auto" && !isPartial) {
                detectLanguage(text)
            } else {
                sourceLang
            }

            val translationResult = translateText(text, detectedSourceLang, targetLang, isPartial)

            if (enableCaching && translationResult.success && !isPartial) {
                val cacheKey = generateCacheKey(text, detectedSourceLang, targetLang)
                cacheTranslationResult(cacheKey, translationResult)
            }

            if (historyEnabled && translationResult.success && !translationResult.isPartial) {
                try {
                    historyRepository.addTranslation(translationResult)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save translation to history", e)
                }
            }

            translationCounter.incrementAndGet()
            translationResult

        } catch (e: Exception) {
            Log.e(TAG, "Error processing text", e)
            TranslationResult(
                success = false,
                error = "Processing failed: ${e.message}",
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun startConfigurationObservers() {
        if (configurationJob != null) return
        configurationJob = configurationScope.launch {
            configurationManager.translationConfig
                .combine(configurationManager.performanceConfig) { translation, performance ->
                    translation to performance
                }
                .collectLatest { (translation, performance) ->
                    applyConfigurationUpdates(translation, performance, translation.confidenceThreshold)
                }
        }
    }

    private fun applyConfigurationUpdates(
        translation: TranslationConfig,
        performance: PerformanceConfig,
        rawConfidence: Float
    ) {
        historyEnabled = translation.enableHistory
        defaultSourceLanguage = translation.defaultSourceLanguage
        defaultTargetLanguage = translation.defaultTargetLanguage
        enableCaching = performance.enableCaching

        val updatedConfidence = rawConfidence.coerceAtLeast(MIN_CONFIDENCE_THRESHOLD)
        if (updatedConfidence != confidenceThreshold || languageIdentifier == null) {
            confidenceThreshold = updatedConfidence
            rebuildLanguageIdentifier()
        }

        enableBatchProcessing = translation.enableAutoTranslation
    }

    private fun rebuildLanguageIdentifier() {
        languageIdentifier?.close()
        val options = LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(confidenceThreshold)
            .build()
        languageIdentifier = LanguageIdentification.getClient(options)
    }

    /**
     * Processes multiple texts in batch
     */
    suspend fun processBatch(
        texts: List<String>,
        sourceLanguage: String? = null,
        targetLanguage: String? = null
    ): List<TranslationResult> = withContext(Dispatchers.IO) {
        val targetLang = targetLanguage ?: defaultTargetLanguage
        val sourceLang = sourceLanguage ?: defaultSourceLanguage
        
        try {
            // Detect language for all texts if source is auto
            val detectedSourceLang = if (sourceLang == "auto") {
                detectLanguage(texts.firstOrNull() ?: "")
            } else {
                sourceLang
            }
            
            // Process all texts concurrently
            texts.map { text ->
                async {
                    processText(text, detectedSourceLang, targetLang, false)
                }
            }.awaitAll()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing batch", e)
            texts.map { text ->
                TranslationResult(
                    success = false,
                    originalText = text,
                    error = "Batch processing failed: ${e.message}",
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }
    
    /**
     * Adds text to batch processing queue
     */
    suspend fun addToBatch(
        text: String,
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
        isPartial: Boolean = false,
        callback: suspend (TranslationResult) -> Unit
    ) {
        if (!enableBatchProcessing) {
            val result = processText(text, sourceLanguage, targetLanguage, isPartial)
            callback(result)
            return
        }
        
        val request = TranslationRequest(
            text = text,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage ?: defaultTargetLanguage,
            isPartial = isPartial,
            callback = callback
        )
        
        batchChannel.trySend(request)
    }
    
    /**
     * Detects the language of the given text
     */
    private suspend fun detectLanguage(text: String): String = withContext(Dispatchers.IO) {
        try {
            // Check cache first
            if (enableCaching) {
                val cachedLanguage = languageDetectionCache[text]
                if (cachedLanguage != null) {
                    return@withContext cachedLanguage
                }
            }
            
            val identifier = languageIdentifier ?: throw IllegalStateException("Language identifier not initialized")
            
            val detectedLanguage = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { continuation ->
                    identifier.identifyLanguage(text)
                        .addOnSuccessListener { languageCode ->
                            Log.d(TAG, "Language detected: $languageCode")
                            continuation.resume(languageCode)
                        }
                        .addOnFailureListener { exception ->
                            Log.e(TAG, "Language detection failed", exception)
                            continuation.resumeWithException(exception)
                        }
                }
            }
            
            val result = detectedLanguage ?: "und" // undefined
            
            // Cache result
            if (enableCaching) {
                languageDetectionCache[text] = result
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Language detection error", e)
            "und"
        }
    }
    
    /**
     * Translates text from source to target language
     */
    private suspend fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        isPartial: Boolean = false
    ): TranslationResult = withContext(Dispatchers.IO) {
        try {
            val translator = getOrCreateTranslator(sourceLanguage, targetLanguage)
            
            val translatedText = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { continuation ->
                    translator.translate(text)
                        .addOnSuccessListener { result ->
                            Log.d(TAG, "Translation successful: $text -> $result")
                            continuation.resume(result)
                        }
                        .addOnFailureListener { exception ->
                            Log.e(TAG, "Translation failed", exception)
                            continuation.resumeWithException(exception)
                        }
                }
            }
            
            if (translatedText != null) {
                TranslationResult(
                    success = true,
                    originalText = text,
                    translatedText = translatedText,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    confidence = confidenceThreshold,
                    isPartial = isPartial,
                    isOnDevice = true,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                TranslationResult(
                    success = false,
                    originalText = text,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    error = "Translation timed out",
                    timestamp = System.currentTimeMillis()
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            TranslationResult(
                success = false,
                originalText = text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                error = "Translation failed: ${e.message}",
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Gets or creates a translator for the given language pair
     */
    private suspend fun getOrCreateTranslator(sourceLanguage: String, targetLanguage: String): Translator {
        val key = "${sourceLanguage}_$targetLanguage"
        
        return activeTranslators[key] ?: run {
            modelManager.ensureLanguagePairAvailable(sourceLanguage, targetLanguage)
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
            
            val translator = Translation.getClient(options)
            activeTranslators[key] = translator
            translator
        }
    }
    
    /**
     * Starts the batch processor
     */
    private fun startBatchProcessor(batchSize: Int, batchTimeoutMs: Long) {
        batchScope.launch {
            val batch = mutableListOf<TranslationRequest>()
            var lastBatchTime = System.currentTimeMillis()
            
            batchChannel.consumeEach { request ->
                batch.add(request)
                
                val currentTime = System.currentTimeMillis()
                val shouldProcess = batch.size >= batchSize || 
                    (currentTime - lastBatchTime) >= batchTimeoutMs
                
                if (shouldProcess) {
                    processBatchRequests(batch.toList())
                    batch.clear()
                    lastBatchTime = currentTime
                }
            }
        }
    }
    
    /**
     * Processes a batch of translation requests
     */
    private suspend fun processBatchRequests(requests: List<TranslationRequest>) {
        try {
            isProcessing.set(true)
            
            // Group requests by language pair for efficiency
            val groupedRequests = requests.groupBy { 
                "${it.sourceLanguage ?: defaultSourceLanguage}_${it.targetLanguage}"
            }
            
            // Process each group concurrently
            val deferredJobs = groupedRequests.values.map { group ->
                CoroutineScope(Dispatchers.IO).async {
                    group.forEach { request ->
                        try {
                            val result = processText(
                                request.text,
                                request.sourceLanguage,
                                request.targetLanguage,
                                request.isPartial
                            )
                            request.callback(result)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing batch request", e)
                            request.callback(TranslationResult(
                                success = false,
                                originalText = request.text,
                                error = "Batch processing error: ${e.message}",
                                timestamp = System.currentTimeMillis()
                            ))
                        }
                    }
                }
            }
            
            // Wait for all jobs to complete
            deferredJobs.forEach { it.await() }
            
        } finally {
            isProcessing.set(false)
        }
    }
    
    /**
     * Generates cache key for translation
     */
    private fun generateCacheKey(text: String, sourceLanguage: String, targetLanguage: String): String {
        return "${sourceLanguage}_${targetLanguage}_${text.hashCode()}"
    }
    
    /**
     * Caches translation result
     */
    private fun cacheTranslationResult(key: String, result: TranslationResult) {
        if (translationCache.size >= CACHE_SIZE) {
            // Remove oldest entry (simple LRU)
            val oldestKey = translationCache.keys.firstOrNull()
            if (oldestKey != null) {
                translationCache.remove(oldestKey)
            }
        }
        translationCache[key] = result
    }
    
    /**
     * Gets supported languages
     */
    suspend fun getSupportedLanguages(): List<SupportedLanguage> = withContext(Dispatchers.IO) {
        try {
            // ML Kit supported languages
            listOf(
                SupportedLanguage("af", "Afrikaans", "Afrikaans", true),
                SupportedLanguage("ar", "Arabic", "العربية", true),
                SupportedLanguage("be", "Belarusian", "Беларуская", true),
                SupportedLanguage("bg", "Bulgarian", "Български", true),
                SupportedLanguage("bn", "Bengali", "বাংলা", true),
                SupportedLanguage("ca", "Catalan", "Català", true),
                SupportedLanguage("cs", "Czech", "Čeština", true),
                SupportedLanguage("cy", "Welsh", "Cymraeg", true),
                SupportedLanguage("da", "Danish", "Dansk", true),
                SupportedLanguage("de", "German", "Deutsch", true),
                SupportedLanguage("el", "Greek", "Ελληνικά", true),
                SupportedLanguage("en", "English", "English", true),
                SupportedLanguage("es", "Spanish", "Español", true),
                SupportedLanguage("et", "Estonian", "Eesti", true),
                SupportedLanguage("fa", "Persian", "فارسی", true),
                SupportedLanguage("fi", "Finnish", "Suomi", true),
                SupportedLanguage("fr", "French", "Français", true),
                SupportedLanguage("gl", "Galician", "Galego", true),
                SupportedLanguage("gu", "Gujarati", "ગુજરાતી", true),
                SupportedLanguage("he", "Hebrew", "עברית", true),
                SupportedLanguage("hi", "Hindi", "हिन्दी", true),
                SupportedLanguage("hr", "Croatian", "Hrvatski", true),
                SupportedLanguage("hu", "Hungarian", "Magyar", true),
                SupportedLanguage("id", "Indonesian", "Bahasa Indonesia", true),
                SupportedLanguage("is", "Icelandic", "Íslenska", true),
                SupportedLanguage("it", "Italian", "Italiano", true),
                SupportedLanguage("ja", "Japanese", "日本語", true),
                SupportedLanguage("ka", "Georgian", "ქართული", true),
                SupportedLanguage("kn", "Kannada", "ಕನ್ನಡ", true),
                SupportedLanguage("ko", "Korean", "한국어", true),
                SupportedLanguage("lt", "Lithuanian", "Lietuvių", true),
                SupportedLanguage("lv", "Latvian", "Latviešu", true),
                SupportedLanguage("mk", "Macedonian", "Македонски", true),
                SupportedLanguage("mr", "Marathi", "मराठी", true),
                SupportedLanguage("ms", "Malay", "Bahasa Melayu", true),
                SupportedLanguage("nb", "Norwegian", "Norsk", true),
                SupportedLanguage("nl", "Dutch", "Nederlands", true),
                SupportedLanguage("pl", "Polish", "Polski", true),
                SupportedLanguage("pt", "Portuguese", "Português", true),
                SupportedLanguage("ro", "Romanian", "Română", true),
                SupportedLanguage("ru", "Russian", "Русский", true),
                SupportedLanguage("sk", "Slovak", "Slovenčina", true),
                SupportedLanguage("sl", "Slovenian", "Slovenščina", true),
                SupportedLanguage("sq", "Albanian", "Shqip", true),
                SupportedLanguage("sr", "Serbian", "Српски", true),
                SupportedLanguage("sv", "Swedish", "Svenska", true),
                SupportedLanguage("sw", "Swahili", "Kiswahili", true),
                SupportedLanguage("ta", "Tamil", "தமிழ்", true),
                SupportedLanguage("te", "Telugu", "తెలుగు", true),
                SupportedLanguage("th", "Thai", "ไทย", true),
                SupportedLanguage("tr", "Turkish", "Türkçe", true),
                SupportedLanguage("uk", "Ukrainian", "Українська", true),
                SupportedLanguage("ur", "Urdu", "اردو", true),
                SupportedLanguage("vi", "Vietnamese", "Tiếng Việt", true),
                SupportedLanguage("zh", "Chinese", "中文", true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting supported languages", e)
            emptyList()
        }
    }
    
    /**
     * Gets pipeline statistics
     */
    fun getStats(): PipelineStats {
        val cacheHits = translationCache.size.toLong()
        val cacheMisses = translationCounter.get() - cacheHits
        
        return PipelineStats(
            totalTranslations = translationCounter.get(),
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            batchProcessingCount = 0, // TODO: Track batch processing count
            averageProcessingTime = 0.0, // TODO: Track processing times
            activeTranslators = activeTranslators.size,
            cacheSize = translationCache.size,
            isInitialized = isInitialized.get(),
            isProcessing = isProcessing.get()
        )
    }
    
    /**
     * Clears the translation cache
     */
    fun clearCache() {
        translationCache.clear()
        languageDetectionCache.clear()
        Log.d(TAG, "Translation cache cleared")
    }
    
    /**
     * Updates pipeline configuration
     */
    suspend fun updateConfig(config: PipelineConfig): TranslationResult {
        try {
            defaultSourceLanguage = config.defaultSourceLanguage
            defaultTargetLanguage = config.defaultTargetLanguage
            enableCaching = config.enableCaching
            enableBatchProcessing = config.enableBatchProcessing
            confidenceThreshold = config.confidenceThreshold
            
            // Restart batch processor if needed
            if (enableBatchProcessing && batchJob.isCancelled) {
                startBatchProcessor(config.batchSize, config.batchTimeoutMs)
            }
            
            Log.d(TAG, "Pipeline configuration updated")
            return TranslationResult(
                success = true,
                originalText = "Configuration updated",
                timestamp = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating configuration", e)
            return TranslationResult(
                success = false,
                error = "Configuration update failed: ${e.message}",
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        try {
            configurationJob?.cancel()
            configurationScope.coroutineContext.cancelChildren()
            batchJob.cancel()
            activeTranslators.values.forEach { it.close() }
            activeTranslators.clear()
            translationCache.clear()
            languageDetectionCache.clear()
            configurationJob = null
            isInitialized.set(false)
            
            Log.d(TAG, "TranslationPipeline cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

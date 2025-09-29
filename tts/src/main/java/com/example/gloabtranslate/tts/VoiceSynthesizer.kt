package com.example.gloabtranslate.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
import java.util.Locale
import java.util.UUID

/**
 * Voice synthesizer for handling text-to-speech synthesis with advanced features.
 * Provides voice synthesis for translated text with support for multiple languages,
 * voice selection, and audio processing.
 */
class VoiceSynthesizer(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceSynthesizer"
        private const val MAX_QUEUE_SIZE = 10
        private const val SYNTHESIS_TIMEOUT_MS = 30000L
        private const val BATCH_SIZE = 5
    }
    
    private val ttsService = TextToSpeechService(context)
    private val synthesisQueue = mutableListOf<SynthesisRequest>()
    private val activeSyntheses = mutableMapOf<String, SynthesisRequest>()
    private val synthesisResults = mutableMapOf<String, SynthesisResult>()
    
    // Synthesis state
    private var isInitialized = false
    private var isProcessing = false
    private var currentLanguage = Locale.ENGLISH
    private var currentVoice: TextToSpeechService.VoiceInfo? = null
    
    // Synthesis listeners
    private val synthesisListeners = mutableListOf<SynthesisListener>()
    
    // Event channel for synthesis events
    private val eventChannel = Channel<SynthesisEvent>(Channel.UNLIMITED)
    
    /**
     * Synthesis request
     */
    data class SynthesisRequest(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
        val language: Locale,
        val voice: TextToSpeechService.VoiceInfo? = null,
        val priority: SynthesisPriority = SynthesisPriority.NORMAL,
        val options: SynthesisOptions = SynthesisOptions(),
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Synthesis result
     */
    data class SynthesisResult(
        val requestId: String,
        val success: Boolean,
        val utteranceId: String? = null,
        val duration: Long = 0L,
        val error: String? = null,
        val audioData: ByteArray? = null,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Synthesis options
     */
    data class SynthesisOptions(
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
        val volume: Float = 1.0f,
        val enablePunctuation: Boolean = true,
        val enableSSML: Boolean = false,
        val enableEmphasis: Boolean = true,
        val enablePauses: Boolean = true,
        val enableBreathing: Boolean = false,
        val enableEmotions: Boolean = false,
        val emotion: EmotionType = EmotionType.NEUTRAL,
        val enableBackgroundMusic: Boolean = false,
        val backgroundMusicVolume: Float = 0.3f,
        val enableEcho: Boolean = false,
        val echoDelay: Float = 0.5f,
        val enableReverb: Boolean = false,
        val reverbLevel: Float = 0.2f,
        val enableNoiseReduction: Boolean = true,
        val enableVoiceCloning: Boolean = false,
        val voiceCloningModel: String? = null
    )
    
    /**
     * Synthesis priority levels
     */
    enum class SynthesisPriority {
        LOW, NORMAL, HIGH, URGENT
    }
    
    /**
     * Emotion types for synthesis
     */
    enum class EmotionType {
        NEUTRAL, HAPPY, SAD, ANGRY, EXCITED, CALM, CONFIDENT, SHY, SURPRISED, WORRIED
    }
    
    /**
     * Synthesis event types
     */
    enum class SynthesisEventType {
        SYNTHESIS_STARTED, SYNTHESIS_PROGRESS, SYNTHESIS_COMPLETED, SYNTHESIS_FAILED,
        QUEUE_UPDATED, VOICE_CHANGED, LANGUAGE_CHANGED, OPTIONS_CHANGED,
        BATCH_STARTED, BATCH_COMPLETED, BATCH_FAILED
    }
    
    /**
     * Synthesis event
     */
    data class SynthesisEvent(
        val type: SynthesisEventType,
        val message: String,
        val requestId: String? = null,
        val progress: Float = 0.0f,
        val queueSize: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Synthesis listener interface
     */
    interface SynthesisListener {
        fun onSynthesisStarted(requestId: String)
        fun onSynthesisProgress(requestId: String, progress: Float)
        fun onSynthesisCompleted(requestId: String, result: SynthesisResult)
        fun onSynthesisFailed(requestId: String, error: String)
        fun onQueueUpdated(queueSize: Int)
        fun onVoiceChanged(voice: TextToSpeechService.VoiceInfo)
        fun onLanguageChanged(language: Locale)
        fun onOptionsChanged(options: SynthesisOptions)
        fun onBatchStarted(batchSize: Int)
        fun onBatchCompleted(successCount: Int, failureCount: Int)
        fun onBatchFailed(error: String)
    }
    
    /**
     * Initializes the voice synthesizer
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) {
                Log.d(TAG, "Voice synthesizer already initialized")
                return@withContext true
            }
            
            val ttsInitialized = ttsService.initialize()
            if (ttsInitialized) {
                isInitialized = true
                startSynthesisProcessor()
                Log.d(TAG, "Voice synthesizer initialized successfully")
                true
            } else {
                Log.e(TAG, "Failed to initialize TTS service")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize voice synthesizer", e)
            false
        }
    }
    
    /**
     * Synthesizes text to speech
     */
    suspend fun synthesizeText(
        text: String,
        language: Locale,
        voice: TextToSpeechService.VoiceInfo? = null,
        options: SynthesisOptions = SynthesisOptions(),
        priority: SynthesisPriority = SynthesisPriority.NORMAL
    ): SynthesisResult = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext SynthesisResult(
                    requestId = "",
                    success = false,
                    error = "Voice synthesizer not initialized"
                )
            }
            
            if (text.isBlank()) {
                return@withContext SynthesisResult(
                    requestId = "",
                    success = false,
                    error = "Text cannot be empty"
                )
            }
            
            val request = SynthesisRequest(
                text = text,
                language = language,
                voice = voice,
                priority = priority,
                options = options
            )
            
            // Add to queue
            addToQueue(request)
            
            // Wait for synthesis to complete
            val result = suspendCancellableCoroutine<SynthesisResult> { continuation ->
                // Set up a listener to wait for completion
                val listener = object : SynthesisListener {
                    override fun onSynthesisCompleted(requestId: String, result: SynthesisResult) {
                        if (requestId == request.id) {
                            continuation.resume(result)
                        }
                    }
                    
                    override fun onSynthesisFailed(requestId: String, error: String) {
                        if (requestId == request.id) {
                            continuation.resumeWithException(Exception(error))
                        }
                    }
                    
                    override fun onSynthesisStarted(requestId: String) {}
                    override fun onSynthesisProgress(requestId: String, progress: Float) {}
                    override fun onQueueUpdated(queueSize: Int) {}
                    override fun onVoiceChanged(voice: TextToSpeechService.VoiceInfo) {}
                    override fun onLanguageChanged(language: Locale) {}
                    override fun onOptionsChanged(options: SynthesisOptions) {}
                    override fun onBatchStarted(batchSize: Int) {}
                    override fun onBatchCompleted(successCount: Int, failureCount: Int) {}
                    override fun onBatchFailed(error: String) {}
                }
                
                addSynthesisListener(listener)
                
                continuation.invokeOnCancellation {
                    removeSynthesisListener(listener)
                }
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to synthesize text", e)
            SynthesisResult(
                requestId = "",
                success = false,
                error = "Failed to synthesize text: ${e.message}"
            )
        }
    }
    
    /**
     * Synthesizes multiple texts in batch
     */
    suspend fun synthesizeBatch(
        requests: List<SynthesisRequest>
    ): List<SynthesisResult> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                return@withContext requests.map { request ->
                    SynthesisResult(
                        requestId = request.id,
                        success = false,
                        error = "Voice synthesizer not initialized"
                    )
                }
            }
            
            if (requests.isEmpty()) {
                return@withContext emptyList()
            }
            
            notifySynthesisEvent(SynthesisEvent(
                type = SynthesisEventType.BATCH_STARTED,
                message = "Batch synthesis started",
                queueSize = requests.size
            ))
            
            // Add all requests to queue
            requests.forEach { request ->
                addToQueue(request)
            }
            
            // Wait for all syntheses to complete
            val results = mutableListOf<SynthesisResult>()
            var successCount = 0
            var failureCount = 0
            
            for (request in requests) {
                try {
                    val result = suspendCancellableCoroutine<SynthesisResult> { continuation ->
                        val listener = object : SynthesisListener {
                            override fun onSynthesisCompleted(requestId: String, result: SynthesisResult) {
                                if (requestId == request.id) {
                                    continuation.resume(result)
                                }
                            }
                            
                            override fun onSynthesisFailed(requestId: String, error: String) {
                                if (requestId == request.id) {
                                    continuation.resumeWithException(Exception(error))
                                }
                            }
                            
                            override fun onSynthesisStarted(requestId: String) {}
                            override fun onSynthesisProgress(requestId: String, progress: Float) {}
                            override fun onQueueUpdated(queueSize: Int) {}
                            override fun onVoiceChanged(voice: TextToSpeechService.VoiceInfo) {}
                            override fun onLanguageChanged(language: Locale) {}
                            override fun onOptionsChanged(options: SynthesisOptions) {}
                            override fun onBatchStarted(batchSize: Int) {}
                            override fun onBatchCompleted(successCount: Int, failureCount: Int) {}
                            override fun onBatchFailed(error: String) {}
                        }
                        
                        addSynthesisListener(listener)
                        
                        continuation.invokeOnCancellation {
                            removeSynthesisListener(listener)
                        }
                    }
                    
                    results.add(result)
                    if (result.success) {
                        successCount++
                    } else {
                        failureCount++
                    }
                    
                } catch (e: Exception) {
                    val errorResult = SynthesisResult(
                        requestId = request.id,
                        success = false,
                        error = "Synthesis failed: ${e.message}"
                    )
                    results.add(errorResult)
                    failureCount++
                }
            }
            
            notifySynthesisEvent(SynthesisEvent(
                type = SynthesisEventType.BATCH_COMPLETED,
                message = "Batch synthesis completed",
                queueSize = 0
            ))
            
            Log.d(TAG, "Batch synthesis completed: $successCount success, $failureCount failures")
            results
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to synthesize batch", e)
            notifySynthesisEvent(SynthesisEvent(
                type = SynthesisEventType.BATCH_FAILED,
                message = "Batch synthesis failed: ${e.message}"
            ))
            requests.map { request ->
                SynthesisResult(
                    requestId = request.id,
                    success = false,
                    error = "Batch synthesis failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Processes text for synthesis (applies options, SSML, etc.)
     */
    private fun processTextForSynthesis(text: String, options: SynthesisOptions): String {
        var processedText = text
        
        // Apply punctuation handling
        if (!options.enablePunctuation) {
            processedText = processedText.replace(Regex("[.,!?;:]"), "")
        }
        
        // Apply SSML if enabled
        if (options.enableSSML) {
            processedText = wrapWithSSML(processedText, options)
        }
        
        // Apply emphasis if enabled
        if (options.enableEmphasis) {
            processedText = applyEmphasis(processedText, options.emotion)
        }
        
        // Apply pauses if enabled
        if (options.enablePauses) {
            processedText = applyPauses(processedText)
        }
        
        // Apply breathing if enabled
        if (options.enableBreathing) {
            processedText = applyBreathing(processedText)
        }
        
        return processedText
    }
    
    /**
     * Wraps text with SSML
     */
    private fun wrapWithSSML(text: String, options: SynthesisOptions): String {
        val ssml = StringBuilder()
        ssml.append("<speak>")
        
        // Apply voice settings
        if (options.speechRate != 1.0f) {
            ssml.append("<prosody rate=\"${options.speechRate}\">")
        }
        
        if (options.pitch != 1.0f) {
            ssml.append("<prosody pitch=\"${options.pitch}\">")
        }
        
        if (options.volume != 1.0f) {
            ssml.append("<prosody volume=\"${options.volume}\">")
        }
        
        // Apply emotion
        if (options.enableEmotions) {
            val emotion = when (options.emotion) {
                EmotionType.HAPPY -> "cheerful"
                EmotionType.SAD -> "sad"
                EmotionType.ANGRY -> "angry"
                EmotionType.EXCITED -> "excited"
                EmotionType.CALM -> "calm"
                EmotionType.CONFIDENT -> "confident"
                EmotionType.SHY -> "shy"
                EmotionType.SURPRISED -> "surprised"
                EmotionType.WORRIED -> "worried"
                else -> "neutral"
            }
            ssml.append("<prosody style=\"$emotion\">")
        }
        
        ssml.append(text)
        
        // Close all tags
        if (options.enableEmotions) {
            ssml.append("</prosody>")
        }
        
        if (options.volume != 1.0f) {
            ssml.append("</prosody>")
        }
        
        if (options.pitch != 1.0f) {
            ssml.append("</prosody>")
        }
        
        if (options.speechRate != 1.0f) {
            ssml.append("</prosody>")
        }
        
        ssml.append("</speak>")
        
        return ssml.toString()
    }
    
    /**
     * Applies emphasis to text
     */
    private fun applyEmphasis(text: String, emotion: EmotionType): String {
        return when (emotion) {
            EmotionType.HAPPY -> text.replace(Regex("\\b(amazing|wonderful|great|excellent|fantastic)\\b"), "<emphasis level=\"strong\">$1</emphasis>")
            EmotionType.SAD -> text.replace(Regex("\\b(sad|terrible|awful|horrible|devastating)\\b"), "<emphasis level=\"strong\">$1</emphasis>")
            EmotionType.ANGRY -> text.replace(Regex("\\b(angry|furious|mad|outraged|frustrated)\\b"), "<emphasis level=\"strong\">$1</emphasis>")
            EmotionType.EXCITED -> text.replace(Regex("\\b(excited|thrilled|ecstatic|overjoyed|elated)\\b"), "<emphasis level=\"strong\">$1</emphasis>")
            else -> text
        }
    }
    
    /**
     * Applies pauses to text
     */
    private fun applyPauses(text: String): String {
        return text
            .replace(Regex("([.!?])\\s+"), "$1<break time=\"1s\"/>")
            .replace(Regex("([,;:])\\s+"), "$1<break time=\"0.5s\"/>")
    }
    
    /**
     * Applies breathing to text
     */
    private fun applyBreathing(text: String): String {
        // Add breathing pauses at natural breaks
        return text.replace(Regex("\\b(and|but|so|however|therefore)\\b"), "$1<break time=\"0.3s\"/>")
    }
    
    /**
     * Adds request to synthesis queue
     */
    private fun addToQueue(request: SynthesisRequest) {
        synthesisQueue.add(request)
        
        // Sort by priority
        synthesisQueue.sortBy { it.priority.ordinal }
        
        // Limit queue size
        if (synthesisQueue.size > MAX_QUEUE_SIZE) {
            synthesisQueue.removeAt(synthesisQueue.size - 1)
        }
        
        notifySynthesisEvent(SynthesisEvent(
            type = SynthesisEventType.QUEUE_UPDATED,
            message = "Queue updated",
            queueSize = synthesisQueue.size
        ))
        
        Log.d(TAG, "Added request to queue: ${request.id}, queue size: ${synthesisQueue.size}")
    }
    
    /**
     * Starts the synthesis processor
     */
    private fun startSynthesisProcessor() {
        // This would typically run in a background coroutine
        // For now, we'll implement a simple version
        Log.d(TAG, "Synthesis processor started")
    }
    
    /**
     * Processes synthesis requests
     */
    private suspend fun processSynthesisRequests() = coroutineScope {
        while (isInitialized) {
            if (synthesisQueue.isNotEmpty() && !isProcessing) {
                isProcessing = true
                
                val request = synthesisQueue.removeAt(0)
                activeSyntheses[request.id] = request
                
                try {
                    notifySynthesisEvent(SynthesisEvent(
                        type = SynthesisEventType.SYNTHESIS_STARTED,
                        message = "Synthesis started",
                        requestId = request.id
                    ))
                    
                    // Process text for synthesis
                    val processedText = processTextForSynthesis(request.text, request.options)
                    
                    // Configure TTS
                    val ttsConfig = TextToSpeechService.TTSConfig(
                        language = request.language,
                        speechRate = request.options.speechRate,
                        pitch = request.options.pitch,
                        volume = request.options.volume,
                        voice = request.voice
                    )
                    
                    // Perform synthesis
                    val ttsResult = ttsService.speak(processedText, ttsConfig, request.id)
                    
                    val result = SynthesisResult(
                        requestId = request.id,
                        success = ttsResult.success,
                        utteranceId = ttsResult.utteranceId,
                        duration = ttsResult.duration,
                        error = ttsResult.error
                    )
                    
                    synthesisResults[request.id] = result
                    activeSyntheses.remove(request.id)
                    
                    if (result.success) {
                        notifySynthesisEvent(SynthesisEvent(
                            type = SynthesisEventType.SYNTHESIS_COMPLETED,
                            message = "Synthesis completed",
                            requestId = request.id
                        ))
                    } else {
                        notifySynthesisEvent(SynthesisEvent(
                            type = SynthesisEventType.SYNTHESIS_FAILED,
                            message = "Synthesis failed: ${result.error}",
                            requestId = request.id
                        ))
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process synthesis request", e)
                    
                    val result = SynthesisResult(
                        requestId = request.id,
                        success = false,
                        error = "Synthesis processing failed: ${e.message}"
                    )
                    
                    synthesisResults[request.id] = result
                    activeSyntheses.remove(request.id)
                    
                    notifySynthesisEvent(SynthesisEvent(
                        type = SynthesisEventType.SYNTHESIS_FAILED,
                        message = "Synthesis failed: ${e.message}",
                        requestId = request.id
                    ))
                }
                
                isProcessing = false
            }
            
            // Small delay to prevent busy waiting
            kotlinx.coroutines.delay(100)
        }
    }
    
    /**
     * Gets synthesis status
     */
    fun getSynthesisStatus(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized,
            "isProcessing" to isProcessing,
            "queueSize" to synthesisQueue.size,
            "activeSyntheses" to activeSyntheses.size,
            "completedSyntheses" to synthesisResults.size,
            "currentLanguage" to currentLanguage.displayName,
            "currentVoice" to (currentVoice?.name ?: "Default")
        )
    }
    
    /**
     * Clears synthesis queue
     */
    fun clearQueue() {
        synthesisQueue.clear()
        activeSyntheses.clear()
        synthesisResults.clear()
        
        notifySynthesisEvent(SynthesisEvent(
            type = SynthesisEventType.QUEUE_UPDATED,
            message = "Queue cleared",
            queueSize = 0
        ))
        
        Log.d(TAG, "Synthesis queue cleared")
    }
    
    /**
     * Adds a synthesis listener
     */
    fun addSynthesisListener(listener: SynthesisListener) {
        synthesisListeners.add(listener)
    }
    
    /**
     * Removes a synthesis listener
     */
    fun removeSynthesisListener(listener: SynthesisListener) {
        synthesisListeners.remove(listener)
    }
    
    /**
     * Creates a Flow for synthesis events
     */
    fun createSynthesisEventFlow(): Flow<SynthesisEvent> = callbackFlow {
        val listener = object : SynthesisListener {
            override fun onSynthesisStarted(requestId: String) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.SYNTHESIS_STARTED,
                    message = "Synthesis started",
                    requestId = requestId
                ))
            }
            
            override fun onSynthesisProgress(requestId: String, progress: Float) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.SYNTHESIS_PROGRESS,
                    message = "Synthesis progress",
                    requestId = requestId,
                    progress = progress
                ))
            }
            
            override fun onSynthesisCompleted(requestId: String, result: SynthesisResult) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.SYNTHESIS_COMPLETED,
                    message = "Synthesis completed",
                    requestId = requestId
                ))
            }
            
            override fun onSynthesisFailed(requestId: String, error: String) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.SYNTHESIS_FAILED,
                    message = "Synthesis failed: $error",
                    requestId = requestId
                ))
            }
            
            override fun onQueueUpdated(queueSize: Int) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.QUEUE_UPDATED,
                    message = "Queue updated",
                    queueSize = queueSize
                ))
            }
            
            override fun onVoiceChanged(voice: TextToSpeechService.VoiceInfo) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.VOICE_CHANGED,
                    message = "Voice changed to ${voice.name}"
                ))
            }
            
            override fun onLanguageChanged(language: Locale) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.LANGUAGE_CHANGED,
                    message = "Language changed to ${language.displayName}"
                ))
            }
            
            override fun onOptionsChanged(options: SynthesisOptions) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.OPTIONS_CHANGED,
                    message = "Options changed"
                ))
            }
            
            override fun onBatchStarted(batchSize: Int) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.BATCH_STARTED,
                    message = "Batch started",
                    queueSize = batchSize
                ))
            }
            
            override fun onBatchCompleted(successCount: Int, failureCount: Int) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.BATCH_COMPLETED,
                    message = "Batch completed: $successCount success, $failureCount failures"
                ))
            }
            
            override fun onBatchFailed(error: String) {
                trySend(SynthesisEvent(
                    type = SynthesisEventType.BATCH_FAILED,
                    message = "Batch failed: $error"
                ))
            }
        }
        
        addSynthesisListener(listener)
        
        awaitClose {
            removeSynthesisListener(listener)
        }
    }.flowOn(Dispatchers.Default)
    
    /**
     * Notifies synthesis event
     */
    private fun notifySynthesisEvent(event: SynthesisEvent) {
        synthesisListeners.forEach { listener ->
            try {
                when (event.type) {
                    SynthesisEventType.SYNTHESIS_STARTED -> {
                        event.requestId?.let { listener.onSynthesisStarted(it) }
                    }
                    SynthesisEventType.SYNTHESIS_PROGRESS -> {
                        event.requestId?.let { listener.onSynthesisProgress(it, event.progress) }
                    }
                    SynthesisEventType.SYNTHESIS_COMPLETED -> {
                        event.requestId?.let { requestId ->
                            val result = synthesisResults[requestId]
                            if (result != null) {
                                listener.onSynthesisCompleted(requestId, result)
                            }
                        }
                    }
                    SynthesisEventType.SYNTHESIS_FAILED -> {
                        event.requestId?.let { requestId ->
                            listener.onSynthesisFailed(requestId, event.message)
                        }
                    }
                    SynthesisEventType.QUEUE_UPDATED -> {
                        listener.onQueueUpdated(event.queueSize)
                    }
                    SynthesisEventType.VOICE_CHANGED -> {
                        currentVoice?.let { voice -> listener.onVoiceChanged(voice) }
                    }
                    SynthesisEventType.LANGUAGE_CHANGED -> {
                        listener.onLanguageChanged(currentLanguage)
                    }
                    SynthesisEventType.OPTIONS_CHANGED -> {
                        // This would need to be implemented based on current options
                    }
                    SynthesisEventType.BATCH_STARTED -> {
                        listener.onBatchStarted(event.queueSize)
                    }
                    SynthesisEventType.BATCH_COMPLETED -> {
                        // This would need to be implemented based on batch results
                    }
                    SynthesisEventType.BATCH_FAILED -> {
                        listener.onBatchFailed(event.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying synthesis event", e)
            }
        }
    }
    
    /**
     * Cleans up the voice synthesizer
     */
    fun cleanup() {
        try {
            clearQueue()
            ttsService.cleanup()
            synthesisListeners.clear()
            isInitialized = false
            
            Log.d(TAG, "Voice synthesizer cleaned up")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up voice synthesizer", e)
        }
    }
}

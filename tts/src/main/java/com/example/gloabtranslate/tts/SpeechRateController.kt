package com.example.gloabtranslate.tts

import android.content.Context
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
 * Speech rate controller for managing and controlling TTS speech rate.
 * Provides comprehensive speech rate control including adaptive rate adjustment,
 * rate profiles, and real-time rate monitoring.
 */
class SpeechRateController(private val context: Context) {
    
    companion object {
        private const val TAG = "SpeechRateController"
        private const val MIN_SPEECH_RATE = 0.1f
        private const val MAX_SPEECH_RATE = 3.0f
        private const val DEFAULT_SPEECH_RATE = 1.0f
        private const val RATE_ADJUSTMENT_STEP = 0.1f
        private const val ADAPTIVE_RATE_WINDOW_SIZE = 10
        private const val RATE_MONITORING_INTERVAL_MS = 1000L
    }
    
    private val ttsService = TextToSpeechService(context)
    private val rateProfiles = mutableMapOf<String, RateProfile>()
    private val rateHistory = mutableListOf<RateEntry>()
    private val adaptiveRateData = mutableListOf<AdaptiveRateData>()
    
    // Rate control state
    private var currentRate = DEFAULT_SPEECH_RATE
    private var targetRate = DEFAULT_SPEECH_RATE
    private var isAdaptiveMode = false
    private var isRateLocked = false
    private var rateAdjustmentMode = RateAdjustmentMode.MANUAL
    
    // Rate control listeners
    private val rateControlListeners = mutableListOf<RateControlListener>()
    
    // Event channel for rate control events
    private val eventChannel = Channel<RateControlEvent>(Channel.UNLIMITED)
    
    /**
     * Rate profile for different contexts
     */
    data class RateProfile(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String,
        val baseRate: Float,
        val minRate: Float = MIN_SPEECH_RATE,
        val maxRate: Float = MAX_SPEECH_RATE,
        val adjustmentStep: Float = RATE_ADJUSTMENT_STEP,
        val adaptiveSettings: AdaptiveRateSettings? = null,
        val context: RateContext = RateContext.GENERAL,
        val isDefault: Boolean = false,
        val isEnabled: Boolean = true,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Rate entry for history tracking
     */
    data class RateEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val rate: Float,
        val context: RateContext,
        val reason: RateChangeReason,
        val duration: Long = 0L,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Adaptive rate data for learning
     */
    data class AdaptiveRateData(
        val timestamp: Long = System.currentTimeMillis(),
        val rate: Float,
        val context: RateContext,
        val userFeedback: UserFeedback? = null,
        val performance: PerformanceMetrics? = null,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Adaptive rate settings
     */
    data class AdaptiveRateSettings(
        val enabled: Boolean = true,
        val learningRate: Float = 0.1f,
        val adaptationSpeed: Float = 0.5f,
        val stabilityThreshold: Float = 0.05f,
        val maxAdjustment: Float = 0.5f,
        val contextWeight: Float = 0.3f,
        val feedbackWeight: Float = 0.4f,
        val performanceWeight: Float = 0.3f,
        val windowSize: Int = ADAPTIVE_RATE_WINDOW_SIZE,
        val minSamples: Int = 5
    )
    
    /**
     * Rate context types
     */
    enum class RateContext {
        GENERAL, NEWS, CONVERSATION, PRESENTATION, READING, TRANSLATION,
        ACCESSIBILITY, CHILDREN, ELDERLY, PROFESSIONAL, CASUAL, EMERGENCY
    }
    
    /**
     * Rate adjustment modes
     */
    enum class RateAdjustmentMode {
        MANUAL, AUTOMATIC, ADAPTIVE, CONTEXT_AWARE, USER_PREFERENCE
    }
    
    /**
     * Rate change reasons
     */
    enum class RateChangeReason {
        USER_ADJUSTMENT, AUTOMATIC_ADJUSTMENT, ADAPTIVE_LEARNING, CONTEXT_CHANGE,
        PROFILE_SWITCH, ACCESSIBILITY, PERFORMANCE_OPTIMIZATION, ERROR_RECOVERY
    }
    
    /**
     * User feedback types
     */
    enum class UserFeedback {
        TOO_FAST, TOO_SLOW, PERFECT, SLIGHTLY_FAST, SLIGHTLY_SLOW, UNKNOWN
    }
    
    /**
     * Performance metrics
     */
    data class PerformanceMetrics(
        val comprehensionScore: Float = 0.0f,
        val attentionScore: Float = 0.0f,
        val engagementScore: Float = 0.0f,
        val errorRate: Float = 0.0f,
        val completionRate: Float = 0.0f,
        val userSatisfaction: Float = 0.0f
    )
    
    /**
     * Rate control event
     */
    data class RateControlEvent(
        val type: RateControlEventType,
        val message: String,
        val oldRate: Float? = null,
        val newRate: Float? = null,
        val context: RateContext? = null,
        val reason: RateChangeReason? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Rate control event types
     */
    enum class RateControlEventType {
        RATE_CHANGED, RATE_ADJUSTED, RATE_LOCKED, RATE_UNLOCKED,
        PROFILE_LOADED, PROFILE_SAVED, PROFILE_DELETED,
        ADAPTIVE_MODE_ENABLED, ADAPTIVE_MODE_DISABLED,
        ADAPTIVE_LEARNING, ADAPTIVE_ADJUSTMENT,
        CONTEXT_CHANGED, PERFORMANCE_UPDATED, FEEDBACK_RECEIVED
    }
    
    /**
     * Rate control listener interface
     */
    interface RateControlListener {
        fun onRateChanged(oldRate: Float, newRate: Float, reason: RateChangeReason)
        fun onRateAdjusted(adjustment: Float, newRate: Float)
        fun onRateLocked(isLocked: Boolean)
        fun onProfileLoaded(profile: RateProfile)
        fun onProfileSaved(profile: RateProfile)
        fun onProfileDeleted(profileId: String)
        fun onAdaptiveModeChanged(enabled: Boolean)
        fun onAdaptiveLearning(data: AdaptiveRateData)
        fun onAdaptiveAdjustment(adjustment: Float, newRate: Float)
        fun onContextChanged(oldContext: RateContext, newContext: RateContext)
        fun onPerformanceUpdated(metrics: PerformanceMetrics)
        fun onFeedbackReceived(feedback: UserFeedback, rate: Float)
    }
    
    /**
     * Initializes the speech rate controller
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Initialize TTS service
            val ttsInitialized = ttsService.initialize()
            if (!ttsInitialized) {
                Log.e(TAG, "Failed to initialize TTS service")
                return@withContext false
            }
            
            // Load default rate profiles
            loadDefaultRateProfiles()
            
            // Start rate monitoring
            startRateMonitoring()
            
            Log.d(TAG, "Speech rate controller initialized successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech rate controller", e)
            false
        }
    }
    
    /**
     * Sets the speech rate
     */
    suspend fun setSpeechRate(rate: Float, reason: RateChangeReason = RateChangeReason.USER_ADJUSTMENT): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isRateLocked) {
                Log.w(TAG, "Rate is locked, cannot change")
                return@withContext false
            }
            
            val clampedRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
            val oldRate = currentRate
            
            // Update TTS service
            val ttsResult = ttsService.setSpeechRate(clampedRate)
            if (!ttsResult) {
                Log.e(TAG, "Failed to set speech rate in TTS service")
                return@withContext false
            }
            
            // Update current rate
            currentRate = clampedRate
            targetRate = clampedRate
            
            // Record rate change
            recordRateChange(clampedRate, reason)
            
            // Notify listeners
            notifyRateControlEvent(RateControlEvent(
                type = RateControlEventType.RATE_CHANGED,
                message = "Speech rate changed from $oldRate to $clampedRate",
                oldRate = oldRate,
                newRate = clampedRate,
                reason = reason
            ))
            
            Log.d(TAG, "Speech rate set to: $clampedRate (reason: $reason)")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speech rate", e)
            false
        }
    }
    
    /**
     * Adjusts the speech rate by a delta amount
     */
    suspend fun adjustSpeechRate(delta: Float, reason: RateChangeReason = RateChangeReason.USER_ADJUSTMENT): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isRateLocked) {
                Log.w(TAG, "Rate is locked, cannot adjust")
                return@withContext false
            }
            
            val newRate = (currentRate + delta).coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
            val adjustment = newRate - currentRate
            
            if (adjustment == 0.0f) {
                Log.d(TAG, "No rate adjustment needed")
                return@withContext true
            }
            
            val success = setSpeechRate(newRate, reason)
            if (success) {
                notifyRateControlEvent(RateControlEvent(
                    type = RateControlEventType.RATE_ADJUSTED,
                    message = "Speech rate adjusted by $adjustment",
                    oldRate = currentRate - adjustment,
                    newRate = currentRate,
                    reason = reason
                ))
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust speech rate", e)
            false
        }
    }
    
    /**
     * Increases the speech rate
     */
    suspend fun increaseRate(step: Float = RATE_ADJUSTMENT_STEP): Boolean = withContext(Dispatchers.IO) {
        adjustSpeechRate(step, RateChangeReason.USER_ADJUSTMENT)
    }
    
    /**
     * Decreases the speech rate
     */
    suspend fun decreaseRate(step: Float = RATE_ADJUSTMENT_STEP): Boolean = withContext(Dispatchers.IO) {
        adjustSpeechRate(-step, RateChangeReason.USER_ADJUSTMENT)
    }
    
    /**
     * Resets the speech rate to default
     */
    suspend fun resetRate(): Boolean = withContext(Dispatchers.IO) {
        setSpeechRate(DEFAULT_SPEECH_RATE, RateChangeReason.USER_ADJUSTMENT)
    }
    
    /**
     * Locks the current speech rate
     */
    fun lockRate(): Boolean {
        if (isRateLocked) {
            Log.w(TAG, "Rate is already locked")
            return false
        }
        
        isRateLocked = true
        
        notifyRateControlEvent(RateControlEvent(
            type = RateControlEventType.RATE_LOCKED,
            message = "Speech rate locked at $currentRate"
        ))
        
        Log.d(TAG, "Speech rate locked at: $currentRate")
        return true
    }
    
    /**
     * Unlocks the speech rate
     */
    fun unlockRate(): Boolean {
        if (!isRateLocked) {
            Log.w(TAG, "Rate is not locked")
            return false
        }
        
        isRateLocked = false
        
        notifyRateControlEvent(RateControlEvent(
            type = RateControlEventType.RATE_UNLOCKED,
            message = "Speech rate unlocked"
        ))
        
        Log.d(TAG, "Speech rate unlocked")
        return true
    }
    
    /**
     * Sets the rate adjustment mode
     */
    fun setRateAdjustmentMode(mode: RateAdjustmentMode) {
        rateAdjustmentMode = mode
        
        when (mode) {
            RateAdjustmentMode.ADAPTIVE -> {
                enableAdaptiveMode()
            }
            else -> {
                disableAdaptiveMode()
            }
        }
        
        Log.d(TAG, "Rate adjustment mode set to: $mode")
    }
    
    /**
     * Enables adaptive rate mode
     */
    fun enableAdaptiveMode() {
        if (isAdaptiveMode) {
            Log.w(TAG, "Adaptive mode is already enabled")
            return
        }
        
        isAdaptiveMode = true
        
        notifyRateControlEvent(RateControlEvent(
            type = RateControlEventType.ADAPTIVE_MODE_ENABLED,
            message = "Adaptive rate mode enabled"
        ))
        
        Log.d(TAG, "Adaptive rate mode enabled")
    }
    
    /**
     * Disables adaptive rate mode
     */
    fun disableAdaptiveMode() {
        if (!isAdaptiveMode) {
            Log.w(TAG, "Adaptive mode is not enabled")
            return
        }
        
        isAdaptiveMode = false
        
        notifyRateControlEvent(RateControlEvent(
            type = RateControlEventType.ADAPTIVE_MODE_DISABLED,
            message = "Adaptive rate mode disabled"
        ))
        
        Log.d(TAG, "Adaptive rate mode disabled")
    }
    
    /**
     * Loads a rate profile
     */
    suspend fun loadRateProfile(profileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val profile = rateProfiles[profileId]
            if (profile == null) {
                Log.e(TAG, "Rate profile not found: $profileId")
                return@withContext false
            }
            
            if (!profile.isEnabled) {
                Log.w(TAG, "Rate profile is disabled: $profileId")
                return@withContext false
            }
            
            val success = setSpeechRate(profile.baseRate, RateChangeReason.PROFILE_SWITCH)
            if (success) {
                notifyRateControlEvent(RateControlEvent(
                    type = RateControlEventType.PROFILE_LOADED,
                    message = "Rate profile loaded: ${profile.name}",
                    newRate = profile.baseRate,
                    context = profile.context
                ))
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rate profile", e)
            false
        }
    }
    
    /**
     * Saves a rate profile
     */
    suspend fun saveRateProfile(profile: RateProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            rateProfiles[profile.id] = profile
            
            notifyRateControlEvent(RateControlEvent(
                type = RateControlEventType.PROFILE_SAVED,
                message = "Rate profile saved: ${profile.name}",
                context = profile.context
            ))
            
            Log.d(TAG, "Rate profile saved: ${profile.name}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save rate profile", e)
            false
        }
    }
    
    /**
     * Deletes a rate profile
     */
    suspend fun deleteRateProfile(profileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val profile = rateProfiles.remove(profileId)
            if (profile == null) {
                Log.e(TAG, "Rate profile not found: $profileId")
                return@withContext false
            }
            
            notifyRateControlEvent(RateControlEvent(
                type = RateControlEventType.PROFILE_DELETED,
                message = "Rate profile deleted: ${profile.name}",
                context = profile.context
            ))
            
            Log.d(TAG, "Rate profile deleted: ${profile.name}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete rate profile", e)
            false
        }
    }
    
    /**
     * Gets available rate profiles
     */
    fun getRateProfiles(): List<RateProfile> {
        return rateProfiles.values.toList()
    }
    
    /**
     * Gets rate profiles for a specific context
     */
    fun getRateProfilesForContext(context: RateContext): List<RateProfile> {
        return rateProfiles.values.filter { it.context == context && it.isEnabled }
    }
    
    /**
     * Records a rate change
     */
    private fun recordRateChange(rate: Float, reason: RateChangeReason) {
        val entry = RateEntry(
            rate = rate,
            context = getCurrentContext(),
            reason = reason
        )
        
        rateHistory.add(entry)
        
        // Limit history size
        if (rateHistory.size > 1000) {
            rateHistory.removeAt(0)
        }
    }
    
    /**
     * Gets the current context
     */
    private fun getCurrentContext(): RateContext {
        // This would be determined based on current app state, user preferences, etc.
        return RateContext.GENERAL
    }
    
    /**
     * Loads default rate profiles
     */
    private fun loadDefaultRateProfiles() {
        val defaultProfiles = listOf(
            RateProfile(
                name = "Normal",
                description = "Standard speech rate for general use",
                baseRate = 1.0f,
                context = RateContext.GENERAL,
                isDefault = true
            ),
            RateProfile(
                name = "Slow",
                description = "Slower speech rate for better comprehension",
                baseRate = 0.7f,
                context = RateContext.ACCESSIBILITY
            ),
            RateProfile(
                name = "Fast",
                description = "Faster speech rate for quick information",
                baseRate = 1.3f,
                context = RateContext.NEWS
            ),
            RateProfile(
                name = "Conversation",
                description = "Natural speech rate for conversations",
                baseRate = 0.9f,
                context = RateContext.CONVERSATION
            ),
            RateProfile(
                name = "Presentation",
                description = "Clear speech rate for presentations",
                baseRate = 0.8f,
                context = RateContext.PRESENTATION
            ),
            RateProfile(
                name = "Reading",
                description = "Comfortable speech rate for reading",
                baseRate = 0.9f,
                context = RateContext.READING
            ),
            RateProfile(
                name = "Translation",
                description = "Clear speech rate for translations",
                baseRate = 0.8f,
                context = RateContext.TRANSLATION
            ),
            RateProfile(
                name = "Children",
                description = "Slower speech rate for children",
                baseRate = 0.6f,
                context = RateContext.CHILDREN
            ),
            RateProfile(
                name = "Elderly",
                description = "Slower speech rate for elderly users",
                baseRate = 0.7f,
                context = RateContext.ELDERLY
            ),
            RateProfile(
                name = "Professional",
                description = "Professional speech rate",
                baseRate = 1.1f,
                context = RateContext.PROFESSIONAL
            ),
            RateProfile(
                name = "Casual",
                description = "Casual speech rate",
                baseRate = 1.0f,
                context = RateContext.CASUAL
            ),
            RateProfile(
                name = "Emergency",
                description = "Fast speech rate for emergency information",
                baseRate = 1.5f,
                context = RateContext.EMERGENCY
            )
        )
        
        defaultProfiles.forEach { profile ->
            rateProfiles[profile.id] = profile
        }
        
        Log.d(TAG, "Loaded ${defaultProfiles.size} default rate profiles")
    }
    
    /**
     * Starts rate monitoring
     */
    private fun startRateMonitoring() {
        // This would typically run in a background coroutine
        // For now, we'll implement a simple version
        Log.d(TAG, "Rate monitoring started")
    }
    
    /**
     * Processes adaptive rate learning
     */
    private suspend fun processAdaptiveLearning(data: AdaptiveRateData) {
        try {
            if (!isAdaptiveMode) return
            
            adaptiveRateData.add(data)
            
            // Limit adaptive data size
            if (adaptiveRateData.size > ADAPTIVE_RATE_WINDOW_SIZE) {
                adaptiveRateData.removeAt(0)
            }
            
            // Check if we have enough data for adaptation
            if (adaptiveRateData.size < 5) return
            
            // Calculate adaptive adjustment
            val adjustment = calculateAdaptiveAdjustment()
            if (adjustment != 0.0f) {
                val newRate = (currentRate + adjustment).coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
                setSpeechRate(newRate, RateChangeReason.ADAPTIVE_LEARNING)
                
                notifyRateControlEvent(RateControlEvent(
                    type = RateControlEventType.ADAPTIVE_ADJUSTMENT,
                    message = "Adaptive rate adjustment: $adjustment",
                    oldRate = currentRate - adjustment,
                    newRate = newRate
                ))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process adaptive learning", e)
        }
    }
    
    /**
     * Calculates adaptive rate adjustment
     */
    private fun calculateAdaptiveAdjustment(): Float {
        // This is a simplified implementation
        // In a real implementation, you would use machine learning algorithms
        
        val recentData = adaptiveRateData.takeLast(5)
        val avgFeedback = recentData.mapNotNull { it.userFeedback }.map { feedback ->
            when (feedback) {
                UserFeedback.TOO_FAST -> -0.2f
                UserFeedback.TOO_SLOW -> 0.2f
                UserFeedback.SLIGHTLY_FAST -> -0.1f
                UserFeedback.SLIGHTLY_SLOW -> 0.1f
                UserFeedback.PERFECT -> 0.0f
                UserFeedback.UNKNOWN -> 0.0f
            }
        }.average().toFloat()
        
        return avgFeedback * 0.1f // Scale down the adjustment
    }
    
    /**
     * Gets current speech rate
     */
    fun getCurrentRate(): Float = currentRate
    
    /**
     * Gets target speech rate
     */
    fun getTargetRate(): Float = targetRate
    
    /**
     * Gets rate history
     */
    fun getRateHistory(): List<RateEntry> = rateHistory.toList()
    
    /**
     * Gets adaptive rate data
     */
    fun getAdaptiveRateData(): List<AdaptiveRateData> = adaptiveRateData.toList()
    
    /**
     * Gets rate statistics
     */
    fun getRateStats(): Map<String, Any> {
        return mapOf(
            "currentRate" to currentRate,
            "targetRate" to targetRate,
            "isAdaptiveMode" to isAdaptiveMode,
            "isRateLocked" to isRateLocked,
            "adjustmentMode" to rateAdjustmentMode.name,
            "profilesCount" to rateProfiles.size,
            "historySize" to rateHistory.size,
            "adaptiveDataSize" to adaptiveRateData.size
        )
    }
    
    /**
     * Adds a rate control listener
     */
    fun addRateControlListener(listener: RateControlListener) {
        rateControlListeners.add(listener)
    }
    
    /**
     * Removes a rate control listener
     */
    fun removeRateControlListener(listener: RateControlListener) {
        rateControlListeners.remove(listener)
    }
    
    /**
     * Creates a Flow for rate control events
     */
    fun createRateControlEventFlow(): Flow<RateControlEvent> = flow {
        // This would typically emit events as they occur
        // For now, we'll emit the current state
        emit(RateControlEvent(
            type = RateControlEventType.RATE_CHANGED,
            message = "Rate control event flow created"
        ))
    }.flowOn(Dispatchers.Default)
    
    /**
     * Notifies rate control event
     */
    private fun notifyRateControlEvent(event: RateControlEvent) {
        rateControlListeners.forEach { listener ->
            try {
                when (event.type) {
                    RateControlEventType.RATE_CHANGED -> {
                        event.oldRate?.let { oldRate ->
                            event.newRate?.let { newRate ->
                                listener.onRateChanged(oldRate, newRate, event.reason ?: RateChangeReason.USER_ADJUSTMENT)
                            }
                        }
                    }
                    RateControlEventType.RATE_ADJUSTED -> {
                        event.oldRate?.let { oldRate ->
                            event.newRate?.let { newRate ->
                                listener.onRateAdjusted(newRate - oldRate, newRate)
                            }
                        }
                    }
                    RateControlEventType.RATE_LOCKED -> {
                        listener.onRateLocked(true)
                    }
                    RateControlEventType.RATE_UNLOCKED -> {
                        listener.onRateLocked(false)
                    }
                    RateControlEventType.PROFILE_LOADED -> {
                        // This would need to be implemented based on loaded profile
                    }
                    RateControlEventType.PROFILE_SAVED -> {
                        // This would need to be implemented based on saved profile
                    }
                    RateControlEventType.PROFILE_DELETED -> {
                        // This would need to be implemented based on deleted profile
                    }
                    RateControlEventType.ADAPTIVE_MODE_ENABLED -> {
                        listener.onAdaptiveModeChanged(true)
                    }
                    RateControlEventType.ADAPTIVE_MODE_DISABLED -> {
                        listener.onAdaptiveModeChanged(false)
                    }
                    RateControlEventType.ADAPTIVE_LEARNING -> {
                        // This would need to be implemented based on learning data
                    }
                    RateControlEventType.ADAPTIVE_ADJUSTMENT -> {
                        event.oldRate?.let { oldRate ->
                            event.newRate?.let { newRate ->
                                listener.onAdaptiveAdjustment(newRate - oldRate, newRate)
                            }
                        }
                    }
                    RateControlEventType.CONTEXT_CHANGED -> {
                        // This would need to be implemented based on context change
                    }
                    RateControlEventType.PERFORMANCE_UPDATED -> {
                        // This would need to be implemented based on performance metrics
                    }
                    RateControlEventType.FEEDBACK_RECEIVED -> {
                        // This would need to be implemented based on feedback
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying rate control event", e)
            }
        }
    }
    
    /**
     * Cleans up the speech rate controller
     */
    fun cleanup() {
        try {
            ttsService.cleanup()
            rateProfiles.clear()
            rateHistory.clear()
            adaptiveRateData.clear()
            rateControlListeners.clear()
            
            Log.d(TAG, "Speech rate controller cleaned up")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up speech rate controller", e)
        }
    }
}

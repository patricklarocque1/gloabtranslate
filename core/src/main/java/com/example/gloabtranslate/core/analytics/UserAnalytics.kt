package com.example.gloabtranslate.core.analytics

import android.content.Context
import android.content.SharedPreferences
import com.example.gloabtranslate.core.logging.StructuredLogger
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-compliant user analytics system
 * Tracks user behavior and app usage patterns while respecting privacy
 */
class UserAnalytics private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "UserAnalytics"
        private const val PREFS_NAME = "user_analytics"
        private const val ANALYTICS_FILE = "analytics_data.json"
        private const val MAX_ANALYTICS_ENTRIES = 10000
        private const val ANALYTICS_RETENTION_DAYS = 30
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
        
        @Volatile
        private var INSTANCE: UserAnalytics? = null
        
        fun getInstance(context: Context): UserAnalytics {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserAnalytics(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    data class AnalyticsEvent(
        val id: String,
        val timestamp: Long,
        val eventType: EventType,
        val eventName: String,
        val properties: Map<String, Any>,
        val sessionId: String,
        val userId: String? = null
    )
    
    data class UserSession(
        val sessionId: String,
        val startTime: Long,
        val endTime: Long?,
        val duration: Long,
        val events: List<AnalyticsEvent>,
        val screenViews: List<ScreenView>,
        val userActions: List<UserAction>
    )
    
    data class ScreenView(
        val screenName: String,
        val timestamp: Long,
        val duration: Long,
        val properties: Map<String, Any>
    )
    
    data class UserAction(
        val actionName: String,
        val screenName: String,
        val timestamp: Long,
        val properties: Map<String, Any>
    )
    
    data class AppUsageStats(
        val totalSessions: Int,
        val totalDuration: Long,
        val averageSessionDuration: Long,
        val mostUsedFeature: String,
        val totalTranslations: Int,
        val successfulTranslations: Int,
        val failedTranslations: Int,
        val mostTranslatedLanguage: String,
        val averageTranslationTime: Long
    )
    
    enum class EventType {
        SCREEN_VIEW,
        USER_ACTION,
        TRANSLATION,
        ERROR,
        PERFORMANCE,
        FEATURE_USAGE,
        SETTINGS_CHANGE
    }
    
    private val structuredLogger = StructuredLogger.getInstance(context)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val analyticsFile = File(context.filesDir, ANALYTICS_FILE)
    
    // Analytics state
    private val isEnabled = AtomicBoolean(true)
    private val isPrivacyMode = AtomicBoolean(false)
    private val currentSessionId = AtomicLong(0)
    private val currentUserId = AtomicLong(0)
    
    // Data storage
    private val analyticsEvents = ConcurrentHashMap<String, AnalyticsEvent>()
    private val userSessions = mutableListOf<UserSession>()
    private val currentSessionEvents = mutableListOf<AnalyticsEvent>()
    private val currentSessionScreenViews = mutableListOf<ScreenView>()
    private val currentSessionUserActions = mutableListOf<UserAction>()
    
    // Coroutine scope for background operations
    private val analyticsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Load existing analytics data
        analyticsScope.launch {
            loadAnalyticsData()
        }
        
        // Start new session
        startNewSession()
    }
    
    /**
     * Track a screen view
     */
    fun trackScreenView(
        screenName: String,
        properties: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get() || isPrivacyMode.get()) return
        
        try {
            val screenView = ScreenView(
                screenName = screenName,
                timestamp = System.currentTimeMillis(),
                duration = 0L, // Will be updated when screen is closed
                properties = properties
            )
            
            currentSessionScreenViews.add(screenView)
            
            val event = AnalyticsEvent(
                id = generateEventId(),
                timestamp = screenView.timestamp,
                eventType = EventType.SCREEN_VIEW,
                eventName = "screen_view",
                properties = properties + mapOf("screen_name" to screenName),
                sessionId = getCurrentSessionId(),
                userId = getCurrentUserId()
            )
            
            trackEvent(event)
            
            structuredLogger.logUserAction("screen_view", screenName, properties)
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error tracking screen view", throwable = e)
        }
    }
    
    /**
     * Track a user action
     */
    fun trackUserAction(
        actionName: String,
        screenName: String,
        properties: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get() || isPrivacyMode.get()) return
        
        try {
            val userAction = UserAction(
                actionName = actionName,
                screenName = screenName,
                timestamp = System.currentTimeMillis(),
                properties = properties
            )
            
            currentSessionUserActions.add(userAction)
            
            val event = AnalyticsEvent(
                id = generateEventId(),
                timestamp = userAction.timestamp,
                eventType = EventType.USER_ACTION,
                eventName = "user_action",
                properties = properties + mapOf(
                    "action_name" to actionName,
                    "screen_name" to screenName
                ),
                sessionId = getCurrentSessionId(),
                userId = getCurrentUserId()
            )
            
            trackEvent(event)
            
            structuredLogger.logUserAction(actionName, screenName, properties)
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error tracking user action", throwable = e)
        }
    }
    
    /**
     * Track a translation event
     */
    fun trackTranslation(
        sourceLanguage: String,
        targetLanguage: String,
        textLength: Int,
        isOnDevice: Boolean,
        success: Boolean,
        durationMs: Long,
        properties: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get() || isPrivacyMode.get()) return
        
        try {
            val translationProperties = properties + mapOf(
                "source_language" to sourceLanguage,
                "target_language" to targetLanguage,
                "text_length" to textLength,
                "is_on_device" to isOnDevice,
                "success" to success,
                "duration_ms" to durationMs
            )
            
            val event = AnalyticsEvent(
                id = generateEventId(),
                timestamp = System.currentTimeMillis(),
                eventType = EventType.TRANSLATION,
                eventName = "translation",
                properties = translationProperties,
                sessionId = getCurrentSessionId(),
                userId = getCurrentUserId()
            )
            
            trackEvent(event)
            
            structuredLogger.logTranslation(
                sourceLanguage, targetLanguage, textLength,
                isOnDevice, success, durationMs, properties
            )
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error tracking translation", throwable = e)
        }
    }
    
    /**
     * Track feature usage
     */
    fun trackFeatureUsage(
        featureName: String,
        properties: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get() || isPrivacyMode.get()) return
        
        try {
            val event = AnalyticsEvent(
                id = generateEventId(),
                timestamp = System.currentTimeMillis(),
                eventType = EventType.FEATURE_USAGE,
                eventName = "feature_usage",
                properties = properties + mapOf("feature_name" to featureName),
                sessionId = getCurrentSessionId(),
                userId = getCurrentUserId()
            )
            
            trackEvent(event)
            
            structuredLogger.logUserAction("feature_usage", featureName, properties)
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error tracking feature usage", throwable = e)
        }
    }
    
    /**
     * Track settings change
     */
    fun trackSettingsChange(
        settingName: String,
        oldValue: Any,
        newValue: Any,
        properties: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get() || isPrivacyMode.get()) return
        
        try {
            val event = AnalyticsEvent(
                id = generateEventId(),
                timestamp = System.currentTimeMillis(),
                eventType = EventType.SETTINGS_CHANGE,
                eventName = "settings_change",
                properties = properties + mapOf(
                    "setting_name" to settingName,
                    "old_value" to oldValue.toString(),
                    "new_value" to newValue.toString()
                ),
                sessionId = getCurrentSessionId(),
                userId = getCurrentUserId()
            )
            
            trackEvent(event)
            
            structuredLogger.logUserAction("settings_change", settingName, properties)
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error tracking settings change", throwable = e)
        }
    }
    
    /**
     * Track error event
     */
    fun trackError(
        errorType: String,
        errorMessage: String,
        properties: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get() || isPrivacyMode.get()) return
        
        try {
            val event = AnalyticsEvent(
                id = generateEventId(),
                timestamp = System.currentTimeMillis(),
                eventType = EventType.ERROR,
                eventName = "error",
                properties = properties + mapOf(
                    "error_type" to errorType,
                    "error_message" to errorMessage
                ),
                sessionId = getCurrentSessionId(),
                userId = getCurrentUserId()
            )
            
            trackEvent(event)
            
            structuredLogger.e(TAG, "Error tracked: $errorType", properties)
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error tracking error event", throwable = e)
        }
    }
    
    /**
     * Get app usage statistics
     */
    fun getAppUsageStats(): AppUsageStats {
        val allEvents = analyticsEvents.values.toList()
        val translationEvents = allEvents.filter { it.eventType == EventType.TRANSLATION }
        val successfulTranslations = translationEvents.count { it.properties["success"] == true }
        val failedTranslations = translationEvents.count { it.properties["success"] == false }
        
        val totalSessions = userSessions.size
        val totalDuration = userSessions.sumOf { it.duration }
        val averageSessionDuration = if (totalSessions > 0) totalDuration / totalSessions else 0L
        
        val mostUsedFeature = getMostUsedFeature()
        val mostTranslatedLanguage = getMostTranslatedLanguage()
        val averageTranslationTime = getAverageTranslationTime()
        
        return AppUsageStats(
            totalSessions = totalSessions,
            totalDuration = totalDuration,
            averageSessionDuration = averageSessionDuration,
            mostUsedFeature = mostUsedFeature,
            totalTranslations = translationEvents.size,
            successfulTranslations = successfulTranslations,
            failedTranslations = failedTranslations,
            mostTranslatedLanguage = mostTranslatedLanguage,
            averageTranslationTime = averageTranslationTime
        )
    }
    
    /**
     * Get analytics events for time range
     */
    fun getEventsForTimeRange(startTime: Long, endTime: Long): List<AnalyticsEvent> {
        return analyticsEvents.values.filter { it.timestamp in startTime..endTime }
    }
    
    /**
     * Get events by type
     */
    fun getEventsByType(eventType: EventType): List<AnalyticsEvent> {
        return analyticsEvents.values.filter { it.eventType == eventType }
    }
    
    /**
     * Get user sessions
     */
    fun getUserSessions(): List<UserSession> {
        return userSessions.toList()
    }
    
    /**
     * Enable/disable analytics
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        prefs.edit().putBoolean("analytics_enabled", enabled).apply()
    }
    
    /**
     * Enable/disable privacy mode
     */
    fun setPrivacyMode(enabled: Boolean) {
        isPrivacyMode.set(enabled)
        prefs.edit().putBoolean("privacy_mode", enabled).apply()
        
        if (enabled) {
            // Clear sensitive data when privacy mode is enabled
            clearSensitiveData()
        }
    }
    
    /**
     * Clear all analytics data
     */
    fun clearAnalyticsData() {
        analyticsEvents.clear()
        userSessions.clear()
        currentSessionEvents.clear()
        currentSessionScreenViews.clear()
        currentSessionUserActions.clear()
        
        analyticsScope.launch {
            try {
                if (analyticsFile.exists()) {
                    analyticsFile.delete()
                }
            } catch (e: Exception) {
                structuredLogger.e(TAG, "Error clearing analytics data", throwable = e)
            }
        }
    }
    
    /**
     * Export analytics data
     */
    suspend fun exportAnalyticsData(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonObject = JSONObject().apply {
                put("export_timestamp", System.currentTimeMillis())
                put("total_events", analyticsEvents.size)
                put("total_sessions", userSessions.size)
                put("privacy_mode", isPrivacyMode.get())
                
                // Export events
                val eventsArray = org.json.JSONArray()
                analyticsEvents.values.forEach { event ->
                    val eventJson = JSONObject().apply {
                        put("id", event.id)
                        put("timestamp", event.timestamp)
                        put("event_type", event.eventType.name)
                        put("event_name", event.eventName)
                        put("session_id", event.sessionId)
                        put("user_id", event.userId)
                        
                        val propertiesJson = JSONObject()
                        event.properties.forEach { (key, value) ->
                            propertiesJson.put(key, value.toString())
                        }
                        put("properties", propertiesJson)
                    }
                    eventsArray.put(eventJson)
                }
                put("events", eventsArray)
                
                // Export sessions
                val sessionsArray = org.json.JSONArray()
                userSessions.forEach { session ->
                    val sessionJson = JSONObject().apply {
                        put("session_id", session.sessionId)
                        put("start_time", session.startTime)
                        put("end_time", session.endTime)
                        put("duration", session.duration)
                        put("event_count", session.events.size)
                        put("screen_view_count", session.screenViews.size)
                        put("user_action_count", session.userActions.size)
                    }
                    sessionsArray.put(sessionJson)
                }
                put("sessions", sessionsArray)
            }
            
            outputFile.writeText(jsonObject.toString(2))
            true
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error exporting analytics data", throwable = e)
            false
        }
    }
    
    /**
     * Track an analytics event
     */
    private fun trackEvent(event: AnalyticsEvent) {
        analyticsEvents[event.id] = event
        currentSessionEvents.add(event)
        
        // Save to file asynchronously
        analyticsScope.launch {
            saveAnalyticsData()
        }
        
        // Check if cleanup is needed
        if (analyticsEvents.size > MAX_ANALYTICS_ENTRIES) {
            analyticsScope.launch {
                cleanupOldData()
            }
        }
    }
    
    /**
     * Start a new session
     */
    private fun startNewSession() {
        val sessionId = generateSessionId()
        currentSessionId.set(sessionId)
        
        // End previous session if exists
        endCurrentSession()
        
        // Start new session
        val startTime = System.currentTimeMillis()
        
        structuredLogger.i(TAG, "Started new session: $sessionId")
    }
    
    /**
     * End current session
     */
    private fun endCurrentSession() {
        val sessionId = getCurrentSessionId()
        if (sessionId.isEmpty()) return
        
        val endTime = System.currentTimeMillis()
        val startTime = currentSessionEvents.firstOrNull()?.timestamp ?: endTime
        val duration = endTime - startTime
        
        val session = UserSession(
            sessionId = sessionId.toString(),
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            events = currentSessionEvents.toList(),
            screenViews = currentSessionScreenViews.toList(),
            userActions = currentSessionUserActions.toList()
        )
        
        userSessions.add(session)
        
        // Clear current session data
        currentSessionEvents.clear()
        currentSessionScreenViews.clear()
        currentSessionUserActions.clear()
        
        structuredLogger.i(TAG, "Ended session: $sessionId, duration: ${duration}ms")
    }
    
    /**
     * Get current session ID
     */
    private fun getCurrentSessionId(): String {
        return currentSessionId.get().toString()
    }
    
    /**
     * Get current user ID
     */
    private fun getCurrentUserId(): String? {
        return if (isPrivacyMode.get()) null else currentUserId.get().toString()
    }
    
    /**
     * Generate unique event ID
     */
    private fun generateEventId(): String {
        return "event_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}"
    }
    
    /**
     * Generate unique session ID
     */
    private fun generateSessionId(): Long {
        return System.currentTimeMillis()
    }
    
    /**
     * Get most used feature
     */
    private fun getMostUsedFeature(): String {
        val featureUsage = analyticsEvents.values
            .filter { it.eventType == EventType.FEATURE_USAGE }
            .groupBy { it.properties["feature_name"] as? String ?: "unknown" }
            .mapValues { it.value.size }
        
        return featureUsage.maxByOrNull { it.value }?.key ?: "unknown"
    }
    
    /**
     * Get most translated language
     */
    private fun getMostTranslatedLanguage(): String {
        val languageUsage = analyticsEvents.values
            .filter { it.eventType == EventType.TRANSLATION }
            .groupBy { it.properties["target_language"] as? String ?: "unknown" }
            .mapValues { it.value.size }
        
        return languageUsage.maxByOrNull { it.value }?.key ?: "unknown"
    }
    
    /**
     * Get average translation time
     */
    private fun getAverageTranslationTime(): Long {
        val translationTimes = analyticsEvents.values
            .filter { it.eventType == EventType.TRANSLATION }
            .mapNotNull { it.properties["duration_ms"] as? Long }
        
        return if (translationTimes.isNotEmpty()) {
            translationTimes.average().toLong()
        } else 0L
    }
    
    /**
     * Clear sensitive data
     */
    private fun clearSensitiveData() {
        // Remove user IDs from events
        analyticsEvents.values.forEach { event ->
            if (event.userId != null) {
                val updatedEvent = event.copy(userId = null)
                analyticsEvents[event.id] = updatedEvent
            }
        }
    }
    
    /**
     * Save analytics data to file
     */
    private suspend fun saveAnalyticsData() = withContext(Dispatchers.IO) {
        try {
            val jsonObject = JSONObject().apply {
                put("last_updated", System.currentTimeMillis())
                put("total_events", analyticsEvents.size)
                put("total_sessions", userSessions.size)
                
                // Export recent events (last 1000)
                val recentEvents = analyticsEvents.values
                    .sortedByDescending { it.timestamp }
                    .take(1000)
                
                val eventsArray = org.json.JSONArray()
                recentEvents.forEach { event ->
                    val eventJson = JSONObject().apply {
                        put("id", event.id)
                        put("timestamp", event.timestamp)
                        put("event_type", event.eventType.name)
                        put("event_name", event.eventName)
                        put("session_id", event.sessionId)
                        put("user_id", event.userId)
                        
                        val propertiesJson = JSONObject()
                        event.properties.forEach { (key, value) ->
                            propertiesJson.put(key, value.toString())
                        }
                        put("properties", propertiesJson)
                    }
                    eventsArray.put(eventJson)
                }
                put("events", eventsArray)
            }
            
            analyticsFile.writeText(jsonObject.toString(2))
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error saving analytics data", throwable = e)
        }
    }
    
    /**
     * Load analytics data from file
     */
    private suspend fun loadAnalyticsData() = withContext(Dispatchers.IO) {
        try {
            if (!analyticsFile.exists()) return@withContext
            
            val jsonString = analyticsFile.readText()
            val jsonObject = JSONObject(jsonString)
            
            // Load events
            val eventsArray = jsonObject.optJSONArray("events")
            eventsArray?.let { array ->
                for (i in 0 until array.length()) {
                    val eventJson = array.getJSONObject(i)
                    val event = AnalyticsEvent(
                        id = eventJson.getString("id"),
                        timestamp = eventJson.getLong("timestamp"),
                        eventType = EventType.valueOf(eventJson.getString("event_type")),
                        eventName = eventJson.getString("event_name"),
                        properties = emptyMap(), // Simplified for loading
                        sessionId = eventJson.getString("session_id"),
                        userId = eventJson.optString("user_id").takeIf { it.isNotEmpty() }
                    )
                    analyticsEvents[event.id] = event
                }
            }
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error loading analytics data", throwable = e)
        }
    }
    
    /**
     * Clean up old data
     */
    private suspend fun cleanupOldData() = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (ANALYTICS_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
            
            // Remove old events
            analyticsEvents.entries.removeAll { it.value.timestamp < cutoffTime }
            
            // Remove old sessions
            userSessions.removeAll { it.startTime < cutoffTime }
            
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error cleaning up old data", throwable = e)
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        endCurrentSession()
        analyticsScope.cancel()
    }
}

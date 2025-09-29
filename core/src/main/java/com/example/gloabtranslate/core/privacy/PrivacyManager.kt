package com.example.gloabtranslate.core.privacy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy manager for handling privacy policy compliance, user consent management,
 * data collection transparency, and privacy-related user preferences. Provides
 * comprehensive privacy controls and compliance tracking.
 */
class PrivacyManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "PrivacyManager"
        private const val PREFS_NAME = "privacy_preferences"
        private const val VERSION_KEY = "privacy_version"
        private const val CURRENT_VERSION = 1
        
        // Privacy preference keys
        private const val KEY_PRIVACY_POLICY_VERSION = "privacy_policy_version"
        private const val KEY_USER_CONSENT = "user_consent"
        private const val KEY_DATA_COLLECTION_SETTINGS = "data_collection_settings"
        private const val KEY_ANALYTICS_CONSENT = "analytics_consent"
        private const val KEY_CRASH_REPORTING_CONSENT = "crash_reporting_consent"
        private const val KEY_PERMISSION_RATIONALE_CONSENT = "permission_rationale_consent"
        private const val KEY_PRIVACY_NOTIFICATIONS = "privacy_notifications"
        private const val KEY_DATA_RETENTION_SETTINGS = "data_retention_settings"
        private const val KEY_LAST_PRIVACY_REVIEW = "last_privacy_review"
        private const val KEY_PRIVACY_EVENTS = "privacy_events"
        
        // Privacy policy versions
        private const val CURRENT_PRIVACY_POLICY_VERSION = "1.0"
        
        @Volatile
        private var INSTANCE: PrivacyManager? = null
        
        fun getInstance(context: Context): PrivacyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrivacyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val privacyMutex = Mutex()
    private val privacyEvents = CopyOnWriteArrayList<PrivacyEvent>()
    private val consentSettings = ConcurrentHashMap<String, ConsentSetting>()
    private val dataCollectionSettings = ConcurrentHashMap<String, DataCollectionSetting>()
    private val privacyListeners = CopyOnWriteArrayList<PrivacyListener>()
    
    // State flows for reactive updates
    private val _consentFlow = MutableStateFlow<UserConsent?>(null)
    val consentFlow: Flow<UserConsent?> = _consentFlow.asStateFlow()
    
    private val _privacyEventsFlow = MutableStateFlow<List<PrivacyEvent>>(emptyList())
    val privacyEventsFlow: Flow<List<PrivacyEvent>> = _privacyEventsFlow.asStateFlow()
    
    private val _dataCollectionFlow = MutableStateFlow<Map<String, DataCollectionSetting>>(emptyMap())
    val dataCollectionFlow: Flow<Map<String, DataCollectionSetting>> = _dataCollectionFlow.asStateFlow()
    
    /**
     * User consent data structure
     */
    @Serializable
    data class UserConsent(
        val privacyPolicyVersion: String,
        val consentTimestamp: Long = System.currentTimeMillis(),
        val consentGiven: Boolean = false,
        val analyticsConsent: Boolean = false,
        val crashReportingConsent: Boolean = false,
        val permissionRationaleConsent: Boolean = true, // Default to true for essential functionality
        val dataCollectionConsent: Boolean = false,
        val marketingConsent: Boolean = false,
        val thirdPartySharingConsent: Boolean = false,
        val userAgeVerified: Boolean = false,
        val consentMethod: String = "unknown", // "dialog", "settings", "implicit", etc.
        val ipAddress: String? = null, // For legal compliance
        val userAgent: String? = null,
        val locale: String? = null
    )
    
    /**
     * Data collection setting
     */
    @Serializable
    data class DataCollectionSetting(
        val dataType: String,
        val purpose: String,
        val isEnabled: Boolean = false,
        val retentionPeriod: Long = 365L * 24 * 60 * 60 * 1000L, // 1 year in milliseconds
        val lastModified: Long = System.currentTimeMillis(),
        val legalBasis: String = "consent",
        val description: String = ""
    )
    
    /**
     * Consent setting for specific features
     */
    @Serializable
    data class ConsentSetting(
        val feature: String,
        val consentGiven: Boolean = false,
        val consentTimestamp: Long = System.currentTimeMillis(),
        val consentVersion: String = CURRENT_PRIVACY_POLICY_VERSION,
        val withdrawalTimestamp: Long? = null,
        val consentMethod: String = "unknown"
    )
    
    /**
     * Privacy event for tracking and compliance
     */
    @Serializable
    data class PrivacyEvent(
        val eventType: String, // "consent_given", "consent_withdrawn", "data_collected", etc.
        val timestamp: Long = System.currentTimeMillis(),
        val details: String = "",
        val userId: String? = null,
        val sessionId: String? = null,
        val dataTypes: List<String> = emptyList(),
        val purpose: String = "",
        val legalBasis: String = "consent"
    )
    
    /**
     * Privacy listener interface
     */
    interface PrivacyListener {
        fun onConsentChanged(consent: UserConsent)
        fun onDataCollectionChanged(dataType: String, enabled: Boolean)
        fun onPrivacyEvent(event: PrivacyEvent)
        fun onPrivacyPolicyUpdated(newVersion: String)
    }
    
    /**
     * Initialize the privacy manager
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            privacyMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "PrivacyManager already initialized")
                    return@withContext true
                }
                
                // Load privacy settings
                loadConsentSettings()
                loadDataCollectionSettings()
                loadPrivacyEvents()
                
                // Check privacy policy version
                checkPrivacyPolicyVersion()
                
                // Setup default data collection settings
                setupDefaultDataCollectionSettings()
                
                // Update flows
                updateFlows()
                
                Log.d(TAG, "PrivacyManager initialized successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PrivacyManager", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Check if user has given consent for the current privacy policy
     */
    suspend fun hasUserConsent(): Boolean = withContext(Dispatchers.IO) {
        try {
            privacyMutex.withLock {
                val consentJson = sharedPreferences.getString(KEY_USER_CONSENT, null)
                if (consentJson != null) {
                    val consent = json.decodeFromString<UserConsent>(consentJson)
                    consent.consentGiven && consent.privacyPolicyVersion == CURRENT_PRIVACY_POLICY_VERSION
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check user consent", e)
            false
        }
    }
    
    /**
     * Record user consent
     */
    suspend fun recordUserConsent(
        consent: UserConsent,
        consentMethod: String = "dialog"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            privacyMutex.withLock {
                val updatedConsent = consent.copy(
                    consentMethod = consentMethod,
                    consentTimestamp = System.currentTimeMillis(),
                    privacyPolicyVersion = CURRENT_PRIVACY_POLICY_VERSION
                )
                
                val consentJson = json.encodeToString(updatedConsent)
                sharedPreferences.edit()
                    .putString(KEY_USER_CONSENT, consentJson)
                    .putString(KEY_PRIVACY_POLICY_VERSION, CURRENT_PRIVACY_POLICY_VERSION)
                    .putLong(KEY_LAST_PRIVACY_REVIEW, System.currentTimeMillis())
                    .apply()
                
                _consentFlow.value = updatedConsent
                
                // Record privacy event
                recordPrivacyEvent(
                    eventType = "consent_given",
                    details = "User gave consent via $consentMethod",
                    purpose = "privacy_compliance"
                )
                
                // Notify listeners
                notifyConsentChanged(updatedConsent)
                
                Log.d(TAG, "User consent recorded successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record user consent", e)
            false
        }
    }
    
    /**
     * Withdraw user consent
     */
    suspend fun withdrawUserConsent(reason: String = "user_request"): Boolean = withContext(Dispatchers.IO) {
        try {
            privacyMutex.withLock {
                val consentJson = sharedPreferences.getString(KEY_USER_CONSENT, null)
                if (consentJson != null) {
                    val consent = json.decodeFromString<UserConsent>(consentJson)
                    val withdrawnConsent = consent.copy(
                        consentGiven = false,
                        consentTimestamp = System.currentTimeMillis()
                    )
                    
                    val updatedConsentJson = json.encodeToString(withdrawnConsent)
                    sharedPreferences.edit()
                        .putString(KEY_USER_CONSENT, updatedConsentJson)
                        .apply()
                    
                    _consentFlow.value = withdrawnConsent
                    
                    // Record privacy event
                    recordPrivacyEvent(
                        eventType = "consent_withdrawn",
                        details = "User withdrew consent. Reason: $reason",
                        purpose = "privacy_compliance"
                    )
                    
                    // Notify listeners
                    notifyConsentChanged(withdrawnConsent)
                    
                    Log.d(TAG, "User consent withdrawn successfully")
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to withdraw user consent", e)
            false
        }
    }
    
    /**
     * Update data collection setting
     */
    suspend fun updateDataCollectionSetting(
        dataType: String,
        enabled: Boolean,
        purpose: String = "",
        retentionPeriod: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            privacyMutex.withLock {
                val currentSetting = dataCollectionSettings[dataType]
                val updatedSetting = currentSetting?.copy(
                    isEnabled = enabled,
                    purpose = purpose,
                    retentionPeriod = retentionPeriod ?: currentSetting.retentionPeriod,
                    lastModified = System.currentTimeMillis()
                ) ?: DataCollectionSetting(
                    dataType = dataType,
                    purpose = purpose,
                    isEnabled = enabled,
                    retentionPeriod = retentionPeriod ?: 365L * 24 * 60 * 60 * 1000L
                )
                
                dataCollectionSettings[dataType] = updatedSetting
                saveDataCollectionSettings()
                updateFlows()
                
                // Record privacy event
                recordPrivacyEvent(
                    eventType = "data_collection_setting_changed",
                    details = "Data collection for $dataType ${if (enabled) "enabled" else "disabled"}",
                    dataTypes = listOf(dataType),
                    purpose = purpose
                )
                
                // Notify listeners
                notifyDataCollectionChanged(dataType, enabled)
                
                Log.d(TAG, "Data collection setting updated: $dataType = $enabled")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update data collection setting: $dataType", e)
            false
        }
    }
    
    /**
     * Check if data collection is enabled for a specific type
     */
    suspend fun isDataCollectionEnabled(dataType: String): Boolean = withContext(Dispatchers.IO) {
        try {
            privacyMutex.withLock {
                dataCollectionSettings[dataType]?.isEnabled ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check data collection setting: $dataType", e)
            false
        }
    }
    
    /**
     * Record privacy event for compliance tracking
     */
    suspend fun recordPrivacyEvent(
        eventType: String,
        details: String = "",
        dataTypes: List<String> = emptyList(),
        purpose: String = "",
        legalBasis: String = "consent"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            privacyMutex.withLock {
                val event = PrivacyEvent(
                    eventType = eventType,
                    timestamp = System.currentTimeMillis(),
                    details = details,
                    dataTypes = dataTypes,
                    purpose = purpose,
                    legalBasis = legalBasis
                )
                
                privacyEvents.add(event)
                
                // Keep only last 1000 events
                if (privacyEvents.size > 1000) {
                    privacyEvents.removeAt(0)
                }
                
                savePrivacyEvents()
                updateFlows()
                
                // Notify listeners
                notifyPrivacyEvent(event)
                
                Log.d(TAG, "Privacy event recorded: $eventType")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record privacy event: $eventType", e)
            false
        }
    }
    
    /**
     * Get privacy compliance report
     */
    suspend fun getPrivacyComplianceReport(): PrivacyComplianceReport = withContext(Dispatchers.IO) {
        try {
            privacyMutex.withLock {
                val consent = _consentFlow.value
                val totalEvents = privacyEvents.size
                val consentEvents = privacyEvents.count { it.eventType == "consent_given" }
                val withdrawalEvents = privacyEvents.count { it.eventType == "consent_withdrawn" }
                val dataCollectionEvents = privacyEvents.count { it.eventType.contains("data_collection") }
                
                val enabledDataTypes = dataCollectionSettings.values.count { it.isEnabled }
                val totalDataTypes = dataCollectionSettings.size
                
                PrivacyComplianceReport(
                    hasConsent = consent?.consentGiven ?: false,
                    consentVersion = consent?.privacyPolicyVersion ?: "none",
                    consentTimestamp = consent?.consentTimestamp ?: 0,
                    totalPrivacyEvents = totalEvents,
                    consentEvents = consentEvents,
                    withdrawalEvents = withdrawalEvents,
                    dataCollectionEvents = dataCollectionEvents,
                    enabledDataTypes = enabledDataTypes,
                    totalDataTypes = totalDataTypes,
                    lastPrivacyReview = sharedPreferences.getLong(KEY_LAST_PRIVACY_REVIEW, 0),
                    reportTimestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate privacy compliance report", e)
            PrivacyComplianceReport()
        }
    }
    
    /**
     * Get privacy policy information
     */
    fun getPrivacyPolicyInfo(): PrivacyPolicyInfo {
        return PrivacyPolicyInfo(
            version = CURRENT_PRIVACY_POLICY_VERSION,
            lastUpdated = System.currentTimeMillis(),
            effectiveDate = System.currentTimeMillis(),
            url = "https://example.com/privacy-policy", // Replace with actual URL
            summary = "This privacy policy explains how GloabTranslate collects, uses, and protects your data.",
            keyPoints = listOf(
                "Audio data is processed locally when possible",
                "No personal information is shared with third parties",
                "You can withdraw consent at any time",
                "Data is retained only as long as necessary",
                "All communications are encrypted"
            )
        )
    }
    
    /**
     * Privacy policy information
     */
    data class PrivacyPolicyInfo(
        val version: String,
        val lastUpdated: Long,
        val effectiveDate: Long,
        val url: String,
        val summary: String,
        val keyPoints: List<String>
    )
    
    /**
     * Privacy compliance report
     */
    data class PrivacyComplianceReport(
        val hasConsent: Boolean = false,
        val consentVersion: String = "none",
        val consentTimestamp: Long = 0,
        val totalPrivacyEvents: Int = 0,
        val consentEvents: Int = 0,
        val withdrawalEvents: Int = 0,
        val dataCollectionEvents: Int = 0,
        val enabledDataTypes: Int = 0,
        val totalDataTypes: Int = 0,
        val lastPrivacyReview: Long = 0,
        val reportTimestamp: Long = System.currentTimeMillis()
    )
    
    // Private helper methods
    
    private fun loadConsentSettings() {
        try {
            val consentJson = sharedPreferences.getString(KEY_USER_CONSENT, null)
            if (consentJson != null) {
                val consent = json.decodeFromString<UserConsent>(consentJson)
                _consentFlow.value = consent
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load consent settings", e)
        }
    }
    
    private fun loadDataCollectionSettings() {
        try {
            val settingsJson = sharedPreferences.getString(KEY_DATA_COLLECTION_SETTINGS, null)
            if (settingsJson != null) {
                val settings = json.decodeFromString<Map<String, DataCollectionSetting>>(settingsJson)
                dataCollectionSettings.clear()
                dataCollectionSettings.putAll(settings)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load data collection settings", e)
        }
    }
    
    private fun saveDataCollectionSettings() {
        try {
            val settingsJson = json.encodeToString(dataCollectionSettings.toMap())
            sharedPreferences.edit()
                .putString(KEY_DATA_COLLECTION_SETTINGS, settingsJson)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save data collection settings", e)
        }
    }
    
    private fun loadPrivacyEvents() {
        try {
            val eventsJson = sharedPreferences.getString(KEY_PRIVACY_EVENTS, null)
            if (eventsJson != null) {
                val events = json.decodeFromString<List<PrivacyEvent>>(eventsJson)
                privacyEvents.clear()
                privacyEvents.addAll(events)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load privacy events", e)
        }
    }
    
    private fun savePrivacyEvents() {
        try {
            val eventsJson = json.encodeToString(privacyEvents.toList())
            sharedPreferences.edit()
                .putString(KEY_PRIVACY_EVENTS, eventsJson)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save privacy events", e)
        }
    }
    
    private fun checkPrivacyPolicyVersion() {
        val savedVersion = sharedPreferences.getString(KEY_PRIVACY_POLICY_VERSION, null)
        if (savedVersion != CURRENT_PRIVACY_POLICY_VERSION) {
            Log.d(TAG, "Privacy policy version changed from $savedVersion to $CURRENT_PRIVACY_POLICY_VERSION")
            // Notify listeners about policy update
            notifyPrivacyPolicyUpdated(CURRENT_PRIVACY_POLICY_VERSION)
        }
    }
    
    private fun setupDefaultDataCollectionSettings() {
        if (dataCollectionSettings.isEmpty()) {
            // Setup default data collection settings
            val defaultSettings = mapOf(
                "audio_data" to DataCollectionSetting(
                    dataType = "audio_data",
                    purpose = "speech recognition and translation",
                    isEnabled = false,
                    retentionPeriod = 0L, // No retention for audio
                    description = "Temporary audio processing for translation"
                ),
                "translation_history" to DataCollectionSetting(
                    dataType = "translation_history",
                    purpose = "improving translation accuracy",
                    isEnabled = false,
                    retentionPeriod = 30L * 24 * 60 * 60 * 1000L, // 30 days
                    description = "Translation history for service improvement"
                ),
                "usage_analytics" to DataCollectionSetting(
                    dataType = "usage_analytics",
                    purpose = "app performance and feature usage analysis",
                    isEnabled = false,
                    retentionPeriod = 90L * 24 * 60 * 60 * 1000L, // 90 days
                    description = "Anonymous usage statistics"
                ),
                "crash_reports" to DataCollectionSetting(
                    dataType = "crash_reports",
                    purpose = "app stability and bug fixes",
                    isEnabled = false,
                    retentionPeriod = 365L * 24 * 60 * 60 * 1000L, // 1 year
                    description = "Crash and error reports for debugging"
                )
            )
            
            dataCollectionSettings.putAll(defaultSettings)
            saveDataCollectionSettings()
        }
    }
    
    private fun updateFlows() {
        _privacyEventsFlow.value = privacyEvents.toList()
        _dataCollectionFlow.value = dataCollectionSettings.toMap()
    }
    
    // Listener management
    
    fun addPrivacyListener(listener: PrivacyListener) {
        privacyListeners.add(listener)
    }
    
    fun removePrivacyListener(listener: PrivacyListener) {
        privacyListeners.remove(listener)
    }
    
    private fun notifyConsentChanged(consent: UserConsent) {
        privacyListeners.forEach { listener ->
            try {
                listener.onConsentChanged(consent)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying consent changed", e)
            }
        }
    }
    
    private fun notifyDataCollectionChanged(dataType: String, enabled: Boolean) {
        privacyListeners.forEach { listener ->
            try {
                listener.onDataCollectionChanged(dataType, enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying data collection changed", e)
            }
        }
    }
    
    private fun notifyPrivacyEvent(event: PrivacyEvent) {
        privacyListeners.forEach { listener ->
            try {
                listener.onPrivacyEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying privacy event", e)
            }
        }
    }
    
    private fun notifyPrivacyPolicyUpdated(newVersion: String) {
        privacyListeners.forEach { listener ->
            try {
                listener.onPrivacyPolicyUpdated(newVersion)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying privacy policy updated", e)
            }
        }
    }
}

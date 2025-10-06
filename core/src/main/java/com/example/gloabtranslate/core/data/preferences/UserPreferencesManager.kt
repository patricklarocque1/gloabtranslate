package com.example.gloabtranslate.core.data.preferences

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.example.gloabtranslate.core.utils.Logger

/**
 * User preferences manager for handling application settings and user preferences.
 * Provides type-safe preference management, validation, reactive updates, backup/restore,
 * and migration capabilities.
 */
class UserPreferencesManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "UserPreferencesManager"
        private const val PREFS_NAME = "user_preferences"
        private const val BACKUP_DIR = "preferences_backups"
        private const val MAX_BACKUP_FILES = 10
        private const val VERSION_KEY = "preferences_version"
        private const val CURRENT_VERSION = 1
        
        @Volatile
        private var INSTANCE: UserPreferencesManager? = null
        
        fun getInstance(context: Context): UserPreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferencesManager(context.applicationContext).also { INSTANCE = it }
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

    // Sanitizes arbitrary values so they can be safely serialized without requiring a kotlinx serializer
    private fun sanitizeForStorage(value: Any?): Any? = when (value) {
        null -> null
        is String, is Int, is Long, is Float, is Double, is Boolean -> value
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to sanitizeForStorage(v) }
        is Iterable<*> -> value.map { sanitizeForStorage(it) }
        is Array<*> -> value.map { sanitizeForStorage(it) }
        else -> value.toString() // Fallback to string representation
    }
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val preferencesMutex = Mutex()
    private val preferencesCache = ConcurrentHashMap<String, Any>()
    private val changeListeners = CopyOnWriteArrayList<PreferenceChangeListener>()
    private val validationRules = ConcurrentHashMap<String, ValidationRule>()
    
    // Statistics
    private val preferenceChanges = AtomicLong(0)
    private val lastSaveTime = AtomicLong(0)
    
    // State flows for reactive updates
    private val _preferencesFlow = MutableStateFlow<Map<String, Any>>(emptyMap())
    val preferencesFlow: Flow<Map<String, Any>> = _preferencesFlow.asStateFlow()
    
    private val _preferenceChangeFlow = MutableStateFlow<PreferenceChangeEvent?>(null)
    val preferenceChangeFlow: Flow<PreferenceChangeEvent?> = _preferenceChangeFlow.asStateFlow()
    
    /**
     * User preferences data structure
     */
    @Serializable
    data class UserPreferences(
        // General preferences
        val theme: String = "system", // system, light, dark
        val language: String = "en",
        val region: String = "US",
        val autoSave: Boolean = true,
        val enableAnalytics: Boolean = true,
        val enableCrashReporting: Boolean = true,
        
        // Translation preferences
        val defaultSourceLanguage: String = "auto",
        val defaultTargetLanguage: String = "en",
        val enableAutoTranslation: Boolean = false,
        val enablePartialResults: Boolean = true,
        val translationTimeout: Long = 10000L,
        val confidenceThreshold: Float = 0.7f,
        val enableOnDeviceTranslation: Boolean = true,
        val enableCloudTranslation: Boolean = true,
        val preferredTranslationEngine: String = "auto", // auto, on_device, cloud
        
        // Audio preferences
        val enableVoiceRecognition: Boolean = true,
        val enableAudioRecording: Boolean = true,
        val audioQuality: String = "high", // low, medium, high
        val sampleRate: Int = 16000,
        val enableNoiseReduction: Boolean = true,
        val enableEchoCancellation: Boolean = true,
        val audioBufferSize: Int = 1024,
        val enableContinuousRecording: Boolean = false,
        
        // Speech recognition preferences
        val enableSpeechRecognition: Boolean = true,
        val speechRecognitionLanguage: String = "en-US",
        val enablePartialSpeechResults: Boolean = true,
        val speechRecognitionTimeout: Long = 30000L,
        val enableVoiceActivityDetection: Boolean = true,
        val speechConfidenceThreshold: Float = 0.6f,
        
        // Text-to-Speech preferences
        val enableTextToSpeech: Boolean = true,
        val ttsLanguage: String = "en-US",
        val ttsVoice: String = "default",
        val ttsSpeed: Float = 1.0f,
        val ttsPitch: Float = 1.0f,
        val enableTtsAutoPlay: Boolean = false,
        
        // UI preferences
        val fontSize: String = "medium", // small, medium, large, extra_large
        val enableAnimations: Boolean = true,
        val enableHapticFeedback: Boolean = true,
        val enableSoundEffects: Boolean = true,
        val showTranslationConfidence: Boolean = true,
        val showSourceLanguage: Boolean = true,
        val enableDarkMode: Boolean = false,
        val enableCompactMode: Boolean = false,
        
        // Privacy and security preferences
        val enableHistory: Boolean = true,
        val enableHistorySync: Boolean = false,
        val enableDataCollection: Boolean = false,
        val enablePersonalizedAds: Boolean = false,
        val enableLocationServices: Boolean = false,
        val dataRetentionDays: Int = 30,
        val enableBiometricAuth: Boolean = false,
        
        // Performance preferences
        val enableBackgroundProcessing: Boolean = true,
        val enableCaching: Boolean = true,
        val cacheSize: Int = 100,
        val enableAutoCleanup: Boolean = true,
        val cleanupIntervalHours: Int = 24,
        val enableBatteryOptimization: Boolean = true,
        
        // Advanced preferences
        val enableDebugMode: Boolean = false,
        val enableVerboseLogging: Boolean = false,
        val enablePerformanceMonitoring: Boolean = false,
        val enableCrashDumps: Boolean = false,
        val logLevel: String = "info", // debug, info, warn, error
        val enableRemoteConfig: Boolean = true,
        
        // Custom preferences (extensible)
        val customPreferences: Map<String, String> = emptyMap(),
        
        // Metadata
        val version: Int = CURRENT_VERSION,
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    /**
     * Preference change event
     */
    data class PreferenceChangeEvent(
        val key: String,
        val oldValue: Any?,
        val newValue: Any?,
        val timestamp: Long = System.currentTimeMillis(),
        val source: ChangeSource = ChangeSource.USER
    )
    
    /**
     * Change source enumeration
     */
    enum class ChangeSource {
        USER,           // Changed by user interaction
        SYSTEM,         // Changed by system/defaults
        MIGRATION,      // Changed during migration
        RESTORE,        // Changed during restore
        REMOTE_CONFIG   // Changed by remote configuration
    }
    
    /**
     * Preference validation rule
     */
    data class ValidationRule(
        val key: String,
        val validator: (Any?) -> Boolean,
        val errorMessage: String,
        val defaultValue: Any? = null
    )
    
    /**
     * Preference change listener interface
     */
    interface PreferenceChangeListener {
        fun onPreferenceChanged(event: PreferenceChangeEvent)
        fun onPreferencesReset()
        fun onPreferencesMigrated(fromVersion: Int, toVersion: Int)
    }
    
    /**
     * Preference category enumeration
     */
    enum class PreferenceCategory {
        GENERAL,
        TRANSLATION,
        AUDIO,
        SPEECH_RECOGNITION,
        TEXT_TO_SPEECH,
        UI,
        PRIVACY,
        PERFORMANCE,
        ADVANCED,
        CUSTOM
    }
    
    /**
     * Initializes the user preferences manager
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.d("Initializing UserPreferencesManager", TAG)
            preferencesMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "UserPreferencesManager already initialized")
                    return@withContext true
                }
                
                // Load preferences from SharedPreferences
                loadPreferencesFromStorage()
                
                // Set up validation rules
                setupValidationRules()
                
                // Check version and migrate if necessary
                val savedVersion = sharedPreferences.getInt(VERSION_KEY, 0)
                if (savedVersion < CURRENT_VERSION) {
                    migratePreferences(savedVersion, CURRENT_VERSION)
                }
                
                // Update flows
                updateFlows()
                
                Log.d(TAG, "UserPreferencesManager initialized successfully")
                Logger.d("UserPreferencesManager initialized successfully", TAG)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize UserPreferencesManager", e)
            isInitialized.set(false)
            Logger.e("Failed to initialize UserPreferencesManager", TAG, e)
            false
        }
    }
    
    /**
     * Gets a preference value by key
     */
    suspend fun getPreference(key: String): Any? = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                preferencesCache[key]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get preference: $key", e)
            null
        }
    }
    
    /**
     * Gets a preference value with default
     */
    suspend fun getPreference(key: String, defaultValue: Any): Any = withContext(Dispatchers.IO) {
        getPreference(key) ?: defaultValue
    }
    
    /**
     * Sets a preference value
     */
    suspend fun setPreference(
        key: String,
        value: Any?,
        source: ChangeSource = ChangeSource.USER
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                val oldValue = preferencesCache[key]
                
                // Validate the new value
                val validationRule = validationRules[key]
                if (validationRule != null && !validationRule.validator(value)) {
                    Log.w(TAG, "Preference validation failed for key: $key")
                    return@withContext false
                }
                
                // Update cache
                if (value == null) {
                    preferencesCache.remove(key)
                } else {
                    preferencesCache[key] = value
                }
                
                // Save to SharedPreferences
                savePreferenceToStorage(key, value)
                
                // Update flows
                updateFlows()
                
                // Notify listeners
                val event = PreferenceChangeEvent(key, oldValue, value, source = source)
                notifyPreferenceChanged(event)
                
                preferenceChanges.incrementAndGet()
                lastSaveTime.set(System.currentTimeMillis())
                
                Log.d(TAG, "Preference updated: $key = $value")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set preference: $key", e)
            false
        }
    }
    
    /**
     * Gets all preferences
     */
    suspend fun getAllPreferences(): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                preferencesCache.toMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all preferences", e)
            emptyMap()
        }
    }
    
    /**
     * Gets preferences by category
     */
    suspend fun getPreferencesByCategory(category: PreferenceCategory): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            val categoryKeys = getKeysForCategory(category)
            preferencesMutex.withLock {
                preferencesCache.filterKeys { it in categoryKeys }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get preferences by category: $category", e)
            emptyMap()
        }
    }
    
    /**
     * Resets preferences to defaults
     */
    suspend fun resetPreferences(category: PreferenceCategory? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                val defaultPreferences = UserPreferences()
                val defaultMap = preferencesToMap(defaultPreferences)
                
                if (category != null) {
                    val categoryKeys = getKeysForCategory(category)
                    categoryKeys.forEach { key ->
                        val defaultValue = defaultMap[key]
                        if (defaultValue != null) {
                            preferencesCache[key] = defaultValue
                            savePreferenceToStorage(key, defaultValue)
                        }
                    }
                } else {
                    // Reset all preferences
                    preferencesCache.clear()
                    preferencesCache.putAll(defaultMap)
                    saveAllPreferencesToStorage()
                }
                
                updateFlows()
                
                // Notify listeners
                changeListeners.forEach { listener ->
                    try {
                        listener.onPreferencesReset()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error notifying preference reset", e)
                    }
                }
                
                Log.d(TAG, "Preferences reset for category: $category")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset preferences", e)
            false
        }
    }
    
    /**
     * Adds a preference change listener
     */
    fun addPreferenceChangeListener(listener: PreferenceChangeListener) {
        changeListeners.add(listener)
    }
    
    /**
     * Removes a preference change listener
     */
    fun removePreferenceChangeListener(listener: PreferenceChangeListener) {
        changeListeners.remove(listener)
    }
    
    /**
     * Creates a backup of current preferences
     */
    suspend fun createBackup(): File? = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                val backupDir = File(context.filesDir, BACKUP_DIR)
                if (!backupDir.exists()) {
                    backupDir.mkdirs()
                }
                
                val timestamp = System.currentTimeMillis()
                val backupFile = File(backupDir, "preferences_backup_$timestamp.json")
                
                val sanitizedPreferences = preferencesCache.mapValues { sanitizeForStorage(it.value) }
                val backupData = mapOf(
                    "version" to CURRENT_VERSION,
                    "timestamp" to timestamp,
                    "preferences" to sanitizedPreferences,
                    "metadata" to mapOf(
                        "app_version" to getAppVersion(),
                        "device_model" to getDeviceModel(),
                        "total_preferences" to sanitizedPreferences.size
                    )
                )
                
                backupFile.writeText(json.encodeToString(backupData))
                
                // Clean up old backups
                cleanupOldBackups(backupDir)
                
                Log.d(TAG, "Preferences backup created: ${backupFile.absolutePath}")
                backupFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create preferences backup", e)
            null
        }
    }
    
    /**
     * Restores preferences from backup
     */
    suspend fun restoreFromBackup(backupFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                if (!backupFile.exists()) {
                    Log.e(TAG, "Backup file does not exist: ${backupFile.absolutePath}")
                    return@withContext false
                }
                
                val jsonString = backupFile.readText()
                val rootElement: JsonElement = json.parseToJsonElement(jsonString)
                val rootObj = rootElement as? JsonObject ?: run {
                    Log.e(TAG, "Invalid backup root object")
                    return@withContext false
                }
                val preferencesElement = rootObj["preferences"]
                val preferencesObj = preferencesElement as? JsonObject ?: run {
                    Log.e(TAG, "Invalid backup preferences object")
                    return@withContext false
                }
                val preferences: Map<String, Any?> = preferencesObj.mapValues { (_, je) ->
                    when (je) {
                        is JsonPrimitive -> {
                            val text = je.content
                            // Try to coerce into boolean/int/long/double in that order
                            when {
                                text.equals("true", ignoreCase = true) -> true
                                text.equals("false", ignoreCase = true) -> false
                                text.toLongOrNull() != null -> {
                                    val l = text.toLong()
                                    // If fits in Int range, store as Int
                                    if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
                                }
                                text.toDoubleOrNull() != null -> text.toDouble()
                                else -> text
                            }
                        }
                        is JsonObject -> je.toString()
                        else -> je.toString()
                    }
                }
                
                // Clear current preferences
                preferencesCache.clear()
                
                // Restore preferences
                preferences.forEach { (key, value) ->
                    if (value != null) {
                        preferencesCache[key] = value
                        savePreferenceToStorage(key, value)
                    }
                }
                
                updateFlows()
                
                Log.d(TAG, "Preferences restored from backup: ${backupFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore preferences from backup", e)
            false
        }
    }
    
    /**
     * Gets available backup files
     */
    suspend fun getAvailableBackups(): List<File> = withContext(Dispatchers.IO) {
        try {
            val backupDir = File(context.filesDir, BACKUP_DIR)
            if (!backupDir.exists()) {
                return@withContext emptyList()
            }
            
            backupDir.listFiles()
                ?.filter { it.name.startsWith("preferences_backup_") && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available backups", e)
            emptyList()
        }
    }
    
    /**
     * Gets preferences statistics
     */
    fun getPreferencesStatistics(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized.get(),
            "totalPreferences" to preferencesCache.size,
            "preferenceChanges" to preferenceChanges.get(),
            "lastSaveTime" to lastSaveTime.get(),
            "currentVersion" to CURRENT_VERSION,
            "validationRules" to validationRules.size,
            "changeListeners" to changeListeners.size
        )
    }
    
    // Private helper methods
    
    private fun setupValidationRules() {
        // General preferences validation
        validationRules["theme"] = ValidationRule(
            "theme",
            { it in listOf("system", "light", "dark") },
            "Theme must be system, light, or dark",
            "system"
        )
        
        validationRules["fontSize"] = ValidationRule(
            "fontSize",
            { it in listOf("small", "medium", "large", "extra_large") },
            "Font size must be small, medium, large, or extra_large",
            "medium"
        )
        
        // Translation preferences validation
        validationRules["confidenceThreshold"] = ValidationRule(
            "confidenceThreshold",
            { it is Number && (it as Number).toFloat() in 0f..1f },
            "Confidence threshold must be between 0 and 1",
            0.7f
        )
        
        validationRules["translationTimeout"] = ValidationRule(
            "translationTimeout",
            { it is Number && (it as Number).toLong() > 0 },
            "Translation timeout must be positive",
            10000L
        )
        
        // Audio preferences validation
        validationRules["sampleRate"] = ValidationRule(
            "sampleRate",
            { it is Number && (it as Number).toInt() in listOf(8000, 16000, 22050, 44100) },
            "Sample rate must be 8000, 16000, 22050, or 44100",
            16000
        )
        
        validationRules["audioBufferSize"] = ValidationRule(
            "audioBufferSize",
            { it is Number && (it as Number).toInt() > 0 },
            "Audio buffer size must be positive",
            1024
        )
        
        // TTS preferences validation
        validationRules["ttsSpeed"] = ValidationRule(
            "ttsSpeed",
            { it is Number && (it as Number).toFloat() in 0.1f..3.0f },
            "TTS speed must be between 0.1 and 3.0",
            1.0f
        )
        
        validationRules["ttsPitch"] = ValidationRule(
            "ttsPitch",
            { it is Number && (it as Number).toFloat() in 0.1f..2.0f },
            "TTS pitch must be between 0.1 and 2.0",
            1.0f
        )
        
        // Privacy preferences validation
        validationRules["dataRetentionDays"] = ValidationRule(
            "dataRetentionDays",
            { it is Number && (it as Number).toInt() in 1..365 },
            "Data retention days must be between 1 and 365",
            30
        )
        
        // Performance preferences validation
        validationRules["cacheSize"] = ValidationRule(
            "cacheSize",
            { it is Number && (it as Number).toInt() in 10..1000 },
            "Cache size must be between 10 and 1000",
            100
        )
        
        validationRules["cleanupIntervalHours"] = ValidationRule(
            "cleanupIntervalHours",
            { it is Number && (it as Number).toInt() in 1..168 }, // 1 hour to 1 week
            "Cleanup interval must be between 1 and 168 hours",
            24
        )
    }
    
    private fun getKeysForCategory(category: PreferenceCategory): List<String> {
        return when (category) {
            PreferenceCategory.GENERAL -> listOf(
                "theme", "language", "region", "autoSave", "enableAnalytics", "enableCrashReporting"
            )
            PreferenceCategory.TRANSLATION -> listOf(
                "defaultSourceLanguage", "defaultTargetLanguage", "enableAutoTranslation",
                "enablePartialResults", "translationTimeout", "confidenceThreshold",
                "enableOnDeviceTranslation", "enableCloudTranslation", "preferredTranslationEngine"
            )
            PreferenceCategory.AUDIO -> listOf(
                "enableVoiceRecognition", "enableAudioRecording", "audioQuality",
                "sampleRate", "enableNoiseReduction", "enableEchoCancellation",
                "audioBufferSize", "enableContinuousRecording"
            )
            PreferenceCategory.SPEECH_RECOGNITION -> listOf(
                "enableSpeechRecognition", "speechRecognitionLanguage", "enablePartialSpeechResults",
                "speechRecognitionTimeout", "enableVoiceActivityDetection", "speechConfidenceThreshold"
            )
            PreferenceCategory.TEXT_TO_SPEECH -> listOf(
                "enableTextToSpeech", "ttsLanguage", "ttsVoice", "ttsSpeed", "ttsPitch", "enableTtsAutoPlay"
            )
            PreferenceCategory.UI -> listOf(
                "fontSize", "enableAnimations", "enableHapticFeedback", "enableSoundEffects",
                "showTranslationConfidence", "showSourceLanguage", "enableDarkMode", "enableCompactMode"
            )
            PreferenceCategory.PRIVACY -> listOf(
                "enableHistory", "enableHistorySync", "enableDataCollection", "enablePersonalizedAds",
                "enableLocationServices", "dataRetentionDays", "enableBiometricAuth"
            )
            PreferenceCategory.PERFORMANCE -> listOf(
                "enableBackgroundProcessing", "enableCaching", "cacheSize", "enableAutoCleanup",
                "cleanupIntervalHours", "enableBatteryOptimization"
            )
            PreferenceCategory.ADVANCED -> listOf(
                "enableDebugMode", "enableVerboseLogging", "enablePerformanceMonitoring",
                "enableCrashDumps", "logLevel", "enableRemoteConfig"
            )
            PreferenceCategory.CUSTOM -> listOf("customPreferences")
        }
    }
    
    private fun preferencesToMap(preferences: UserPreferences): Map<String, Any> {
        return mapOf(
            "theme" to preferences.theme,
            "language" to preferences.language,
            "region" to preferences.region,
            "autoSave" to preferences.autoSave,
            "enableAnalytics" to preferences.enableAnalytics,
            "enableCrashReporting" to preferences.enableCrashReporting,
            "defaultSourceLanguage" to preferences.defaultSourceLanguage,
            "defaultTargetLanguage" to preferences.defaultTargetLanguage,
            "enableAutoTranslation" to preferences.enableAutoTranslation,
            "enablePartialResults" to preferences.enablePartialResults,
            "translationTimeout" to preferences.translationTimeout,
            "confidenceThreshold" to preferences.confidenceThreshold,
            "enableOnDeviceTranslation" to preferences.enableOnDeviceTranslation,
            "enableCloudTranslation" to preferences.enableCloudTranslation,
            "preferredTranslationEngine" to preferences.preferredTranslationEngine,
            "enableVoiceRecognition" to preferences.enableVoiceRecognition,
            "enableAudioRecording" to preferences.enableAudioRecording,
            "audioQuality" to preferences.audioQuality,
            "sampleRate" to preferences.sampleRate,
            "enableNoiseReduction" to preferences.enableNoiseReduction,
            "enableEchoCancellation" to preferences.enableEchoCancellation,
            "audioBufferSize" to preferences.audioBufferSize,
            "enableContinuousRecording" to preferences.enableContinuousRecording,
            "enableSpeechRecognition" to preferences.enableSpeechRecognition,
            "speechRecognitionLanguage" to preferences.speechRecognitionLanguage,
            "enablePartialSpeechResults" to preferences.enablePartialSpeechResults,
            "speechRecognitionTimeout" to preferences.speechRecognitionTimeout,
            "enableVoiceActivityDetection" to preferences.enableVoiceActivityDetection,
            "speechConfidenceThreshold" to preferences.speechConfidenceThreshold,
            "enableTextToSpeech" to preferences.enableTextToSpeech,
            "ttsLanguage" to preferences.ttsLanguage,
            "ttsVoice" to preferences.ttsVoice,
            "ttsSpeed" to preferences.ttsSpeed,
            "ttsPitch" to preferences.ttsPitch,
            "enableTtsAutoPlay" to preferences.enableTtsAutoPlay,
            "fontSize" to preferences.fontSize,
            "enableAnimations" to preferences.enableAnimations,
            "enableHapticFeedback" to preferences.enableHapticFeedback,
            "enableSoundEffects" to preferences.enableSoundEffects,
            "showTranslationConfidence" to preferences.showTranslationConfidence,
            "showSourceLanguage" to preferences.showSourceLanguage,
            "enableDarkMode" to preferences.enableDarkMode,
            "enableCompactMode" to preferences.enableCompactMode,
            "enableHistory" to preferences.enableHistory,
            "enableHistorySync" to preferences.enableHistorySync,
            "enableDataCollection" to preferences.enableDataCollection,
            "enablePersonalizedAds" to preferences.enablePersonalizedAds,
            "enableLocationServices" to preferences.enableLocationServices,
            "dataRetentionDays" to preferences.dataRetentionDays,
            "enableBiometricAuth" to preferences.enableBiometricAuth,
            "enableBackgroundProcessing" to preferences.enableBackgroundProcessing,
            "enableCaching" to preferences.enableCaching,
            "cacheSize" to preferences.cacheSize,
            "enableAutoCleanup" to preferences.enableAutoCleanup,
            "cleanupIntervalHours" to preferences.cleanupIntervalHours,
            "enableBatteryOptimization" to preferences.enableBatteryOptimization,
            "enableDebugMode" to preferences.enableDebugMode,
            "enableVerboseLogging" to preferences.enableVerboseLogging,
            "enablePerformanceMonitoring" to preferences.enablePerformanceMonitoring,
            "enableCrashDumps" to preferences.enableCrashDumps,
            "logLevel" to preferences.logLevel,
            "enableRemoteConfig" to preferences.enableRemoteConfig,
            "customPreferences" to preferences.customPreferences
        )
    }
    
    private suspend fun loadPreferencesFromStorage() {
        try {
            val defaultPreferences = UserPreferences()
            val defaultMap = preferencesToMap(defaultPreferences)
            
            // Load from SharedPreferences
            sharedPreferences.all.forEach { (key, value) ->
                preferencesCache[key] = value ?: ""
            }
            
            // Set defaults for missing preferences
            defaultMap.forEach { (key, defaultValue) ->
                if (!preferencesCache.containsKey(key)) {
                    preferencesCache[key] = defaultValue
                }
            }
            
            Log.d(TAG, "Loaded ${preferencesCache.size} preferences from storage")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preferences from storage", e)
        }
    }
    
    private fun savePreferenceToStorage(key: String, value: Any?) {
        try {
            val editor = sharedPreferences.edit()
            
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> editor.remove(key)
                else -> {
                    // For complex types, attempt JSON serialization; fallback to toString
                    val stored = try {
                        json.encodeToString(value)
                    } catch (ser: Exception) {
                        Log.w(TAG, "Non-serializable preference value for $key (${value::class.java.simpleName}); storing toString()", ser)
                        value.toString()
                    }
                    editor.putString(key, stored)
                }
            }
            
            editor.apply()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save preference to storage: $key", e)
        }
    }
    
    private fun saveAllPreferencesToStorage() {
        try {
            val editor = sharedPreferences.edit()
            editor.clear()
            
            preferencesCache.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> {
                        val stored = try {
                            json.encodeToString(value)
                        } catch (ser: Exception) {
                            Log.w(TAG, "Non-serializable preference value for $key (${value::class.java.simpleName}); storing toString()", ser)
                            value.toString()
                        }
                        editor.putString(key, stored)
                    }
                }
            }
            
            editor.putInt(VERSION_KEY, CURRENT_VERSION)
            editor.apply()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save all preferences to storage", e)
        }
    }
    
    private suspend fun migratePreferences(fromVersion: Int, toVersion: Int) {
        try {
            Log.d(TAG, "Migrating preferences from version $fromVersion to $toVersion")
            
            when (fromVersion) {
                0 -> {
                    // Initial migration - no special handling needed
                    Log.d(TAG, "Initial migration completed")
                }
                // Add future migration logic here
                else -> {
                    Log.d(TAG, "No migration needed from version $fromVersion")
                }
            }
            
            // Update version
            sharedPreferences.edit().putInt(VERSION_KEY, toVersion).apply()
            
            // Notify listeners
            changeListeners.forEach { listener ->
                try {
                    listener.onPreferencesMigrated(fromVersion, toVersion)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying preference migration", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Preference migration failed", e)
        }
    }
    
    private fun updateFlows() {
        _preferencesFlow.value = preferencesCache.toMap()
    }
    
    private fun notifyPreferenceChanged(event: PreferenceChangeEvent) {
        _preferenceChangeFlow.value = event
        
        changeListeners.forEach { listener ->
            try {
                listener.onPreferenceChanged(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying preference change", e)
            }
        }
    }
    
    private fun cleanupOldBackups(backupDir: File) {
        try {
            val backupFiles = backupDir.listFiles()
                ?.filter { it.name.startsWith("preferences_backup_") && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            
            if (backupFiles.size > MAX_BACKUP_FILES) {
                val filesToDelete = backupFiles.drop(MAX_BACKUP_FILES)
                filesToDelete.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old backup: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old backups", e)
        }
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun getDeviceModel(): String {
        return try {
            android.os.Build.MODEL ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Gets the SharedPreferences instance for listener registration
     */
    fun getSharedPreferences(): SharedPreferences {
        return sharedPreferences
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        try {
            preferencesCache.clear()
            validationRules.clear()
            changeListeners.clear()
            _preferencesFlow.value = emptyMap()
            _preferenceChangeFlow.value = null
            isInitialized.set(false)
            
            Log.d(TAG, "UserPreferencesManager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

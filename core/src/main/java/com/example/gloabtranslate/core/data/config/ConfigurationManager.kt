package com.example.gloabtranslate.core.data.config

import android.content.Context
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Bridges user preferences to strongly-typed runtime configuration objects that update reactively.
 */
class ConfigurationManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ConfigurationManager? = null

        fun getInstance(context: Context): ConfigurationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigurationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val preferencesManager = UserPreferencesManager.getInstance(context.applicationContext)
    private val preferencesFlow = preferencesManager.preferencesFlow

    val themeConfig: Flow<ThemeConfig> = preferencesFlow
        .map { prefs ->
            ThemeConfig(
                theme = prefs.getStringValue("theme", "system"),
                enableDarkMode = prefs.getBooleanValue("enableDarkMode", default = false)
            )
        }
        .distinctUntilChanged()

    val translationConfig: Flow<TranslationConfig> = preferencesFlow
        .map { prefs ->
            TranslationConfig(
                defaultSourceLanguage = prefs.getStringValue("defaultSourceLanguage", "auto"),
                defaultTargetLanguage = prefs.getStringValue("defaultTargetLanguage", "en"),
                enableAutoTranslation = prefs.getBooleanValue("enableAutoTranslation", default = false),
                confidenceThreshold = prefs.getFloatValue("confidenceThreshold", 0.7f),
                enableOnDeviceTranslation = prefs.getBooleanValue("enableOnDeviceTranslation", default = true),
                enableCloudTranslation = prefs.getBooleanValue("enableCloudTranslation", default = true),
                preferredTranslationEngine = prefs.getStringValue("preferredTranslationEngine", "auto"),
                enableHistory = prefs.getBooleanValue("enableHistory", default = true),
                dataRetentionDays = prefs.getIntValue("dataRetentionDays", 30)
            )
        }
        .distinctUntilChanged()

    val audioConfig: Flow<AudioConfig> = preferencesFlow
        .map { prefs ->
            AudioConfig(
                enableVoiceRecognition = prefs.getBooleanValue("enableVoiceRecognition", default = true),
                enableAudioRecording = prefs.getBooleanValue("enableAudioRecording", default = true),
                audioQuality = prefs.getStringValue("audioQuality", "high"),
                sampleRate = prefs.getIntValue("sampleRate", 16000),
                enableNoiseReduction = prefs.getBooleanValue("enableNoiseReduction", default = true),
                enableEchoCancellation = prefs.getBooleanValue("enableEchoCancellation", default = true),
                audioBufferSize = prefs.getIntValue("audioBufferSize", 1024),
                enableContinuousRecording = prefs.getBooleanValue("enableContinuousRecording", default = false)
            )
        }
        .distinctUntilChanged()

    val uiConfig: Flow<UiConfig> = preferencesFlow
        .map { prefs ->
            UiConfig(
                fontSize = prefs.getStringValue("fontSize", "medium"),
                enableAnimations = prefs.getBooleanValue("enableAnimations", default = true),
                enableHapticFeedback = prefs.getBooleanValue("enableHapticFeedback", default = true),
                enableSoundEffects = prefs.getBooleanValue("enableSoundEffects", default = true),
                showTranslationConfidence = prefs.getBooleanValue("showTranslationConfidence", default = true),
                showSourceLanguage = prefs.getBooleanValue("showSourceLanguage", default = true),
                enableCompactMode = prefs.getBooleanValue("enableCompactMode", default = false)
            )
        }
        .distinctUntilChanged()

    val privacyConfig: Flow<PrivacyConfig> = preferencesFlow
        .map { prefs ->
            PrivacyConfig(
                enableHistory = prefs.getBooleanValue("enableHistory", default = true),
                enableHistorySync = prefs.getBooleanValue("enableHistorySync", default = false),
                enableDataCollection = prefs.getBooleanValue("enableDataCollection", default = false),
                enablePersonalizedAds = prefs.getBooleanValue("enablePersonalizedAds", default = false),
                enableLocationServices = prefs.getBooleanValue("enableLocationServices", default = false),
                enableBiometricAuth = prefs.getBooleanValue("enableBiometricAuth", default = false)
            )
        }
        .distinctUntilChanged()

    val performanceConfig: Flow<PerformanceConfig> = preferencesFlow
        .map { prefs ->
            PerformanceConfig(
                enableBackgroundProcessing = prefs.getBooleanValue("enableBackgroundProcessing", default = true),
                enableCaching = prefs.getBooleanValue("enableCaching", default = true),
                cacheSize = prefs.getIntValue("cacheSize", 100),
                enableAutoCleanup = prefs.getBooleanValue("enableAutoCleanup", default = true),
                cleanupIntervalHours = prefs.getIntValue("cleanupIntervalHours", 24)
            )
        }
        .distinctUntilChanged()

    val debugConfig: Flow<DebugConfig> = preferencesFlow
        .map { prefs ->
            DebugConfig(
                enableDebugMode = prefs.getBooleanValue("enableDebugMode", default = false),
                enableVerboseLogging = prefs.getBooleanValue("enableVerboseLogging", default = false),
                logLevel = prefs.getStringValue("logLevel", "info"),
                enablePerformanceMonitoring = prefs.getBooleanValue("enablePerformanceMonitoring", default = false),
                enableCrashDumps = prefs.getBooleanValue("enableCrashDumps", default = false)
            )
        }
        .distinctUntilChanged()

    suspend fun currentThemeConfig(): ThemeConfig {
        ensurePreferencesInitialized()
        return themeConfig.first()
    }

    suspend fun currentTranslationConfig(): TranslationConfig {
        ensurePreferencesInitialized()
        return translationConfig.first()
    }

    suspend fun currentAudioConfig(): AudioConfig {
        ensurePreferencesInitialized()
        return audioConfig.first()
    }

    suspend fun currentUiConfig(): UiConfig {
        ensurePreferencesInitialized()
        return uiConfig.first()
    }

    suspend fun currentPrivacyConfig(): PrivacyConfig {
        ensurePreferencesInitialized()
        return privacyConfig.first()
    }

    suspend fun currentPerformanceConfig(): PerformanceConfig {
        ensurePreferencesInitialized()
        return performanceConfig.first()
    }

    suspend fun currentDebugConfig(): DebugConfig {
        ensurePreferencesInitialized()
        return debugConfig.first()
    }

    private suspend fun ensurePreferencesInitialized() {
        preferencesManager.initialize()
    }
}

private fun Map<String, Any>.getStringValue(key: String, default: String): String {
    val raw = this[key] ?: return default
    return when (raw) {
        is String -> raw
        is Enum<*> -> raw.name
        is Number -> raw.toString()
        else -> default
    }
}

private fun Map<String, Any>.getBooleanValue(key: String, default: Boolean): Boolean {
    val raw = this[key] ?: return default
    return when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> raw.toBooleanLenient() ?: default
        else -> default
    }
}

private fun Map<String, Any>.getIntValue(key: String, default: Int): Int {
    val raw = this[key] ?: return default
    return when (raw) {
        is Int -> raw
        is Long -> raw.toInt()
        is Float -> raw.toInt()
        is Double -> raw.toInt()
        is Number -> raw.toInt()
        is String -> raw.toDoubleOrNull()?.toInt() ?: default
        else -> default
    }
}

private fun Map<String, Any>.getFloatValue(key: String, default: Float): Float {
    val raw = this[key] ?: return default
    return when (raw) {
        is Float -> raw
        is Double -> raw.toFloat()
        is Int -> raw.toFloat()
        is Long -> raw.toFloat()
        is Number -> raw.toFloat()
        is String -> raw.toFloatOrNull() ?: default
        else -> default
    }
}

private fun String.toBooleanLenient(): Boolean? {
    return when (lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
}

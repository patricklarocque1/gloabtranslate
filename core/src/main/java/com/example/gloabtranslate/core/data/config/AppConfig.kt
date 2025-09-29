package com.example.gloabtranslate.core.data.config

/**
 * Strongly-typed runtime configuration objects derived from user preferences.
 */
data class ThemeConfig(
    val theme: String,
    val enableDarkMode: Boolean
)

data class TranslationConfig(
    val defaultSourceLanguage: String,
    val defaultTargetLanguage: String,
    val enableAutoTranslation: Boolean,
    val confidenceThreshold: Float,
    val enableOnDeviceTranslation: Boolean,
    val enableCloudTranslation: Boolean,
    val preferredTranslationEngine: String,
    val enableHistory: Boolean,
    val dataRetentionDays: Int
)

data class AudioConfig(
    val enableVoiceRecognition: Boolean,
    val enableAudioRecording: Boolean,
    val audioQuality: String,
    val sampleRate: Int,
    val enableNoiseReduction: Boolean,
    val enableEchoCancellation: Boolean,
    val audioBufferSize: Int,
    val enableContinuousRecording: Boolean
)

data class UiConfig(
    val fontSize: String,
    val enableAnimations: Boolean,
    val enableHapticFeedback: Boolean,
    val enableSoundEffects: Boolean,
    val showTranslationConfidence: Boolean,
    val showSourceLanguage: Boolean,
    val enableCompactMode: Boolean
)

data class PrivacyConfig(
    val enableHistory: Boolean,
    val enableHistorySync: Boolean,
    val enableDataCollection: Boolean,
    val enablePersonalizedAds: Boolean,
    val enableLocationServices: Boolean,
    val enableBiometricAuth: Boolean
)

data class PerformanceConfig(
    val enableBackgroundProcessing: Boolean,
    val enableCaching: Boolean,
    val cacheSize: Int,
    val enableAutoCleanup: Boolean,
    val cleanupIntervalHours: Int
)

data class DebugConfig(
    val enableDebugMode: Boolean,
    val enableVerboseLogging: Boolean,
    val logLevel: String,
    val enablePerformanceMonitoring: Boolean,
    val enableCrashDumps: Boolean
)

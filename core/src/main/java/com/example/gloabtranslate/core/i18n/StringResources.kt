package com.example.gloabtranslate.core.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.*

/**
 * Centralized string resource management for multi-language support
 * Provides dynamic string loading and locale management
 */
class StringResources private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "StringResources"
        private const val DEFAULT_LOCALE = "en"
        
        @Volatile
        private var INSTANCE: StringResources? = null
        
        fun getInstance(context: Context): StringResources {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StringResources(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val resources: Resources = context.resources
    private val supportedLocales = setOf(
        "en", "es", "fr", "de", "it", "pt", "ru", "zh", "ja", "ko", "ar", "hi"
    )
    
    /**
     * Get string resource with current locale
     */
    fun getString(resId: Int, vararg formatArgs: Any): String {
        return try {
            resources.getString(resId, *formatArgs)
        } catch (e: Resources.NotFoundException) {
            getFallbackString(resId, *formatArgs)
        }
    }
    
    /**
     * Get string by key (for core module compatibility)
     */
    fun getString(key: String, vararg formatArgs: Any): String {
        return try {
            val resId = resources.getIdentifier(key, "string", context.packageName)
            if (resId != 0) {
                resources.getString(resId, *formatArgs)
            } else {
                // Fallback to key if resource not found
                key.replace("_", " ").replaceFirstChar { it.uppercase() }
            }
        } catch (e: Exception) {
            key.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
    }
    
    /**
     * Get string resource for specific locale
     */
    fun getStringForLocale(locale: Locale, resId: Int, vararg formatArgs: Any): String {
        return try {
            val config = Configuration(resources.configuration)
            config.setLocale(locale)
            val localizedResources = context.createConfigurationContext(config).resources
            localizedResources.getString(resId, *formatArgs)
        } catch (e: Exception) {
            getFallbackString(resId, *formatArgs)
        }
    }
    
    /**
     * Get current locale
     */
    fun getCurrentLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }
    }
    
    /**
     * Check if locale is supported
     */
    fun isLocaleSupported(locale: Locale): Boolean {
        return supportedLocales.contains(locale.language)
    }
    
    /**
     * Get supported locales
     */
    fun getSupportedLocales(): List<Locale> {
        return supportedLocales.map { Locale.forLanguageTag(it) }
    }
    
    /**
     * Get locale display name
     */
    fun getLocaleDisplayName(locale: Locale): String {
        return locale.getDisplayName(getCurrentLocale())
    }
    
    /**
     * Get fallback string when localized version is not available
     */
    private fun getFallbackString(resId: Int, vararg formatArgs: Any): String {
        return try {
            val config = Configuration(resources.configuration)
            config.setLocale(Locale.forLanguageTag(DEFAULT_LOCALE))
            val fallbackResources = context.createConfigurationContext(config).resources
            fallbackResources.getString(resId, *formatArgs)
        } catch (e: Exception) {
            "String not found"
        }
    }
    
    /**
     * Get language name by code
     */
    fun getLanguageName(languageCode: String): String {
        return when (languageCode) {
            "en" -> "English"
            "es" -> "Español"
            "fr" -> "Français"
            "de" -> "Deutsch"
            "it" -> "Italiano"
            "pt" -> "Português"
            "ru" -> "Русский"
            "zh" -> "中文"
            "ja" -> "日本語"
            "ko" -> "한국어"
            "ar" -> "العربية"
            "hi" -> "हिन्दी"
            else -> languageCode.uppercase()
        }
    }
    
    /**
     * Get RTL languages
     */
    fun getRTLLanguages(): Set<String> {
        return setOf("ar", "he", "fa", "ur")
    }
    
    /**
     * Check if language is RTL
     */
    fun isRTLLanguage(languageCode: String): Boolean {
        return getRTLLanguages().contains(languageCode)
    }
}

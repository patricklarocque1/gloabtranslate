package com.example.gloabtranslate.ui.i18n

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.gloabtranslate.R
import com.example.gloabtranslate.core.i18n.RTLSupport
import com.example.gloabtranslate.core.i18n.StringResources
import java.util.*

/**
 * Manages language-specific UI layouts and RTL support
 * Handles dynamic layout switching based on language selection
 */
class LanguageLayoutManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LanguageLayoutManager"
        
        @Volatile
        private var INSTANCE: LanguageLayoutManager? = null
        
        fun getInstance(context: Context): LanguageLayoutManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LanguageLayoutManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val stringResources = StringResources.getInstance(context)
    private val rtlSupport = RTLSupport.getInstance(context)
    
    /**
     * Apply language-specific layout to view
     */
    fun applyLanguageLayout(view: View, languageCode: String? = null) {
        val targetLanguage = languageCode ?: stringResources.getCurrentLocale().language
        val isRTL = rtlSupport.isRTLLanguage(targetLanguage)
        
        // Apply RTL direction
        rtlSupport.applyRTLDirection(view, targetLanguage)
        
        // Apply to all child views
        if (view is ViewGroup) {
            applyLanguageLayoutToChildren(view, targetLanguage)
        }
        
        // Apply text direction for TextViews
        if (view is TextView) {
            rtlSupport.applyRTLTextDirection(view, targetLanguage)
        }
    }
    
    /**
     * Apply language layout to all children
     */
    private fun applyLanguageLayoutToChildren(viewGroup: ViewGroup, languageCode: String) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            applyLanguageLayout(child, languageCode)
        }
    }
    
    /**
     * Get language-specific layout resource
     */
    fun getLanguageLayout(layoutId: Int, languageCode: String? = null): Int {
        val targetLanguage = languageCode ?: stringResources.getCurrentLocale().language
        val isRTL = rtlSupport.isRTLLanguage(targetLanguage)
        
        return when {
            isRTL -> getRTLLayout(layoutId)
            else -> layoutId
        }
    }
    
    /**
     * Get RTL-specific layout resource
     */
    private fun getRTLLayout(layoutId: Int): Int {
        // For RTL support, we use the same layout files but they are in layout-rtl directory
        // Android automatically selects the correct layout based on locale
        return layoutId
    }
    
    /**
     * Get language-specific drawable resource
     */
    fun getLanguageDrawable(normalDrawable: Int, languageCode: String? = null): Int {
        val targetLanguage = languageCode ?: stringResources.getCurrentLocale().language
        val isRTL = rtlSupport.isRTLLanguage(targetLanguage)
        
        return if (isRTL) {
            getRTLDrawable(normalDrawable)
        } else {
            normalDrawable
        }
    }
    
    /**
     * Get RTL-specific drawable resource
     */
    private fun getRTLDrawable(normalDrawable: Int): Int {
        return when (normalDrawable) {
            com.example.gloabtranslate.R.drawable.ic_chevron_right -> 
                com.example.gloabtranslate.R.drawable.ic_chevron_left
            com.example.gloabtranslate.R.drawable.ic_translation_arrow -> 
                com.example.gloabtranslate.R.drawable.ic_translation_arrow_rtl
            else -> normalDrawable
        }
    }
    
    /**
     * Get language-specific gravity
     */
    fun getLanguageGravity(languageCode: String? = null): Int {
        val targetLanguage = languageCode ?: stringResources.getCurrentLocale().language
        val isRTL = rtlSupport.isRTLLanguage(targetLanguage)
        
        return rtlSupport.getRTLAlignment(isRTL)
    }
    
    /**
     * Get language-specific margin
     */
    fun getLanguageMargin(startMargin: Int, endMargin: Int, languageCode: String? = null): Pair<Int, Int> {
        val targetLanguage = languageCode ?: stringResources.getCurrentLocale().language
        val isRTL = rtlSupport.isRTLLanguage(targetLanguage)
        
        return rtlSupport.getRTLMargin(startMargin, endMargin, isRTL)
    }
    
    /**
     * Get language-specific padding
     */
    fun getLanguagePadding(leftPadding: Int, rightPadding: Int, languageCode: String? = null): Pair<Int, Int> {
        val targetLanguage = languageCode ?: stringResources.getCurrentLocale().language
        val isRTL = rtlSupport.isRTLLanguage(targetLanguage)
        
        return rtlSupport.getRTLPadding(leftPadding, rightPadding, isRTL)
    }
    
    /**
     * Format text for language display
     */
    fun formatTextForLanguage(text: String, languageCode: String? = null): String {
        val targetLanguage = languageCode ?: stringResources.getCurrentLocale().language
        return rtlSupport.formatTextForRTL(text, targetLanguage)
    }
    
    /**
     * Check if current language is RTL
     */
    fun isCurrentLanguageRTL(): Boolean {
        return rtlSupport.isRTLLocale()
    }
    
    /**
     * Get supported languages for UI
     */
    fun getSupportedUILanguages(): List<LanguageInfo> {
        return stringResources.getSupportedLocales().map { locale ->
            LanguageInfo(
                code = locale.language,
                name = stringResources.getLanguageName(locale.language),
                displayName = stringResources.getLocaleDisplayName(locale),
                isRTL = rtlSupport.isRTLLanguage(locale.language)
            )
        }
    }
    
    /**
     * Data class for language information
     */
    data class LanguageInfo(
        val code: String,
        val name: String,
        val displayName: String,
        val isRTL: Boolean
    )
}

package com.example.gloabtranslate.core.i18n

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import java.util.*

/**
 * Right-to-Left (RTL) language support utilities
 * Handles RTL layout, text direction, and UI mirroring
 */
class RTLSupport private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "RTLSupport"
        
        @Volatile
        private var INSTANCE: RTLSupport? = null
        
        fun getInstance(context: Context): RTLSupport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RTLSupport(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val stringResources = StringResources.getInstance(context)
    
    /**
     * Check if current locale is RTL
     */
    fun isRTLLocale(): Boolean {
        val locale = stringResources.getCurrentLocale()
        return isRTLLanguage(locale.language)
    }
    
    /**
     * Check if language code is RTL
     */
    fun isRTLLanguage(languageCode: String): Boolean {
        return stringResources.isRTLLanguage(languageCode)
    }
    
    /**
     * Get text direction for language
     */
    fun getTextDirection(languageCode: String): Int {
        return if (isRTLLanguage(languageCode)) {
            View.TEXT_DIRECTION_RTL
        } else {
            View.TEXT_DIRECTION_LTR
        }
    }
    
    /**
     * Get gravity for RTL support
     */
    fun getGravity(isRTL: Boolean): Int {
        return if (isRTL) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            Gravity.START or Gravity.CENTER_VERTICAL
        }
    }
    
    /**
     * Apply RTL layout direction to view
     */
    fun applyRTLDirection(view: View, languageCode: String? = null) {
        val isRTL = languageCode?.let { isRTLLanguage(it) } ?: isRTLLocale()
        ViewCompat.setLayoutDirection(view, if (isRTL) ViewCompat.LAYOUT_DIRECTION_RTL else ViewCompat.LAYOUT_DIRECTION_LTR)
    }
    
    /**
     * Apply RTL text direction to TextView
     */
    fun applyRTLTextDirection(textView: TextView, languageCode: String? = null) {
        val isRTL = languageCode?.let { isRTLLanguage(it) } ?: isRTLLocale()
        textView.textDirection = if (isRTL) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
    }
    
    /**
     * Mirror view for RTL layout
     */
    fun mirrorViewForRTL(view: View, isRTL: Boolean) {
        if (isRTL) {
            view.scaleX = -1f
        } else {
            view.scaleX = 1f
        }
    }
    
    /**
     * Get RTL-aware margin
     */
    fun getRTLMargin(startMargin: Int, endMargin: Int, isRTL: Boolean): Pair<Int, Int> {
        return if (isRTL) {
            Pair(endMargin, startMargin)
        } else {
            Pair(startMargin, endMargin)
        }
    }
    
    /**
     * Get RTL-aware padding
     */
    fun getRTLPadding(leftPadding: Int, rightPadding: Int, isRTL: Boolean): Pair<Int, Int> {
        return if (isRTL) {
            Pair(rightPadding, leftPadding)
        } else {
            Pair(leftPadding, rightPadding)
        }
    }
    
    /**
     * Apply RTL layout to ViewGroup
     */
    fun applyRTLToViewGroup(viewGroup: ViewGroup, languageCode: String? = null) {
        val isRTL = languageCode?.let { isRTLLanguage(it) } ?: isRTLLocale()
        
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            applyRTLDirection(child, languageCode)
            
            if (child is TextView) {
                applyRTLTextDirection(child, languageCode)
            }
            
            if (child is ViewGroup) {
                applyRTLToViewGroup(child, languageCode)
            }
        }
    }
    
    /**
     * Get RTL-aware drawable
     */
    fun getRTLDrawable(normalDrawable: Int, rtlDrawable: Int, languageCode: String? = null): Int {
        val isRTL = languageCode?.let { isRTLLanguage(it) } ?: isRTLLocale()
        return if (isRTL) rtlDrawable else normalDrawable
    }
    
    /**
     * Format text for RTL display
     */
    fun formatTextForRTL(text: String, languageCode: String? = null): String {
        val isRTL = languageCode?.let { isRTLLanguage(it) } ?: isRTLLocale()
        
        if (!isRTL) return text
        
        // Add RTL mark characters for proper text direction
        return "\u200F$text\u200F"
    }
    
    /**
     * Get RTL-aware alignment
     */
    fun getRTLAlignment(isRTL: Boolean): Int {
        return if (isRTL) {
            Gravity.END
        } else {
            Gravity.START
        }
    }
    
    /**
     * Check if text contains RTL characters
     */
    fun containsRTLCharacters(text: String): Boolean {
        val rtlChars = Regex("[\u0590-\u05FF\u0600-\u06FF\u0750-\u077F\uFB1D-\uFDFF\uFE70-\uFEFF]")
        return rtlChars.containsMatchIn(text)
    }
    
    /**
     * Get text direction based on content
     */
    fun getTextDirectionFromContent(text: String): Int {
        return if (containsRTLCharacters(text)) {
            View.TEXT_DIRECTION_RTL
        } else {
            View.TEXT_DIRECTION_LTR
        }
    }
    
    /**
     * Apply RTL layout parameters
     */
    fun applyRTLParams(view: View, isRTL: Boolean) {
        val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        
        if (isRTL) {
            // Swap left and right margins
            val tempMargin = layoutParams.leftMargin
            layoutParams.leftMargin = layoutParams.rightMargin
            layoutParams.rightMargin = tempMargin
        }
        
        view.layoutParams = layoutParams
    }
    
    /**
     * Get RTL-aware bounds
     */
    fun getRTLBounds(originalBounds: Rect, parentWidth: Int, isRTL: Boolean): Rect {
        return if (isRTL) {
            Rect(
                parentWidth - originalBounds.right,
                originalBounds.top,
                parentWidth - originalBounds.left,
                originalBounds.bottom
            )
        } else {
            originalBounds
        }
    }
    
    /**
     * Get RTL languages list
     */
    fun getRTLLanguages(): List<String> {
        return stringResources.getRTLLanguages().toList()
    }
    
    /**
     * Check if current configuration is RTL
     */
    fun isRTLConfiguration(): Boolean {
        val config = context.resources.configuration
        return config.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }
    
    /**
     * Force RTL layout direction
     */
    fun forceRTLDirection(view: View) {
        ViewCompat.setLayoutDirection(view, ViewCompat.LAYOUT_DIRECTION_RTL)
    }
    
    /**
     * Force LTR layout direction
     */
    fun forceLTRDirection(view: View) {
        ViewCompat.setLayoutDirection(view, ViewCompat.LAYOUT_DIRECTION_LTR)
    }
}

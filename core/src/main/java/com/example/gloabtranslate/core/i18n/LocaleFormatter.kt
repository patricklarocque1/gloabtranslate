package com.example.gloabtranslate.core.i18n

import android.content.Context
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Locale-specific formatting utilities
 * Handles date, time, number, and currency formatting based on locale
 */
class LocaleFormatter private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LocaleFormatter"
        
        @Volatile
        private var INSTANCE: LocaleFormatter? = null
        
        fun getInstance(context: Context): LocaleFormatter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocaleFormatter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val stringResources = StringResources.getInstance(context)
    
    /**
     * Format date according to locale
     */
    fun formatDate(date: Date, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, targetLocale)
        return dateFormat.format(date)
    }
    
    /**
     * Format time according to locale
     */
    fun formatTime(date: Date, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, targetLocale)
        return timeFormat.format(date)
    }
    
    /**
     * Format date and time according to locale
     */
    fun formatDateTime(date: Date, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, targetLocale)
        return dateTimeFormat.format(date)
    }
    
    /**
     * Format number according to locale
     */
    fun formatNumber(number: Number, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val numberFormat = NumberFormat.getNumberInstance(targetLocale)
        return numberFormat.format(number)
    }
    
    /**
     * Format currency according to locale
     */
    fun formatCurrency(amount: Double, currencyCode: String, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val currencyFormat = NumberFormat.getCurrencyInstance(targetLocale)
        currencyFormat.currency = Currency.getInstance(currencyCode)
        return currencyFormat.format(amount)
    }
    
    /**
     * Format percentage according to locale
     */
    fun formatPercentage(value: Double, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val percentageFormat = NumberFormat.getPercentInstance(targetLocale)
        return percentageFormat.format(value)
    }
    
    /**
     * Format relative time (e.g., "2 hours ago")
     */
    fun formatRelativeTime(date: Date, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val now = Date()
        val diff = now.time - date.time
        
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            seconds < 60 -> stringResources.getString("just_now")
            minutes < 60 -> stringResources.getString("minutes_ago", minutes.toInt())
            hours < 24 -> stringResources.getString("hours_ago", hours.toInt())
            else -> stringResources.getString("days_ago", days.toInt())
        }
    }
    
    /**
     * Format file size according to locale
     */
    fun formatFileSize(bytes: Long, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val numberFormat = NumberFormat.getNumberInstance(targetLocale)
        
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${numberFormat.format(bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${numberFormat.format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${numberFormat.format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }
    
    /**
     * Format confidence percentage
     */
    fun formatConfidence(confidence: Float, locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val percentageFormat = NumberFormat.getPercentInstance(targetLocale)
        percentageFormat.minimumFractionDigits = 0
        percentageFormat.maximumFractionDigits = 1
        return percentageFormat.format(confidence)
    }
    
    /**
     * Get locale-specific date pattern
     */
    fun getDatePattern(locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val dateFormat = SimpleDateFormat("", targetLocale)
        return when (targetLocale.language) {
            "en" -> "MMM dd, yyyy"
            "es" -> "dd MMM yyyy"
            "fr" -> "dd MMM yyyy"
            "de" -> "dd. MMM yyyy"
            "it" -> "dd MMM yyyy"
            "pt" -> "dd MMM yyyy"
            "ru" -> "dd MMM yyyy"
            "zh" -> "yyyy年MM月dd日"
            "ja" -> "yyyy年MM月dd日"
            "ko" -> "yyyy년 MM월 dd일"
            "ar" -> "dd MMM yyyy"
            "hi" -> "dd MMM yyyy"
            else -> "MMM dd, yyyy"
        }
    }
    
    /**
     * Get locale-specific time pattern
     */
    fun getTimePattern(locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        return when (targetLocale.language) {
            "en" -> "h:mm a"
            "es" -> "H:mm"
            "fr" -> "HH:mm"
            "de" -> "HH:mm"
            "it" -> "HH:mm"
            "pt" -> "HH:mm"
            "ru" -> "HH:mm"
            "zh" -> "HH:mm"
            "ja" -> "HH:mm"
            "ko" -> "HH:mm"
            "ar" -> "h:mm a"
            "hi" -> "h:mm a"
            else -> "h:mm a"
        }
    }
    
    /**
     * Get locale-specific number pattern
     */
    fun getNumberPattern(locale: Locale? = null): String {
        val targetLocale = locale ?: stringResources.getCurrentLocale()
        val numberFormat = NumberFormat.getNumberInstance(targetLocale)
        return when (targetLocale.language) {
            "en" -> "#,##0.###"
            "es" -> "#,##0.###"
            "fr" -> "#,##0.###"
            "de" -> "#,##0.###"
            "it" -> "#,##0.###"
            "pt" -> "#,##0.###"
            "ru" -> "#,##0.###"
            "zh" -> "#,##0.###"
            "ja" -> "#,##0.###"
            "ko" -> "#,##0.###"
            "ar" -> "#,##0.###"
            "hi" -> "#,##0.###"
            else -> "#,##0.###"
        }
    }
}

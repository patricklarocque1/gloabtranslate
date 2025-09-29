package com.example.gloabtranslate.core.error

import android.content.Context
import com.example.gloabtranslate.core.i18n.StringResources
import com.example.gloabtranslate.core.logging.StructuredLogger
import java.util.*

/**
 * User-friendly error messages and error presentation
 * Provides localized, user-friendly error messages and error display utilities
 */
class UserFriendlyErrors private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "UserFriendlyErrors"
        
        @Volatile
        private var INSTANCE: UserFriendlyErrors? = null
        
        fun getInstance(context: Context): UserFriendlyErrors {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserFriendlyErrors(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    data class UserFriendlyError(
        val title: String,
        val message: String,
        val actionText: String?,
        val actionType: ActionType,
        val severity: ErrorSeverity,
        val canRetry: Boolean,
        val retryText: String? = null
    )
    
    enum class ActionType {
        NONE,
        RETRY,
        SETTINGS,
        PERMISSIONS,
        HELP,
        CONTACT_SUPPORT,
        RESTART_APP,
        CLEAR_CACHE
    }
    
    enum class ErrorSeverity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }
    
    private val stringResources = StringResources.getInstance(context)
    private val structuredLogger = StructuredLogger.getInstance(context)
    
    /**
     * Get user-friendly error message for an error
     */
    fun getUserFriendlyError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        return when (errorInfo.errorType) {
            ErrorHandler.ErrorType.NETWORK_ERROR -> getNetworkError(errorInfo)
            ErrorHandler.ErrorType.TRANSLATION_ERROR -> getTranslationError(errorInfo)
            ErrorHandler.ErrorType.AUDIO_ERROR -> getAudioError(errorInfo)
            ErrorHandler.ErrorType.PERMISSION_ERROR -> getPermissionError(errorInfo)
            ErrorHandler.ErrorType.STORAGE_ERROR -> getStorageError(errorInfo)
            ErrorHandler.ErrorType.MEMORY_ERROR -> getMemoryError(errorInfo)
            ErrorHandler.ErrorType.UI_ERROR -> getUIError(errorInfo)
            ErrorHandler.ErrorType.SYSTEM_ERROR -> getSystemError(errorInfo)
            ErrorHandler.ErrorType.UNKNOWN_ERROR -> getUnknownError(errorInfo)
        }
    }
    
    /**
     * Get user-friendly error message for error type
     */
    fun getUserFriendlyError(errorType: ErrorHandler.ErrorType, context: Map<String, Any> = emptyMap()): UserFriendlyError {
        val mockErrorInfo = ErrorHandler.ErrorInfo(
            id = "mock",
            timestamp = System.currentTimeMillis(),
            errorType = errorType,
            severity = ErrorHandler.ErrorSeverity.MEDIUM,
            message = "Mock error",
            throwable = null,
            context = context,
            stackTrace = "",
            userAction = null
        )
        
        return getUserFriendlyError(mockErrorInfo)
    }
    
    /**
     * Get localized error message
     */
    fun getLocalizedErrorMessage(errorType: ErrorHandler.ErrorType, vararg formatArgs: Any): String {
        val messageKey = getErrorMessageKey(errorType)
        return stringResources.getString(messageKey, *formatArgs)
    }
    
    /**
     * Get localized error title
     */
    fun getLocalizedErrorTitle(errorType: ErrorHandler.ErrorType): String {
        val titleKey = getErrorTitleKey(errorType)
        return stringResources.getString(titleKey)
    }
    
    /**
     * Get localized action text
     */
    fun getLocalizedActionText(actionType: ActionType): String {
        val actionKey = getActionTextKey(actionType)
        return stringResources.getString(actionKey)
    }
    
    /**
     * Get network error message
     */
    private fun getNetworkError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        val isTimeout = errorInfo.message.contains("timeout", ignoreCase = true)
        val isConnection = errorInfo.message.contains("connection", ignoreCase = true)
        
        return when {
            isTimeout -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.NETWORK_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.NETWORK_ERROR, "timeout"),
                actionText = getLocalizedActionText(ActionType.RETRY),
                actionType = ActionType.RETRY,
                severity = ErrorSeverity.WARNING,
                canRetry = true,
                retryText = getLocalizedActionText(ActionType.RETRY)
            )
            isConnection -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.NETWORK_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.NETWORK_ERROR, "connection"),
                actionText = getLocalizedActionText(ActionType.RETRY),
                actionType = ActionType.RETRY,
                severity = ErrorSeverity.WARNING,
                canRetry = true,
                retryText = getLocalizedActionText(ActionType.RETRY)
            )
            else -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.NETWORK_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.NETWORK_ERROR, "general"),
                actionText = getLocalizedActionText(ActionType.RETRY),
                actionType = ActionType.RETRY,
                severity = ErrorSeverity.WARNING,
                canRetry = true,
                retryText = getLocalizedActionText(ActionType.RETRY)
            )
        }
    }
    
    /**
     * Get translation error message
     */
    private fun getTranslationError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        val isModel = errorInfo.message.contains("model", ignoreCase = true)
        val isLanguage = errorInfo.message.contains("language", ignoreCase = true)
        
        return when {
            isModel -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.TRANSLATION_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.TRANSLATION_ERROR, "model"),
                actionText = getLocalizedActionText(ActionType.RETRY),
                actionType = ActionType.RETRY,
                severity = ErrorSeverity.ERROR,
                canRetry = true,
                retryText = getLocalizedActionText(ActionType.RETRY)
            )
            isLanguage -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.TRANSLATION_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.TRANSLATION_ERROR, "language"),
                actionText = getLocalizedActionText(ActionType.RETRY),
                actionType = ActionType.RETRY,
                severity = ErrorSeverity.WARNING,
                canRetry = true,
                retryText = getLocalizedActionText(ActionType.RETRY)
            )
            else -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.TRANSLATION_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.TRANSLATION_ERROR, "general"),
                actionText = getLocalizedActionText(ActionType.RETRY),
                actionType = ActionType.RETRY,
                severity = ErrorSeverity.ERROR,
                canRetry = true,
                retryText = getLocalizedActionText(ActionType.RETRY)
            )
        }
    }
    
    /**
     * Get audio error message
     */
    private fun getAudioError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        val isPermission = errorInfo.message.contains("permission", ignoreCase = true)
        val isHardware = errorInfo.message.contains("hardware", ignoreCase = true)
        
        return when {
            isPermission -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.AUDIO_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.AUDIO_ERROR, "permission"),
                actionText = getLocalizedActionText(ActionType.PERMISSIONS),
                actionType = ActionType.PERMISSIONS,
                severity = ErrorSeverity.ERROR,
                canRetry = false,
                retryText = null
            )
            isHardware -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.AUDIO_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.AUDIO_ERROR, "hardware"),
                actionText = getLocalizedActionText(ActionType.HELP),
                actionType = ActionType.HELP,
                severity = ErrorSeverity.ERROR,
                canRetry = false,
                retryText = null
            )
            else -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.AUDIO_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.AUDIO_ERROR, "general"),
                actionText = getLocalizedActionText(ActionType.RETRY),
                actionType = ActionType.RETRY,
                severity = ErrorSeverity.ERROR,
                canRetry = true,
                retryText = getLocalizedActionText(ActionType.RETRY)
            )
        }
    }
    
    /**
     * Get permission error message
     */
    private fun getPermissionError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        val permission = errorInfo.context["permission"] as? String
        
        return UserFriendlyError(
            title = getLocalizedErrorTitle(ErrorHandler.ErrorType.PERMISSION_ERROR),
            message = getLocalizedErrorMessage(ErrorHandler.ErrorType.PERMISSION_ERROR, permission ?: "unknown"),
            actionText = getLocalizedActionText(ActionType.PERMISSIONS),
            actionType = ActionType.PERMISSIONS,
            severity = ErrorSeverity.ERROR,
            canRetry = false,
            retryText = null
        )
    }
    
    /**
     * Get storage error message
     */
    private fun getStorageError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        val isSpace = errorInfo.message.contains("space", ignoreCase = true)
        val isAccess = errorInfo.message.contains("access", ignoreCase = true)
        
        return when {
            isSpace -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.STORAGE_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.STORAGE_ERROR, "space"),
                actionText = getLocalizedActionText(ActionType.CLEAR_CACHE),
                actionType = ActionType.CLEAR_CACHE,
                severity = ErrorSeverity.ERROR,
                canRetry = false,
                retryText = null
            )
            isAccess -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.STORAGE_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.STORAGE_ERROR, "access"),
                actionText = getLocalizedActionText(ActionType.SETTINGS),
                actionType = ActionType.SETTINGS,
                severity = ErrorSeverity.ERROR,
                canRetry = false,
                retryText = null
            )
            else -> UserFriendlyError(
                title = getLocalizedErrorTitle(ErrorHandler.ErrorType.STORAGE_ERROR),
                message = getLocalizedErrorMessage(ErrorHandler.ErrorType.STORAGE_ERROR, "general"),
                actionText = getLocalizedActionText(ActionType.CLEAR_CACHE),
                actionType = ActionType.CLEAR_CACHE,
                severity = ErrorSeverity.ERROR,
                canRetry = false,
                retryText = null
            )
        }
    }
    
    /**
     * Get memory error message
     */
    private fun getMemoryError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        return UserFriendlyError(
            title = getLocalizedErrorTitle(ErrorHandler.ErrorType.MEMORY_ERROR),
            message = getLocalizedErrorMessage(ErrorHandler.ErrorType.MEMORY_ERROR),
            actionText = getLocalizedActionText(ActionType.CLEAR_CACHE),
            actionType = ActionType.CLEAR_CACHE,
            severity = ErrorSeverity.ERROR,
            canRetry = false,
            retryText = null
        )
    }
    
    /**
     * Get UI error message
     */
    private fun getUIError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        return UserFriendlyError(
            title = getLocalizedErrorTitle(ErrorHandler.ErrorType.UI_ERROR),
            message = getLocalizedErrorMessage(ErrorHandler.ErrorType.UI_ERROR),
            actionText = getLocalizedActionText(ActionType.RETRY),
            actionType = ActionType.RETRY,
            severity = ErrorSeverity.WARNING,
            canRetry = true,
            retryText = getLocalizedActionText(ActionType.RETRY)
        )
    }
    
    /**
     * Get system error message
     */
    private fun getSystemError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        return UserFriendlyError(
            title = getLocalizedErrorTitle(ErrorHandler.ErrorType.SYSTEM_ERROR),
            message = getLocalizedErrorMessage(ErrorHandler.ErrorType.SYSTEM_ERROR),
            actionText = getLocalizedActionText(ActionType.RESTART_APP),
            actionType = ActionType.RESTART_APP,
            severity = ErrorSeverity.CRITICAL,
            canRetry = false,
            retryText = null
        )
    }
    
    /**
     * Get unknown error message
     */
    private fun getUnknownError(errorInfo: ErrorHandler.ErrorInfo): UserFriendlyError {
        return UserFriendlyError(
            title = getLocalizedErrorTitle(ErrorHandler.ErrorType.UNKNOWN_ERROR),
            message = getLocalizedErrorMessage(ErrorHandler.ErrorType.UNKNOWN_ERROR),
            actionText = getLocalizedActionText(ActionType.CONTACT_SUPPORT),
            actionType = ActionType.CONTACT_SUPPORT,
            severity = ErrorSeverity.ERROR,
            canRetry = true,
            retryText = getLocalizedActionText(ActionType.RETRY)
        )
    }
    
    /**
     * Get error message key for error type
     */
    private fun getErrorMessageKey(errorType: ErrorHandler.ErrorType): String {
        return when (errorType) {
            ErrorHandler.ErrorType.NETWORK_ERROR -> "error_network_general"
            ErrorHandler.ErrorType.TRANSLATION_ERROR -> "error_translation_general"
            ErrorHandler.ErrorType.AUDIO_ERROR -> "error_audio_general"
            ErrorHandler.ErrorType.PERMISSION_ERROR -> "error_permission_general"
            ErrorHandler.ErrorType.STORAGE_ERROR -> "error_storage_general"
            ErrorHandler.ErrorType.MEMORY_ERROR -> "error_memory_general"
            ErrorHandler.ErrorType.UI_ERROR -> "error_ui_general"
            ErrorHandler.ErrorType.SYSTEM_ERROR -> "error_system_general"
            ErrorHandler.ErrorType.UNKNOWN_ERROR -> "error_unknown_general"
        }
    }
    
    /**
     * Get error title key for error type
     */
    private fun getErrorTitleKey(errorType: ErrorHandler.ErrorType): String {
        return when (errorType) {
            ErrorHandler.ErrorType.NETWORK_ERROR -> "error_network_title"
            ErrorHandler.ErrorType.TRANSLATION_ERROR -> "error_translation_title"
            ErrorHandler.ErrorType.AUDIO_ERROR -> "error_audio_title"
            ErrorHandler.ErrorType.PERMISSION_ERROR -> "error_permission_title"
            ErrorHandler.ErrorType.STORAGE_ERROR -> "error_storage_title"
            ErrorHandler.ErrorType.MEMORY_ERROR -> "error_memory_title"
            ErrorHandler.ErrorType.UI_ERROR -> "error_ui_title"
            ErrorHandler.ErrorType.SYSTEM_ERROR -> "error_system_title"
            ErrorHandler.ErrorType.UNKNOWN_ERROR -> "error_unknown_title"
        }
    }
    
    /**
     * Get action text key for action type
     */
    private fun getActionTextKey(actionType: ActionType): String {
        return when (actionType) {
            ActionType.NONE -> "action_none"
            ActionType.RETRY -> "action_retry"
            ActionType.SETTINGS -> "action_settings"
            ActionType.PERMISSIONS -> "action_permissions"
            ActionType.HELP -> "action_help"
            ActionType.CONTACT_SUPPORT -> "action_contact_support"
            ActionType.RESTART_APP -> "action_restart_app"
            ActionType.CLEAR_CACHE -> "action_clear_cache"
        }
    }
    
    /**
     * Get error severity color
     */
    fun getErrorSeverityColor(severity: ErrorSeverity): String {
        return when (severity) {
            ErrorSeverity.INFO -> "error_info_color"
            ErrorSeverity.WARNING -> "error_warning_color"
            ErrorSeverity.ERROR -> "error_error_color"
            ErrorSeverity.CRITICAL -> "error_critical_color"
        }
    }
    
    /**
     * Get error severity icon
     */
    fun getErrorSeverityIcon(severity: ErrorSeverity): String {
        return when (severity) {
            ErrorSeverity.INFO -> "ic_info"
            ErrorSeverity.WARNING -> "ic_warning"
            ErrorSeverity.ERROR -> "ic_error"
            ErrorSeverity.CRITICAL -> "ic_error_critical"
        }
    }
    
    /**
     * Get action icon
     */
    fun getActionIcon(actionType: ActionType): String {
        return when (actionType) {
            ActionType.NONE -> ""
            ActionType.RETRY -> "ic_retry"
            ActionType.SETTINGS -> "ic_settings"
            ActionType.PERMISSIONS -> "ic_permissions"
            ActionType.HELP -> "ic_help"
            ActionType.CONTACT_SUPPORT -> "ic_contact_support"
            ActionType.RESTART_APP -> "ic_restart"
            ActionType.CLEAR_CACHE -> "ic_clear_cache"
        }
    }
    
    /**
     * Format error message with context
     */
    fun formatErrorMessage(errorType: ErrorHandler.ErrorType, context: Map<String, Any>): String {
        val baseMessage = getLocalizedErrorMessage(errorType)
        
        return when (errorType) {
            ErrorHandler.ErrorType.NETWORK_ERROR -> {
                val specificErrorType = context["error_type"] as? String ?: "general"
                getLocalizedErrorMessage(errorType, specificErrorType)
            }
            ErrorHandler.ErrorType.TRANSLATION_ERROR -> {
                val sourceLanguage = context["source_language"] as? String ?: "unknown"
                val targetLanguage = context["target_language"] as? String ?: "unknown"
                getLocalizedErrorMessage(errorType, sourceLanguage, targetLanguage)
            }
            ErrorHandler.ErrorType.AUDIO_ERROR -> {
                val specificErrorType = context["error_type"] as? String ?: "general"
                getLocalizedErrorMessage(errorType, specificErrorType)
            }
            ErrorHandler.ErrorType.PERMISSION_ERROR -> {
                val permission = context["permission"] as? String ?: "unknown"
                getLocalizedErrorMessage(errorType, permission)
            }
            ErrorHandler.ErrorType.STORAGE_ERROR -> {
                val specificErrorType = context["error_type"] as? String ?: "general"
                getLocalizedErrorMessage(errorType, specificErrorType)
            }
            else -> baseMessage
        }
    }
    
    /**
     * Get error help text
     */
    fun getErrorHelpText(errorType: ErrorHandler.ErrorType): String {
        val helpKey = getErrorHelpKey(errorType)
        return stringResources.getString(helpKey)
    }
    
    /**
     * Get error help key for error type
     */
    private fun getErrorHelpKey(errorType: ErrorHandler.ErrorType): String {
        return when (errorType) {
            ErrorHandler.ErrorType.NETWORK_ERROR -> "error_network_help"
            ErrorHandler.ErrorType.TRANSLATION_ERROR -> "error_translation_help"
            ErrorHandler.ErrorType.AUDIO_ERROR -> "error_audio_help"
            ErrorHandler.ErrorType.PERMISSION_ERROR -> "error_permission_help"
            ErrorHandler.ErrorType.STORAGE_ERROR -> "error_storage_help"
            ErrorHandler.ErrorType.MEMORY_ERROR -> "error_memory_help"
            ErrorHandler.ErrorType.UI_ERROR -> "error_ui_help"
            ErrorHandler.ErrorType.SYSTEM_ERROR -> "error_system_help"
            ErrorHandler.ErrorType.UNKNOWN_ERROR -> "error_unknown_help"
        }
    }
}

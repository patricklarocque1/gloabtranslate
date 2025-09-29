package com.example.gloabtranslate.core.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive permission management system for the GloabTranslate application.
 * Provides rationale explanations, state tracking, recovery mechanisms, and user-friendly
 * permission handling with detailed explanations for each permission request.
 */
class PermissionManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "PermissionManager"
        
        // Permission constants
        const val PERMISSION_RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
        const val PERMISSION_INTERNET = Manifest.permission.INTERNET
        const val PERMISSION_POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS
        
        // Permission categories
        enum class PermissionCategory {
            AUDIO, NETWORK, NOTIFICATIONS, ESSENTIAL
        }
        
        // Permission states
        enum class PermissionState {
            GRANTED, DENIED, DENIED_PERMANENTLY, NOT_REQUESTED, REVOKED
        }
        
        // Request results
        enum class PermissionResult {
            GRANTED, DENIED, DENIED_PERMANENTLY, PENDING, ERROR
        }
        
        @Volatile
        private var INSTANCE: PermissionManager? = null
        
        fun getInstance(context: Context): PermissionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PermissionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val permissionStates = ConcurrentHashMap<String, PermissionState>()
    private val permissionRationales = ConcurrentHashMap<String, PermissionRationale>()
    private val permissionListeners = CopyOnWriteArrayList<PermissionChangeListener>()
    private val isInitialized = AtomicBoolean(false)
    private val requestCount = AtomicLong(0)
    
    // State flows for reactive updates
    private val _permissionStatesFlow = MutableStateFlow<Map<String, PermissionState>>(emptyMap())
    val permissionStatesFlow: Flow<Map<String, PermissionState>> = _permissionStatesFlow.asStateFlow()
    
    private val _permissionRequestFlow = MutableStateFlow<PermissionRequestEvent?>(null)
    val permissionRequestFlow: Flow<PermissionRequestEvent?> = _permissionRequestFlow.asStateFlow()
    
    /**
     * Permission rationale data class containing detailed explanations
     */
    data class PermissionRationale(
        val permission: String,
        val title: String,
        val description: String,
        val detailedExplanation: String,
        val category: PermissionCategory,
        val isEssential: Boolean = false,
        val alternatives: List<String> = emptyList(),
        val usageExamples: List<String> = emptyList()
    )
    
    /**
     * Permission request event for tracking and analytics
     */
    data class PermissionRequestEvent(
        val permission: String,
        val result: PermissionResult,
        val timestamp: Long = System.currentTimeMillis(),
        val requestCount: Int,
        val wasRationaleShown: Boolean = false,
        val userAction: String? = null
    )
    
    /**
     * Permission change listener interface
     */
    interface PermissionChangeListener {
        fun onPermissionChanged(permission: String, newState: PermissionState, oldState: PermissionState)
        fun onPermissionRequestResult(permission: String, result: PermissionResult)
    }
    
    /**
     * Initialize the permission manager with rationale explanations
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized.getAndSet(true)) {
                Log.w(TAG, "PermissionManager already initialized")
                return@withContext true
            }
            
            setupPermissionRationales()
            updatePermissionStates()
            updateFlows()
            
            Log.d(TAG, "PermissionManager initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PermissionManager", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Setup detailed rationale explanations for all permissions
     */
    private fun setupPermissionRationales() {
        // Record Audio Permission
        permissionRationales[PERMISSION_RECORD_AUDIO] = PermissionRationale(
            permission = PERMISSION_RECORD_AUDIO,
            title = "Microphone Access",
            description = "Allows the app to listen to your voice for speech recognition and live translation",
            detailedExplanation = """
                The microphone permission is essential for GloabTranslate's core functionality:
                
                • **Live Translation**: Captures your speech in real-time for instant translation
                • **Speech Recognition**: Converts your spoken words into text for processing
                • **Voice Commands**: Enables voice-activated features for hands-free operation
                • **Audio Quality**: Ensures optimal audio capture for accurate translation
                
                **Privacy & Security**: 
                - Audio is processed locally when possible
                - No audio data is stored permanently on your device
                - All processing follows strict privacy guidelines
                
                **Alternative**: You can still use text-based translation without microphone access.
            """.trimIndent(),
            category = PermissionCategory.AUDIO,
            isEssential = true,
            alternatives = listOf("Manual text input", "Text-based translation only"),
            usageExamples = listOf(
                "Speaking in your native language for instant translation",
                "Voice commands to start/stop translation",
                "Hands-free operation during conversations"
            )
        )
        
        // Internet Permission
        permissionRationales[PERMISSION_INTERNET] = PermissionRationale(
            permission = PERMISSION_INTERNET,
            title = "Internet Access",
            description = "Allows the app to connect to translation services and download language models",
            detailedExplanation = """
                Internet access enables advanced translation features:
                
                • **Online Translation**: Access to powerful cloud-based translation engines
                • **Language Model Updates**: Download latest language models for better accuracy
                • **Real-time Translation**: Connect to translation services for instant results
                • **Offline Model Downloads**: Download language packs for offline use
                
                **Privacy & Security**:
                - Only necessary data is transmitted for translation
                - All communications are encrypted
                - No personal information is shared with third parties
                
                **Alternative**: Use offline translation models (limited languages available).
            """.trimIndent(),
            category = PermissionCategory.NETWORK,
            isEssential = true,
            alternatives = listOf("Offline translation models", "Pre-downloaded language packs"),
            usageExamples = listOf(
                "Real-time translation of conversations",
                "Downloading new language support",
                "Accessing latest translation improvements"
            )
        )
        
        // Post Notifications Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionRationales[PERMISSION_POST_NOTIFICATIONS] = PermissionRationale(
                permission = PERMISSION_POST_NOTIFICATIONS,
                title = "Notification Access",
                description = "Allows the app to show notifications for translation status and updates",
                detailedExplanation = """
                    Notification permission enables helpful app features:
                    
                    • **Translation Status**: Shows when live translation is active
                    • **Service Updates**: Notifies about translation service status
                    • **Background Operation**: Indicates when translation is running in background
                    • **Quick Actions**: Provides quick access to translation controls
                    
                    **Privacy & Security**:
                    - Notifications only show translation status, no personal content
                    - You can disable notifications anytime in settings
                    - No sensitive information is included in notifications
                    
                    **Alternative**: Use the app without notification updates (manual status checking).
                """.trimIndent(),
                category = PermissionCategory.NOTIFICATIONS,
                isEssential = false,
                alternatives = listOf("Manual status checking", "In-app status indicators only"),
                usageExamples = listOf(
                    "Notification showing \"Translation Active\" status",
                    "Quick access to start/stop translation",
                    "Service status updates"
                )
            )
        }
    }
    
    /**
     * Get rationale for a specific permission
     */
    fun getPermissionRationale(permission: String): PermissionRationale? {
        return permissionRationales[permission]
    }
    
    /**
     * Get all permissions by category
     */
    fun getPermissionsByCategory(category: PermissionCategory): List<String> {
        return permissionRationales.values
            .filter { it.category == category }
            .map { it.permission }
    }
    
    /**
     * Get essential permissions (required for core functionality)
     */
    fun getEssentialPermissions(): List<String> {
        return permissionRationales.values
            .filter { it.isEssential }
            .map { it.permission }
    }
    
    /**
     * Check current permission state
     */
    fun getPermissionState(permission: String): PermissionState {
        return permissionStates[permission] ?: PermissionState.NOT_REQUESTED
    }
    
    /**
     * Check if permission is granted
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if permission was permanently denied
     */
    fun isPermissionPermanentlyDenied(activity: Activity, permission: String): Boolean {
        return !activity.shouldShowRequestPermissionRationale(permission) &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Update permission states based on current system state
     */
    private fun updatePermissionStates() {
        val currentStates = ConcurrentHashMap<String, PermissionState>()
        
        permissionRationales.keys.forEach { permission ->
            currentStates[permission] = when {
                isPermissionGranted(permission) -> PermissionState.GRANTED
                else -> {
                    // For more accurate state, we'd need to track if it was permanently denied
                    // This would require storing request history
                    PermissionState.DENIED
                }
            }
        }
        
        permissionStates.clear()
        permissionStates.putAll(currentStates)
    }
    
    /**
     * Update state flows
     */
    private fun updateFlows() {
        _permissionStatesFlow.value = permissionStates.toMap()
    }
    
    /**
     * Request a single permission with rationale
     */
    fun requestPermission(
        activity: Activity,
        permission: String,
        onResult: (PermissionResult) -> Unit
    ) {
        val rationale = getPermissionRationale(permission)
        if (rationale == null) {
            Log.w(TAG, "No rationale found for permission: $permission")
            onResult(PermissionResult.ERROR)
            return
        }
        
        requestCount.incrementAndGet()
        
        when {
            isPermissionGranted(permission) -> {
                onResult(PermissionResult.GRANTED)
                updatePermissionStates()
                updateFlows()
            }
            isPermissionPermanentlyDenied(activity, permission) -> {
                onResult(PermissionResult.DENIED_PERMANENTLY)
                _permissionRequestFlow.value = PermissionRequestEvent(
                    permission = permission,
                    result = PermissionResult.DENIED_PERMANENTLY,
                    requestCount = requestCount.toInt()
                )
            }
            else -> {
                // Show rationale if needed and request permission
                // Note: This would typically be handled by the calling Activity/Fragment
                // For now, we'll just update the flow with a pending state
                _permissionRequestFlow.value = PermissionRequestEvent(
                    permission = permission,
                    result = PermissionResult.PENDING,
                    requestCount = requestCount.toInt(),
                    wasRationaleShown = true
                )
            }
        }
    }
    
    /**
     * Request multiple permissions with rationale
     */
    fun requestPermissions(
        activity: Activity,
        permissions: List<String>,
        onResult: (Map<String, PermissionResult>) -> Unit
    ) {
        requestCount.incrementAndGet()
        
        val missingPermissions = permissions.filter { !isPermissionGranted(it) }
        
        if (missingPermissions.isEmpty()) {
            val allGranted = permissions.associateWith { PermissionResult.GRANTED }
            onResult(allGranted)
            return
        }
        
        // Note: This would typically be handled by the calling Activity/Fragment
        // For now, we'll just update the flow with a pending state
        _permissionRequestFlow.value = PermissionRequestEvent(
            permission = missingPermissions.joinToString(","),
            result = PermissionResult.PENDING,
            requestCount = requestCount.toInt(),
            wasRationaleShown = true
        )
    }
    
    /**
     * Open app settings for permission management
     */
    fun openAppSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }
    
    /**
     * Get user-friendly message for permission state
     */
    fun getPermissionStateMessage(permission: String): String {
        val rationale = getPermissionRationale(permission)
        val state = getPermissionState(permission)
        
        return when (state) {
            PermissionState.GRANTED -> "${rationale?.title ?: "Permission"} is enabled"
            PermissionState.DENIED -> "${rationale?.title ?: "Permission"} is disabled"
            PermissionState.DENIED_PERMANENTLY -> "${rationale?.title ?: "Permission"} was permanently denied. Please enable it in settings."
            PermissionState.NOT_REQUESTED -> "${rationale?.title ?: "Permission"} has not been requested yet"
            PermissionState.REVOKED -> "${rationale?.title ?: "Permission"} was revoked"
        }
    }
    
    /**
     * Add permission change listener
     */
    fun addPermissionChangeListener(listener: PermissionChangeListener) {
        permissionListeners.add(listener)
    }
    
    /**
     * Remove permission change listener
     */
    fun removePermissionChangeListener(listener: PermissionChangeListener) {
        permissionListeners.remove(listener)
    }
    
    /**
     * Get all permission states
     */
    fun getAllPermissionStates(): Map<String, PermissionState> {
        return permissionStates.toMap()
    }
    
    /**
     * Get permission request statistics
     */
    fun getRequestStatistics(): PermissionRequestStatistics {
        return PermissionRequestStatistics(
            totalRequests = requestCount.get(),
            grantedPermissions = permissionStates.values.count { it == PermissionState.GRANTED },
            deniedPermissions = permissionStates.values.count { it == PermissionState.DENIED },
            permanentlyDeniedPermissions = permissionStates.values.count { it == PermissionState.DENIED_PERMANENTLY }
        )
    }
    
    /**
     * Permission request statistics
     */
    data class PermissionRequestStatistics(
        val totalRequests: Long,
        val grantedPermissions: Int,
        val deniedPermissions: Int,
        val permanentlyDeniedPermissions: Int
    )
}

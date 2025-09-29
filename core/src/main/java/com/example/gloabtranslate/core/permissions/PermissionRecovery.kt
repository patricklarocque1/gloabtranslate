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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.example.gloabtranslate.core.permissions.PermissionManager

/**
 * Permission result enum for recovery operations
 */
enum class PermissionResult {
    GRANTED,
    DENIED,
    DENIED_PERMANENTLY,
    RECOVERED
}


/**
 * Permission recovery manager for handling permission restoration, automatic recovery attempts,
 * and graceful degradation when permissions are revoked or denied. Provides intelligent
 * recovery strategies based on permission importance and user behavior patterns.
 */
class PermissionRecovery private constructor(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val permissionPreferences: com.example.gloabtranslate.core.data.preferences.PermissionPreferences
) {
    
    companion object {
        private const val TAG = "PermissionRecovery"
        
        // Recovery strategies
        enum class RecoveryStrategy {
            IMMEDIATE, // Request permission immediately
            DELAYED,   // Wait for better context
            USER_INITIATED, // Let user decide when to request
            GRACEFUL_DEGRADATION, // Continue with limited functionality
            DISABLE_FEATURE // Disable feature requiring permission
        }
        
        // Recovery triggers
        enum class RecoveryTrigger {
            APP_STARTUP,
            FEATURE_ACCESS,
            PERMISSION_REVOKED,
            PERMISSION_DENIED,
            USER_INITIATED,
            SCHEDULED_CHECK,
            ERROR_RECOVERY
        }
        
        // Recovery states
        enum class RecoveryState {
            IDLE,
            ANALYZING,
            RECOVERING,
            SUCCESS,
            FAILED,
            USER_ACTION_REQUIRED,
            DISABLED
        }
        
        @Volatile
        private var INSTANCE: PermissionRecovery? = null
        
        fun getInstance(
            context: Context,
            permissionManager: PermissionManager,
            permissionPreferences: com.example.gloabtranslate.core.data.preferences.PermissionPreferences
        ): PermissionRecovery {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PermissionRecovery(
                    context.applicationContext,
                    permissionManager,
                    permissionPreferences
                ).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val isInitialized = AtomicBoolean(false)
    private val recoveryMutex = Mutex()
    private val recoveryStates = ConcurrentHashMap<String, RecoveryState>()
    private val recoveryAttempts = ConcurrentHashMap<String, Int>()
    private val lastRecoveryTime = ConcurrentHashMap<String, Long>()
    private val recoveryListeners = CopyOnWriteArrayList<RecoveryListener>()
    private val recoveryCount = AtomicLong(0)
    
    // Recovery configuration
    private val maxRecoveryAttempts = 3
    private val recoveryCooldownMs = 24 * 60 * 60 * 1000L // 24 hours
    private val essentialPermissionCooldownMs = 60 * 60 * 1000L // 1 hour
    
    // State flows for reactive updates
    private val _recoveryStatesFlow = MutableStateFlow<Map<String, RecoveryState>>(emptyMap())
    val recoveryStatesFlow: Flow<Map<String, RecoveryState>> = _recoveryStatesFlow.asStateFlow()
    
    private val _recoveryEventsFlow = MutableStateFlow<RecoveryEvent?>(null)
    val recoveryEventsFlow: Flow<RecoveryEvent?> = _recoveryEventsFlow.asStateFlow()
    
    /**
     * Recovery event data class
     */
    data class RecoveryEvent(
        val permission: String,
        val trigger: RecoveryTrigger,
        val strategy: RecoveryStrategy,
        val state: RecoveryState,
        val timestamp: Long = System.currentTimeMillis(),
        val attemptNumber: Int,
        val success: Boolean = false,
        val userAction: String? = null,
        val errorMessage: String? = null
    )
    
    /**
     * Recovery listener interface
     */
    interface RecoveryListener {
        fun onRecoveryStarted(permission: String, strategy: RecoveryStrategy)
        fun onRecoveryCompleted(permission: String, success: Boolean, userAction: String?)
        fun onRecoveryFailed(permission: String, reason: String)
        fun onRecoveryStateChanged(permission: String, newState: RecoveryState, oldState: RecoveryState)
    }
    
    /**
     * Initialize the permission recovery manager
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val alreadyInitialized = recoveryMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "PermissionRecovery already initialized")
                    true
                } else {
                    false
                }
            }
            
            if (alreadyInitialized) {
                return@withContext true
            }
            
            // Load recovery states from preferences
            loadRecoveryStates()
            
            // Start monitoring permission states
            startPermissionMonitoring()
            
            Log.d(TAG, "PermissionRecovery initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PermissionRecovery", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Trigger permission recovery based on the given trigger and context
     */
    suspend fun triggerRecovery(
        permission: String,
        trigger: RecoveryTrigger,
        activity: Activity? = null,
        userContext: String? = null
    ): RecoveryStrategy = withContext(Dispatchers.IO) {
        try {
            recoveryMutex.withLock {
                val strategy = determineRecoveryStrategy(permission, trigger, userContext)
                
                val event = RecoveryEvent(
                    permission = permission,
                    trigger = trigger,
                    strategy = strategy,
                    state = RecoveryState.ANALYZING,
                    attemptNumber = recoveryAttempts[permission] ?: 0
                )
                
                _recoveryEventsFlow.value = event
                recoveryStates[permission] = RecoveryState.ANALYZING
                updateFlows()
                
                Log.d(TAG, "Recovery triggered for $permission with strategy: $strategy")
                
                when (strategy) {
                    RecoveryStrategy.IMMEDIATE -> executeImmediateRecovery(permission, activity, event)
                    RecoveryStrategy.DELAYED -> executeDelayedRecovery(permission, activity, event)
                    RecoveryStrategy.USER_INITIATED -> executeUserInitiatedRecovery(permission, activity, event)
                    RecoveryStrategy.GRACEFUL_DEGRADATION -> executeGracefulDegradation(permission, event)
                    RecoveryStrategy.DISABLE_FEATURE -> executeFeatureDisable(permission, event)
                }
                
                strategy
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger recovery for $permission", e)
            RecoveryStrategy.DISABLE_FEATURE
        }
    }
    
    /**
     * Determine the best recovery strategy based on permission importance and context
     */
    private fun determineRecoveryStrategy(
        permission: String,
        trigger: RecoveryTrigger,
        userContext: String?
    ): RecoveryStrategy {
        val rationale = permissionManager.getPermissionRationale(permission)
        val isEssential = rationale?.isEssential ?: false
        val attemptCount = recoveryAttempts[permission] ?: 0
        val lastAttemptTime = lastRecoveryTime[permission] ?: 0
        val currentTime = System.currentTimeMillis()
        
        return when {
            // Too many attempts
            attemptCount >= maxRecoveryAttempts -> RecoveryStrategy.DISABLE_FEATURE
            
            // Essential permission - more aggressive recovery
            isEssential -> {
                when (trigger) {
                    RecoveryTrigger.APP_STARTUP -> {
                        if (currentTime - lastAttemptTime > essentialPermissionCooldownMs) {
                            RecoveryStrategy.IMMEDIATE
                        } else {
                            RecoveryStrategy.USER_INITIATED
                        }
                    }
                    RecoveryTrigger.FEATURE_ACCESS -> RecoveryStrategy.IMMEDIATE
                    RecoveryTrigger.PERMISSION_REVOKED -> RecoveryStrategy.IMMEDIATE
                    else -> RecoveryStrategy.DELAYED
                }
            }
            
            // Non-essential permission - more conservative recovery
            else -> {
                when (trigger) {
                    RecoveryTrigger.FEATURE_ACCESS -> RecoveryStrategy.USER_INITIATED
                    RecoveryTrigger.PERMISSION_REVOKED -> RecoveryStrategy.GRACEFUL_DEGRADATION
                    RecoveryTrigger.USER_INITIATED -> RecoveryStrategy.IMMEDIATE
                    else -> RecoveryStrategy.DELAYED
                }
            }
        }
    }
    
    /**
     * Execute immediate recovery strategy
     */
    private suspend fun executeImmediateRecovery(
        permission: String,
        activity: Activity?,
        event: RecoveryEvent
    ) {
        recoveryStates[permission] = RecoveryState.RECOVERING
        updateFlows()
        
        notifyRecoveryStarted(permission, RecoveryStrategy.IMMEDIATE)
        
        if (activity != null) {
            try {
                permissionManager.requestPermission(activity, permission) { result ->
                    // Note: This would typically be handled by launching a coroutine
                    // For now, we'll just log the result
                    Log.d(TAG, "Permission request result for $permission: $result")
                }
            } catch (e: Exception) {
                handleRecoveryFailure(permission, "Failed to request permission: ${e.message}", event)
            }
        } else {
            handleRecoveryFailure(permission, "Activity not available for immediate recovery", event)
        }
    }
    
    /**
     * Execute delayed recovery strategy
     */
    private suspend fun executeDelayedRecovery(
        permission: String,
        activity: Activity?,
        event: RecoveryEvent
    ) {
        recoveryStates[permission] = RecoveryState.RECOVERING
        updateFlows()
        
        notifyRecoveryStarted(permission, RecoveryStrategy.DELAYED)
        
        // Wait for better context or user interaction
        delay(5000) // 5 second delay
        
        // Check if permission is still needed
        if (shouldAttemptRecovery(permission)) {
            if (activity != null) {
                permissionManager.requestPermission(activity, permission) { result ->
                    // Note: This would typically be handled by launching a coroutine
                    // For now, we'll just log the result
                    Log.d(TAG, "Delayed permission request result for $permission: $result")
                }
            } else {
                recoveryStates[permission] = RecoveryState.USER_ACTION_REQUIRED
                updateFlows()
            }
        } else {
            recoveryStates[permission] = RecoveryState.SUCCESS
            updateFlows()
        }
    }
    
    /**
     * Execute user-initiated recovery strategy
     */
    private suspend fun executeUserInitiatedRecovery(
        permission: String,
        activity: Activity?,
        event: RecoveryEvent
    ) {
        recoveryStates[permission] = RecoveryState.USER_ACTION_REQUIRED
        updateFlows()
        
        notifyRecoveryStarted(permission, RecoveryStrategy.USER_INITIATED)
        
        // Show rationale and let user decide
        val rationale = permissionManager.getPermissionRationale(permission)
        if (rationale != null) {
            // This would typically show a dialog or notification
            // For now, we'll mark it as requiring user action
            Log.d(TAG, "User action required for permission: $permission")
        }
    }
    
    /**
     * Execute graceful degradation strategy
     */
    private suspend fun executeGracefulDegradation(
        permission: String,
        event: RecoveryEvent
    ) {
        recoveryStates[permission] = RecoveryState.SUCCESS
        updateFlows()
        
        notifyRecoveryStarted(permission, RecoveryStrategy.GRACEFUL_DEGRADATION)
        
        // Continue with limited functionality
        val rationale = permissionManager.getPermissionRationale(permission)
        if (rationale != null) {
            Log.d(TAG, "Graceful degradation for permission: $permission - using alternatives: ${rationale.alternatives}")
        }
        
        handleRecoveryResult(permission, PermissionResult.DENIED, event, "graceful_degradation")
    }
    
    /**
     * Execute feature disable strategy
     */
    private suspend fun executeFeatureDisable(
        permission: String,
        event: RecoveryEvent
    ) {
        recoveryStates[permission] = RecoveryState.DISABLED
        updateFlows()
        
        notifyRecoveryStarted(permission, RecoveryStrategy.DISABLE_FEATURE)
        
        Log.d(TAG, "Feature disabled due to permission: $permission")
        
        val updatedEvent = event.copy(
            state = RecoveryState.DISABLED,
            success = false,
            userAction = "feature_disabled"
        )
        _recoveryEventsFlow.value = updatedEvent
        
        notifyRecoveryCompleted(permission, false, "feature_disabled")
    }
    
    /**
     * Handle recovery result
     */
    private suspend fun handleRecoveryResult(
        permission: String,
        result: PermissionResult,
        event: RecoveryEvent,
        userAction: String
    ) {
        val success = result == PermissionResult.GRANTED
        val newState = when {
            success -> RecoveryState.SUCCESS
            result == PermissionResult.DENIED_PERMANENTLY -> RecoveryState.FAILED
            else -> RecoveryState.FAILED
        }
        
        recoveryStates[permission] = newState
        recoveryAttempts[permission] = (recoveryAttempts[permission] ?: 0) + 1
        lastRecoveryTime[permission] = System.currentTimeMillis()
        
        updateFlows()
        
        val updatedEvent = event.copy(
            state = newState,
            success = success,
            userAction = userAction
        )
        _recoveryEventsFlow.value = updatedEvent
        
        notifyRecoveryCompleted(permission, success, userAction)
        
        // Record the recovery attempt
        permissionPreferences.recordPermissionRequest(
            permission = permission,
            result = result.toString(),
            wasRationaleShown = true,
            userAction = userAction
        )
    }
    
    /**
     * Handle recovery failure
     */
    private fun handleRecoveryFailure(
        permission: String,
        reason: String,
        event: RecoveryEvent
    ) {
        recoveryStates[permission] = RecoveryState.FAILED
        recoveryAttempts[permission] = (recoveryAttempts[permission] ?: 0) + 1
        lastRecoveryTime[permission] = System.currentTimeMillis()
        
        updateFlows()
        
        val updatedEvent = event.copy(
            state = RecoveryState.FAILED,
            success = false,
            errorMessage = reason
        )
        _recoveryEventsFlow.value = updatedEvent
        
        notifyRecoveryFailed(permission, reason)
        
        Log.e(TAG, "Recovery failed for $permission: $reason")
    }
    
    /**
     * Check if recovery should be attempted
     */
    private fun shouldAttemptRecovery(permission: String): Boolean {
        val attemptCount = recoveryAttempts[permission] ?: 0
        val lastAttemptTime = lastRecoveryTime[permission] ?: 0
        val currentTime = System.currentTimeMillis()
        
        return attemptCount < maxRecoveryAttempts &&
                (currentTime - lastAttemptTime) > recoveryCooldownMs
    }
    
    /**
     * Start monitoring permission states for automatic recovery
     */
    private fun startPermissionMonitoring() {
        // Monitor permission state changes
        CoroutineScope(Dispatchers.IO).launch {
            permissionManager.permissionStatesFlow.collect { permissionStates ->
                permissionStates.forEach { (permission, state) ->
                    if (state.name == "REVOKED" || state.name == "DENIED") {
                        val rationale = permissionManager.getPermissionRationale(permission)
                        if (rationale?.isEssential == true) {
                            triggerRecovery(
                                permission = permission,
                                trigger = RecoveryTrigger.PERMISSION_REVOKED
                            )
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Load recovery states from preferences
     */
    private suspend fun loadRecoveryStates() {
        // This would load persisted recovery states if needed
        // For now, we'll start fresh
    }
    
    /**
     * Update state flows
     */
    private fun updateFlows() {
        _recoveryStatesFlow.value = recoveryStates.toMap()
    }
    
    /**
     * Add recovery listener
     */
    fun addRecoveryListener(listener: RecoveryListener) {
        recoveryListeners.add(listener)
    }
    
    /**
     * Remove recovery listener
     */
    fun removeRecoveryListener(listener: RecoveryListener) {
        recoveryListeners.remove(listener)
    }
    
    /**
     * Get recovery state for a permission
     */
    fun getRecoveryState(permission: String): RecoveryState {
        return recoveryStates[permission] ?: RecoveryState.IDLE
    }
    
    /**
     * Get recovery statistics
     */
    fun getRecoveryStatistics(): RecoveryStatistics {
        val totalAttempts = recoveryAttempts.values.sum()
        val successfulRecoveries = recoveryStates.values.count { it == RecoveryState.SUCCESS }
        val failedRecoveries = recoveryStates.values.count { it == RecoveryState.FAILED }
        
        return RecoveryStatistics(
            totalAttempts = totalAttempts,
            successfulRecoveries = successfulRecoveries,
            failedRecoveries = failedRecoveries,
            activeRecoveries = recoveryStates.values.count { it == RecoveryState.RECOVERING },
            disabledFeatures = recoveryStates.values.count { it == RecoveryState.DISABLED }
        )
    }
    
    /**
     * Recovery statistics data class
     */
    data class RecoveryStatistics(
        val totalAttempts: Int,
        val successfulRecoveries: Int,
        val failedRecoveries: Int,
        val activeRecoveries: Int,
        val disabledFeatures: Int
    )
    
    // Notification methods
    
    private fun notifyRecoveryStarted(permission: String, strategy: RecoveryStrategy) {
        recoveryListeners.forEach { listener ->
            try {
                listener.onRecoveryStarted(permission, strategy)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying recovery started", e)
            }
        }
    }
    
    private fun notifyRecoveryCompleted(permission: String, success: Boolean, userAction: String?) {
        recoveryListeners.forEach { listener ->
            try {
                listener.onRecoveryCompleted(permission, success, userAction)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying recovery completed", e)
            }
        }
    }
    
    private fun notifyRecoveryFailed(permission: String, reason: String) {
        recoveryListeners.forEach { listener ->
            try {
                listener.onRecoveryFailed(permission, reason)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying recovery failed", e)
            }
        }
    }
}

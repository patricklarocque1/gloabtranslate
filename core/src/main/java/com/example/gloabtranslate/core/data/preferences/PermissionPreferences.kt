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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Permission preferences manager for persisting permission states, request history,
 * user choices, and analytics data. Provides comprehensive tracking of permission
 * interactions with the ability to restore states and analyze permission patterns.
 */
class PermissionPreferences private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "PermissionPreferences"
        private const val PREFS_NAME = "permission_preferences"
        private const val VERSION_KEY = "permission_preferences_version"
        private const val CURRENT_VERSION = 1
        private const val MAX_HISTORY_ENTRIES = 100
        
        // Permission state keys
        private const val KEY_PERMISSION_STATES = "permission_states"
        private const val KEY_REQUEST_HISTORY = "request_history"
        private const val KEY_USER_CHOICES = "user_choices"
        private const val KEY_RATIONALE_SHOWN = "rationale_shown"
        private const val KEY_PERMISSION_ANALYTICS = "permission_analytics"
        private const val KEY_LAST_REQUEST_TIME = "last_request_time"
        private const val KEY_FIRST_REQUEST_TIME = "first_request_time"
        private const val KEY_SETTINGS_OPENED_COUNT = "settings_opened_count"
        private const val KEY_AUTO_RECOVERY_ENABLED = "auto_recovery_enabled"
        
        @Volatile
        private var INSTANCE: PermissionPreferences? = null
        
        fun getInstance(context: Context): PermissionPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PermissionPreferences(context.applicationContext).also { INSTANCE = it }
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
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val preferencesMutex = Mutex()
    private val permissionStates = ConcurrentHashMap<String, PersistedPermissionState>()
    private val requestHistory = CopyOnWriteArrayList<PermissionRequestRecord>()
    private val userChoices = ConcurrentHashMap<String, UserPermissionChoice>()
    private val rationaleShown = ConcurrentHashMap<String, Boolean>()
    private val analytics = AtomicLong(0)
    
    // State flows for reactive updates
    private val _permissionStatesFlow = MutableStateFlow<Map<String, PersistedPermissionState>>(emptyMap())
    val permissionStatesFlow: Flow<Map<String, PersistedPermissionState>> = _permissionStatesFlow.asStateFlow()
    
    private val _requestHistoryFlow = MutableStateFlow<List<PermissionRequestRecord>>(emptyList())
    val requestHistoryFlow: Flow<List<PermissionRequestRecord>> = _requestHistoryFlow.asStateFlow()
    
    private val _userChoicesFlow = MutableStateFlow<Map<String, UserPermissionChoice>>(emptyMap())
    val userChoicesFlow: Flow<Map<String, UserPermissionChoice>> = _userChoicesFlow.asStateFlow()
    
    /**
     * Persisted permission state data structure
     */
    @Serializable
    data class PersistedPermissionState(
        val permission: String,
        val state: String, // GRANTED, DENIED, DENIED_PERMANENTLY, NOT_REQUESTED, REVOKED
        val lastUpdated: Long = System.currentTimeMillis(),
        val requestCount: Int = 0,
        val lastRequestTime: Long = 0,
        val isEssential: Boolean = false,
        val category: String = "UNKNOWN",
        val rationaleShownCount: Int = 0,
        val settingsOpenedCount: Int = 0,
        val userFeedback: String? = null
    )
    
    /**
     * Permission request history record
     */
    @Serializable
    data class PermissionRequestRecord(
        val permission: String,
        val result: String, // GRANTED, DENIED, DENIED_PERMANENTLY, ERROR
        val timestamp: Long = System.currentTimeMillis(),
        val requestCount: Int,
        val wasRationaleShown: Boolean = false,
        val userAction: String? = null,
        val sessionId: String? = null,
        val appVersion: String? = null
    )
    
    /**
     * User permission choice record
     */
    @Serializable
    data class UserPermissionChoice(
        val permission: String,
        val choice: String, // GRANTED, DENIED, SKIPPED, DEFERRED
        val timestamp: Long = System.currentTimeMillis(),
        val context: String? = null, // What the user was trying to do
        val feedback: String? = null,
        val wasInformed: Boolean = false // Whether user saw rationale
    )
    
    /**
     * Permission analytics summary
     */
    @Serializable
    data class PermissionAnalytics(
        val totalRequests: Long = 0,
        val grantedRequests: Long = 0,
        val deniedRequests: Long = 0,
        val permanentlyDeniedRequests: Long = 0,
        val rationaleShownCount: Long = 0,
        val settingsOpenedCount: Long = 0,
        val averageRequestsPerPermission: Double = 0.0,
        val mostRequestedPermission: String? = null,
        val leastRequestedPermission: String? = null,
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    /**
     * Initialize the permission preferences manager
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "PermissionPreferences already initialized")
                    return@withContext true
                }
                
                // Load persisted data
                loadPermissionStates()
                loadRequestHistory()
                loadUserChoices()
                loadRationaleShown()
                
                // Check version and migrate if necessary
                val savedVersion = sharedPreferences.getInt(VERSION_KEY, 0)
                if (savedVersion < CURRENT_VERSION) {
                    migratePreferences(savedVersion, CURRENT_VERSION)
                }
                
                // Update flows
                updateFlows()
                
                Log.d(TAG, "PermissionPreferences initialized successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PermissionPreferences", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Save permission state
     */
    suspend fun savePermissionState(
        permission: String,
        state: String,
        isEssential: Boolean = false,
        category: String = "UNKNOWN"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                val currentState = permissionStates[permission]
                val newState = PersistedPermissionState(
                    permission = permission,
                    state = state,
                    lastUpdated = System.currentTimeMillis(),
                    requestCount = (currentState?.requestCount ?: 0) + if (state != currentState?.state) 1 else 0,
                    lastRequestTime = if (state != currentState?.state) System.currentTimeMillis() else (currentState?.lastRequestTime ?: 0),
                    isEssential = isEssential,
                    category = category,
                    rationaleShownCount = currentState?.rationaleShownCount ?: 0,
                    settingsOpenedCount = currentState?.settingsOpenedCount ?: 0,
                    userFeedback = currentState?.userFeedback
                )
                
                permissionStates[permission] = newState
                savePermissionStatesToStorage()
                updateFlows()
                
                Log.d(TAG, "Permission state saved: $permission = $state")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save permission state: $permission", e)
            false
        }
    }
    
    /**
     * Get permission state
     */
    suspend fun getPermissionState(permission: String): PersistedPermissionState? = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                permissionStates[permission]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get permission state: $permission", e)
            null
        }
    }
    
    /**
     * Record permission request
     */
    suspend fun recordPermissionRequest(
        permission: String,
        result: String,
        wasRationaleShown: Boolean = false,
        userAction: String? = null,
        sessionId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                val currentState = permissionStates[permission]
                val requestCount = (currentState?.requestCount ?: 0) + 1
                
                val record = PermissionRequestRecord(
                    permission = permission,
                    result = result,
                    timestamp = System.currentTimeMillis(),
                    requestCount = requestCount,
                    wasRationaleShown = wasRationaleShown,
                    userAction = userAction,
                    sessionId = sessionId,
                    appVersion = getAppVersion()
                )
                
                // Add to history (with size limit)
                requestHistory.add(record)
                if (requestHistory.size > MAX_HISTORY_ENTRIES) {
                    requestHistory.removeAt(0)
                }
                
                // Update permission state
                permissionStates[permission] = currentState?.copy(
                    state = result,
                    lastUpdated = System.currentTimeMillis(),
                    requestCount = requestCount,
                    lastRequestTime = System.currentTimeMillis(),
                    rationaleShownCount = currentState.rationaleShownCount + if (wasRationaleShown) 1 else 0
                ) ?: PersistedPermissionState(
                    permission = permission,
                    state = result,
                    requestCount = 1,
                    lastRequestTime = System.currentTimeMillis(),
                    rationaleShownCount = if (wasRationaleShown) 1 else 0
                )
                
                // Save to storage
                savePermissionStatesToStorage()
                saveRequestHistoryToStorage()
                
                // Update analytics
                updateAnalytics()
                
                updateFlows()
                
                Log.d(TAG, "Permission request recorded: $permission = $result")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record permission request: $permission", e)
            false
        }
    }
    
    /**
     * Record user permission choice
     */
    suspend fun recordUserChoice(
        permission: String,
        choice: String,
        context: String? = null,
        feedback: String? = null,
        wasInformed: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                val userChoice = UserPermissionChoice(
                    permission = permission,
                    choice = choice,
                    timestamp = System.currentTimeMillis(),
                    context = context,
                    feedback = feedback,
                    wasInformed = wasInformed
                )
                
                userChoices[permission] = userChoice
                saveUserChoicesToStorage()
                updateFlows()
                
                Log.d(TAG, "User choice recorded: $permission = $choice")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record user choice: $permission", e)
            false
        }
    }
    
    /**
     * Mark rationale as shown for a permission
     */
    suspend fun markRationaleShown(permission: String): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                rationaleShown[permission] = true
                
                val currentState = permissionStates[permission]
                if (currentState != null) {
                    permissionStates[permission] = currentState.copy(
                        rationaleShownCount = currentState.rationaleShownCount + 1
                    )
                    savePermissionStatesToStorage()
                }
                
                saveRationaleShownToStorage()
                updateFlows()
                
                Log.d(TAG, "Rationale shown marked for: $permission")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark rationale shown: $permission", e)
            false
        }
    }
    
    /**
     * Check if rationale was shown for a permission
     */
    suspend fun wasRationaleShown(permission: String): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                rationaleShown[permission] ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check rationale shown: $permission", e)
            false
        }
    }
    
    /**
     * Record settings opened
     */
    suspend fun recordSettingsOpened(permission: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                val currentCount = sharedPreferences.getInt(KEY_SETTINGS_OPENED_COUNT, 0)
                sharedPreferences.edit()
                    .putInt(KEY_SETTINGS_OPENED_COUNT, currentCount + 1)
                    .apply()
                
                if (permission != null) {
                    val currentState = permissionStates[permission]
                    if (currentState != null) {
                        permissionStates[permission] = currentState.copy(
                            settingsOpenedCount = currentState.settingsOpenedCount + 1
                        )
                        savePermissionStatesToStorage()
                    }
                }
                
                updateFlows()
                
                Log.d(TAG, "Settings opened recorded")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record settings opened", e)
            false
        }
    }
    
    /**
     * Get request history for a permission
     */
    suspend fun getRequestHistory(permission: String? = null): List<PermissionRequestRecord> = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                if (permission == null) {
                    requestHistory.toList()
                } else {
                    requestHistory.filter { it.permission == permission }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get request history", e)
            emptyList()
        }
    }
    
    /**
     * Get user choices
     */
    suspend fun getUserChoices(): Map<String, UserPermissionChoice> = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                userChoices.toMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user choices", e)
            emptyMap()
        }
    }
    
    /**
     * Get permission analytics
     */
    suspend fun getPermissionAnalytics(): PermissionAnalytics = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                val totalRequests = requestHistory.size.toLong()
                val grantedRequests = requestHistory.count { it.result == "GRANTED" }.toLong()
                val deniedRequests = requestHistory.count { it.result == "DENIED" }.toLong()
                val permanentlyDeniedRequests = requestHistory.count { it.result == "DENIED_PERMANENTLY" }.toLong()
                val rationaleShownCount = requestHistory.count { it.wasRationaleShown }.toLong()
                val settingsOpenedCount = sharedPreferences.getInt(KEY_SETTINGS_OPENED_COUNT, 0).toLong()
                
                val permissionRequestCounts = requestHistory.groupingBy { it.permission }.eachCount()
                val mostRequestedPermission = permissionRequestCounts.maxByOrNull { it.value }?.key
                val leastRequestedPermission = permissionRequestCounts.minByOrNull { it.value }?.key
                val averageRequestsPerPermission = if (permissionRequestCounts.isNotEmpty()) {
                    permissionRequestCounts.values.average()
                } else 0.0
                
                PermissionAnalytics(
                    totalRequests = totalRequests,
                    grantedRequests = grantedRequests,
                    deniedRequests = deniedRequests,
                    permanentlyDeniedRequests = permanentlyDeniedRequests,
                    rationaleShownCount = rationaleShownCount,
                    settingsOpenedCount = settingsOpenedCount,
                    averageRequestsPerPermission = averageRequestsPerPermission,
                    mostRequestedPermission = mostRequestedPermission,
                    leastRequestedPermission = leastRequestedPermission,
                    lastUpdated = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get permission analytics", e)
            PermissionAnalytics()
        }
    }
    
    /**
     * Clear all permission data (for testing or privacy)
     */
    suspend fun clearAllData(): Boolean = withContext(Dispatchers.IO) {
        try {
            preferencesMutex.withLock {
                permissionStates.clear()
                requestHistory.clear()
                userChoices.clear()
                rationaleShown.clear()
                
                sharedPreferences.edit().clear().apply()
                updateFlows()
                
                Log.d(TAG, "All permission data cleared")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear permission data", e)
            false
        }
    }
    
    // Private helper methods
    
    private fun loadPermissionStates() {
        try {
            val statesJson = sharedPreferences.getString(KEY_PERMISSION_STATES, null)
            if (statesJson != null) {
                val states = json.decodeFromString<Map<String, PersistedPermissionState>>(statesJson)
                permissionStates.clear()
                permissionStates.putAll(states)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load permission states", e)
        }
    }
    
    private fun savePermissionStatesToStorage() {
        try {
            val statesJson = json.encodeToString(permissionStates.toMap())
            sharedPreferences.edit()
                .putString(KEY_PERMISSION_STATES, statesJson)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save permission states", e)
        }
    }
    
    private fun loadRequestHistory() {
        try {
            val historyJson = sharedPreferences.getString(KEY_REQUEST_HISTORY, null)
            if (historyJson != null) {
                val history = json.decodeFromString<List<PermissionRequestRecord>>(historyJson)
                requestHistory.clear()
                requestHistory.addAll(history)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load request history", e)
        }
    }
    
    private fun saveRequestHistoryToStorage() {
        try {
            val historyJson = json.encodeToString(requestHistory.toList())
            sharedPreferences.edit()
                .putString(KEY_REQUEST_HISTORY, historyJson)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save request history", e)
        }
    }
    
    private fun loadUserChoices() {
        try {
            val choicesJson = sharedPreferences.getString(KEY_USER_CHOICES, null)
            if (choicesJson != null) {
                val choices = json.decodeFromString<Map<String, UserPermissionChoice>>(choicesJson)
                userChoices.clear()
                userChoices.putAll(choices)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user choices", e)
        }
    }
    
    private fun saveUserChoicesToStorage() {
        try {
            val choicesJson = json.encodeToString(userChoices.toMap())
            sharedPreferences.edit()
                .putString(KEY_USER_CHOICES, choicesJson)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user choices", e)
        }
    }
    
    private fun loadRationaleShown() {
        try {
            val rationaleJson = sharedPreferences.getString(KEY_RATIONALE_SHOWN, null)
            if (rationaleJson != null) {
                val rationale = json.decodeFromString<Map<String, Boolean>>(rationaleJson)
                rationaleShown.clear()
                rationaleShown.putAll(rationale)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rationale shown", e)
        }
    }
    
    private fun saveRationaleShownToStorage() {
        try {
            val rationaleJson = json.encodeToString(rationaleShown.toMap())
            sharedPreferences.edit()
                .putString(KEY_RATIONALE_SHOWN, rationaleJson)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save rationale shown", e)
        }
    }
    
    private fun updateAnalytics() {
        analytics.incrementAndGet()
    }
    
    private fun updateFlows() {
        _permissionStatesFlow.value = permissionStates.toMap()
        _requestHistoryFlow.value = requestHistory.toList()
        _userChoicesFlow.value = userChoices.toMap()
    }
    
    private fun migratePreferences(fromVersion: Int, toVersion: Int) {
        Log.d(TAG, "Migrating preferences from version $fromVersion to $toVersion")
        // Add migration logic here when needed
        sharedPreferences.edit()
            .putInt(VERSION_KEY, toVersion)
            .apply()
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}

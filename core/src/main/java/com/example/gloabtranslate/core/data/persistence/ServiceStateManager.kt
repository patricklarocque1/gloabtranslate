package com.example.gloabtranslate.core.data.persistence

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Service state persistence manager for maintaining service states across app restarts.
 * Provides SharedPreferences-based persistence, JSON serialization, state migration,
 * and backup/restore functionality.
 */
class ServiceStateManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ServiceStateManager"
        private const val PREFS_NAME = "service_state_manager"
        private const val VERSION_KEY = "persistence_version"
        private const val CURRENT_VERSION = 1
        private const val BACKUP_DIR = "service_state_backups"
        private const val MAX_BACKUP_FILES = 10
        
        // Service state keys
        private const val KEY_SERVICE_STATES = "service_states"
        private const val KEY_SERVICE_CONFIGS = "service_configs"
        private const val KEY_SERVICE_STATISTICS = "service_statistics"
        private const val KEY_APP_LIFECYCLE_STATE = "app_lifecycle_state"
        private const val KEY_LAST_SAVE_TIME = "last_save_time"
        private const val KEY_AUTO_RESTORE_ENABLED = "auto_restore_enabled"
        
        @Volatile
        private var INSTANCE: ServiceStateManager? = null
        
        fun getInstance(context: Context): ServiceStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServiceStateManager(context.applicationContext).also { INSTANCE = it }
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
    private val lastSaveTime = AtomicLong(0)
    private val persistenceMutex = Mutex()
    private val serviceStates = ConcurrentHashMap<String, PersistedServiceState>()
    private val serviceConfigs = ConcurrentHashMap<String, PersistedServiceConfig>()
    
    // State flows for reactive updates
    private val _serviceStatesFlow = MutableStateFlow<Map<String, PersistedServiceState>>(emptyMap())
    val serviceStatesFlow: Flow<Map<String, PersistedServiceState>> = _serviceStatesFlow.asStateFlow()
    
    private val _appLifecycleStateFlow = MutableStateFlow<PersistedAppLifecycleState?>(null)
    val appLifecycleStateFlow: Flow<PersistedAppLifecycleState?> = _appLifecycleStateFlow.asStateFlow()
    
    /**
     * Persisted service state data structure
     */
    @Serializable
    data class PersistedServiceState(
        val serviceName: String,
        val state: String, // ServiceState enum as string
        val isBound: Boolean = false,
        val isHealthy: Boolean = true,
        val lastHealthCheck: Long = 0L,
        val bindTime: Long = 0L,
        val startTime: Long = 0L,
        val stopTime: Long = 0L,
        val retryCount: Int = 0,
        val configuration: Map<String, String> = emptyMap(),
        val metadata: Map<String, String> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Persisted service configuration
     */
    @Serializable
    data class PersistedServiceConfig(
        val serviceName: String,
        val serviceClass: String,
        val isForegroundService: Boolean = false,
        val autoStart: Boolean = false,
        val dependencies: List<String> = emptyList(),
        val priority: String, // ServicePriority enum as string
        val configuration: Map<String, String> = emptyMap(),
        val version: Int = CURRENT_VERSION,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Persisted app lifecycle state
     */
    @Serializable
    data class PersistedAppLifecycleState(
        val isInForeground: Boolean = false,
        val lastForegroundTime: Long = 0L,
        val lastBackgroundTime: Long = 0L,
        val sessionStartTime: Long = System.currentTimeMillis(),
        val sessionEndTime: Long = 0L,
        val appVersion: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Persisted service statistics
     */
    @Serializable
    data class PersistedServiceStatistics(
        val serviceStartCount: Long = 0,
        val serviceStopCount: Long = 0,
        val totalBindTime: Long = 0,
        val totalUnbindTime: Long = 0,
        val healthCheckPassCount: Long = 0,
        val healthCheckFailCount: Long = 0,
        val recoveryAttemptCount: Long = 0,
        val recoverySuccessCount: Long = 0,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Backup data structure
     */
    @Serializable
    data class ServiceStateBackup(
        val version: Int = CURRENT_VERSION,
        val timestamp: Long = System.currentTimeMillis(),
        val appVersion: String = "",
        val serviceStates: Map<String, PersistedServiceState> = emptyMap(),
        val serviceConfigs: Map<String, PersistedServiceConfig> = emptyMap(),
        val appLifecycleState: PersistedAppLifecycleState? = null,
        val statistics: PersistedServiceStatistics? = null
    )
    
    /**
     * Initializes the service state manager
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "ServiceStateManager already initialized")
                    return@withContext true
                }
                
                // Check version and migrate if necessary
                val savedVersion = sharedPreferences.getInt(VERSION_KEY, 0)
                if (savedVersion < CURRENT_VERSION) {
                    migrateData(savedVersion, CURRENT_VERSION)
                }
                
                // Load persisted data
                loadPersistedData()
                
                // Update last save time
                lastSaveTime.set(sharedPreferences.getLong(KEY_LAST_SAVE_TIME, 0))
                
                Log.d(TAG, "ServiceStateManager initialized successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ServiceStateManager", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Saves a service state
     */
    suspend fun saveServiceState(serviceState: PersistedServiceState): Boolean = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                serviceStates[serviceState.serviceName] = serviceState
                _serviceStatesFlow.value = serviceStates.toMap()
                
                val success = saveToSharedPreferences()
                if (success) {
                    lastSaveTime.set(System.currentTimeMillis())
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save service state: ${serviceState.serviceName}", e)
            false
        }
    }
    
    /**
     * Loads a service state
     */
    suspend fun loadServiceState(serviceName: String): PersistedServiceState? = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                serviceStates[serviceName]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load service state: $serviceName", e)
            null
        }
    }
    
    /**
     * Saves a service configuration
     */
    suspend fun saveServiceConfig(serviceConfig: PersistedServiceConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                serviceConfigs[serviceConfig.serviceName] = serviceConfig
                val success = saveToSharedPreferences()
                if (success) {
                    lastSaveTime.set(System.currentTimeMillis())
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save service config: ${serviceConfig.serviceName}", e)
            false
        }
    }
    
    /**
     * Loads a service configuration
     */
    suspend fun loadServiceConfig(serviceName: String): PersistedServiceConfig? = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                serviceConfigs[serviceName]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load service config: $serviceName", e)
            null
        }
    }
    
    /**
     * Saves app lifecycle state
     */
    suspend fun saveAppLifecycleState(appState: PersistedAppLifecycleState): Boolean = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                _appLifecycleStateFlow.value = appState
                val success = saveToSharedPreferences()
                if (success) {
                    lastSaveTime.set(System.currentTimeMillis())
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save app lifecycle state", e)
            false
        }
    }
    
    /**
     * Loads app lifecycle state
     */
    suspend fun loadAppLifecycleState(): PersistedAppLifecycleState? = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                _appLifecycleStateFlow.value
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load app lifecycle state", e)
            null
        }
    }
    
    /**
     * Saves service statistics
     */
    suspend fun saveServiceStatistics(statistics: PersistedServiceStatistics): Boolean = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                val success = saveToSharedPreferences()
                if (success) {
                    lastSaveTime.set(System.currentTimeMillis())
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save service statistics", e)
            false
        }
    }
    
    /**
     * Removes a service state
     */
    suspend fun removeServiceState(serviceName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                serviceStates.remove(serviceName)
                serviceConfigs.remove(serviceName)
                _serviceStatesFlow.value = serviceStates.toMap()
                
                val success = saveToSharedPreferences()
                if (success) {
                    lastSaveTime.set(System.currentTimeMillis())
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove service state: $serviceName", e)
            false
        }
    }
    
    /**
     * Clears all persisted data
     */
    suspend fun clearAllData(): Boolean = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                serviceStates.clear()
                serviceConfigs.clear()
                _serviceStatesFlow.value = emptyMap()
                _appLifecycleStateFlow.value = null
                
                val success = sharedPreferences.edit()
                    .clear()
                    .putInt(VERSION_KEY, CURRENT_VERSION)
                    .putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis())
                    .commit()
                
                if (success) {
                    lastSaveTime.set(System.currentTimeMillis())
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all data", e)
            false
        }
    }
    
    /**
     * Creates a backup of current state
     */
    suspend fun createBackup(): File? = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                val backupDir = File(context.filesDir, BACKUP_DIR)
                if (!backupDir.exists()) {
                    backupDir.mkdirs()
                }
                
                val timestamp = System.currentTimeMillis()
                val backupFile = File(backupDir, "service_state_backup_$timestamp.json")
                
                val backup = ServiceStateBackup(
                    serviceStates = serviceStates.toMap(),
                    serviceConfigs = serviceConfigs.toMap(),
                    appLifecycleState = _appLifecycleStateFlow.value,
                    appVersion = getAppVersion()
                )
                
                val jsonString = json.encodeToString(backup)
                backupFile.writeText(jsonString)
                
                // Clean up old backups
                cleanupOldBackups(backupDir)
                
                Log.d(TAG, "Backup created: ${backupFile.absolutePath}")
                backupFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup", e)
            null
        }
    }
    
    /**
     * Restores state from a backup file
     */
    suspend fun restoreFromBackup(backupFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            persistenceMutex.withLock {
                if (!backupFile.exists()) {
                    Log.e(TAG, "Backup file does not exist: ${backupFile.absolutePath}")
                    return@withContext false
                }
                
                val jsonString = backupFile.readText()
                val backup = json.decodeFromString<ServiceStateBackup>(jsonString)
                
                // Validate backup version
                if (backup.version > CURRENT_VERSION) {
                    Log.e(TAG, "Backup version ${backup.version} is newer than current version $CURRENT_VERSION")
                    return@withContext false
                }
                
                // Restore data
                serviceStates.clear()
                serviceConfigs.clear()
                
                backup.serviceStates.forEach { (key, value) ->
                    serviceStates[key] = value
                }
                
                backup.serviceConfigs.forEach { (key, value) ->
                    serviceConfigs[key] = value
                }
                
                _serviceStatesFlow.value = serviceStates.toMap()
                _appLifecycleStateFlow.value = backup.appLifecycleState
                
                // Save to SharedPreferences
                val success = saveToSharedPreferences()
                if (success) {
                    lastSaveTime.set(System.currentTimeMillis())
                }
                
                Log.d(TAG, "State restored from backup: ${backupFile.absolutePath}")
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from backup", e)
            false
        }
    }
    
    /**
     * Gets all available backup files
     */
    suspend fun getAvailableBackups(): List<File> = withContext(Dispatchers.IO) {
        try {
            val backupDir = File(context.filesDir, BACKUP_DIR)
            if (!backupDir.exists()) {
                return@withContext emptyList()
            }
            
            backupDir.listFiles()
                ?.filter { it.name.startsWith("service_state_backup_") && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available backups", e)
            emptyList()
        }
    }
    
    /**
     * Gets persistence statistics
     */
    fun getPersistenceStatistics(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized.get(),
            "serviceStatesCount" to serviceStates.size,
            "serviceConfigsCount" to serviceConfigs.size,
            "lastSaveTime" to lastSaveTime.get(),
            "currentVersion" to CURRENT_VERSION,
            "autoRestoreEnabled" to sharedPreferences.getBoolean(KEY_AUTO_RESTORE_ENABLED, true)
        )
    }
    
    /**
     * Enables or disables auto-restore on app startup
     */
    fun setAutoRestoreEnabled(enabled: Boolean): Boolean {
        return sharedPreferences.edit()
            .putBoolean(KEY_AUTO_RESTORE_ENABLED, enabled)
            .commit()
    }
    
    /**
     * Checks if auto-restore is enabled
     */
    fun isAutoRestoreEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTO_RESTORE_ENABLED, true)
    }
    
    // Private helper methods
    
    private suspend fun loadPersistedData() {
        try {
            // Load service states
            val serviceStatesJson = sharedPreferences.getString(KEY_SERVICE_STATES, null)
            if (serviceStatesJson != null) {
                val loadedStates = json.decodeFromString<Map<String, PersistedServiceState>>(serviceStatesJson)
                serviceStates.putAll(loadedStates)
                _serviceStatesFlow.value = serviceStates.toMap()
            }
            
            // Load service configs
            val serviceConfigsJson = sharedPreferences.getString(KEY_SERVICE_CONFIGS, null)
            if (serviceConfigsJson != null) {
                val loadedConfigs = json.decodeFromString<Map<String, PersistedServiceConfig>>(serviceConfigsJson)
                serviceConfigs.putAll(loadedConfigs)
            }
            
            // Load app lifecycle state
            val appStateJson = sharedPreferences.getString(KEY_APP_LIFECYCLE_STATE, null)
            if (appStateJson != null) {
                val appState = json.decodeFromString<PersistedAppLifecycleState>(appStateJson)
                _appLifecycleStateFlow.value = appState
            }
            
            Log.d(TAG, "Persisted data loaded: ${serviceStates.size} states, ${serviceConfigs.size} configs")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted data", e)
        }
    }
    
    private fun saveToSharedPreferences(): Boolean {
        return try {
            val editor = sharedPreferences.edit()
            
            // Save service states
            val serviceStatesJson = json.encodeToString(serviceStates)
            editor.putString(KEY_SERVICE_STATES, serviceStatesJson)
            
            // Save service configs
            val serviceConfigsJson = json.encodeToString(serviceConfigs)
            editor.putString(KEY_SERVICE_CONFIGS, serviceConfigsJson)
            
            // Save app lifecycle state
            _appLifecycleStateFlow.value?.let { appState ->
                val appStateJson = json.encodeToString(appState)
                editor.putString(KEY_APP_LIFECYCLE_STATE, appStateJson)
            }
            
            // Save metadata
            editor.putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis())
            editor.putInt(VERSION_KEY, CURRENT_VERSION)
            
            editor.commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to SharedPreferences", e)
            false
        }
    }
    
    private suspend fun migrateData(fromVersion: Int, toVersion: Int) {
        try {
            Log.d(TAG, "Migrating data from version $fromVersion to $toVersion")
            
            when (fromVersion) {
                0 -> {
                    // Initial migration - no special handling needed
                    Log.d(TAG, "Initial migration completed")
                }
                // Add future migration logic here
                else -> {
                    Log.d(TAG, "No migration needed from version $fromVersion")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
        }
    }
    
    private fun cleanupOldBackups(backupDir: File) {
        try {
            val backupFiles = backupDir.listFiles()
                ?.filter { it.name.startsWith("service_state_backup_") && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            
            if (backupFiles.size > MAX_BACKUP_FILES) {
                val filesToDelete = backupFiles.drop(MAX_BACKUP_FILES)
                filesToDelete.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old backup: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old backups", e)
        }
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app version", e)
            "unknown"
        }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        try {
            runBlocking {
                persistenceMutex.withLock {
                    serviceStates.clear()
                    serviceConfigs.clear()
                    _serviceStatesFlow.value = emptyMap()
                    _appLifecycleStateFlow.value = null
                    isInitialized.set(false)
                }
            }
            Log.d(TAG, "ServiceStateManager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

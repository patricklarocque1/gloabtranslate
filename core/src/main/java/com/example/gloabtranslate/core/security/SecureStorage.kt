package com.example.gloabtranslate.core.security

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
import com.example.gloabtranslate.core.security.DataEncryption
import com.example.gloabtranslate.core.security.DataEncryption.EncryptionAlgorithm
import com.example.gloabtranslate.core.security.DataEncryption.DataSensitivity


/**
 * Secure storage system for user preferences and sensitive data.
 * Provides encrypted storage with automatic key management, data integrity verification,
 * and secure backup/restore capabilities.
 */
class SecureStorage private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SecureStorage"
        private const val SECURE_PREFS_NAME = "secure_preferences"
        private const val VERSION_KEY = "secure_storage_version"
        private const val CURRENT_VERSION = 1
        private const val MAX_CACHE_SIZE = 100
        
        // Storage categories
        enum class StorageCategory {
            USER_PREFERENCES,    // User settings and preferences
            AUTHENTICATION,      // Authentication tokens and credentials
            PERSONAL_DATA,       // Personal information
            TRANSLATION_DATA,    // Translation history and data
            ANALYTICS,          // Analytics and usage data
            SYSTEM_DATA         // System configuration
        }
        
        // Data types
        enum class DataType {
            STRING,
            INTEGER,
            BOOLEAN,
            FLOAT,
            LONG,
            JSON_OBJECT,
            ENCRYPTED_STRING,
            SECURE_TOKEN
        }
        
        @Volatile
        private var INSTANCE: SecureStorage? = null
        
        fun getInstance(context: Context): SecureStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val dataEncryption = DataEncryption.getInstance(context)
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val storageMutex = Mutex()
    private val secureCache = ConcurrentHashMap<String, SecureDataItem>()
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val storageListeners = CopyOnWriteArrayList<StorageListener>()
    
    // State flows for reactive updates
    private val _storageFlow = MutableStateFlow<Map<String, SecureDataItem>>(emptyMap())
    val storageFlow: Flow<Map<String, SecureDataItem>> = _storageFlow.asStateFlow()
    
    private val _storageEventsFlow = MutableStateFlow<StorageEvent?>(null)
    val storageEventsFlow: Flow<StorageEvent?> = _storageEventsFlow.asStateFlow()
    
    /**
     * Secure data item structure
     */
    @Serializable
    data class SecureDataItem(
        val key: String,
        val value: String,
        val dataType: String,
        val category: String,
        val isEncrypted: Boolean = false,
        val encryptedValue: String? = null,
        val checksum: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val expiresAt: Long? = null,
        val accessCount: Int = 0,
        val lastAccessed: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * Storage event for tracking and analytics
     */
    data class StorageEvent(
        val eventType: String, // "stored", "retrieved", "deleted", "expired"
        val key: String,
        val category: StorageCategory,
        val timestamp: Long = System.currentTimeMillis(),
        val success: Boolean = true,
        val errorMessage: String? = null,
        val dataSize: Int = 0
    )
    
    /**
     * Storage listener interface
     */
    interface StorageListener {
        fun onDataStored(key: String, category: StorageCategory, success: Boolean)
        fun onDataRetrieved(key: String, category: StorageCategory, success: Boolean)
        fun onDataDeleted(key: String, category: StorageCategory, success: Boolean)
        fun onDataExpired(key: String, category: StorageCategory)
        fun onStorageError(key: String, category: StorageCategory, error: String)
    }
    
    /**
     * Initialize the secure storage system
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "SecureStorage already initialized")
                    return@withContext true
                }
                
                // Initialize data encryption
                val encryptionInitialized = dataEncryption.initialize()
                if (!encryptionInitialized) {
                    Log.e(TAG, "Failed to initialize data encryption")
                    return@withContext false
                }
                
                // Load secure data from storage
                loadSecureData()
                
                // Check version and migrate if necessary
                val savedVersion = sharedPreferences.getInt(VERSION_KEY, 0)
                if (savedVersion < CURRENT_VERSION) {
                    migrateSecureStorage(savedVersion, CURRENT_VERSION)
                }
                
                // Clean expired data
                cleanExpiredData()
                
                // Update flows
                updateFlows()
                
                Log.d(TAG, "SecureStorage initialized successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SecureStorage", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Store secure data
     */
    suspend fun storeSecureData(
        key: String,
        value: String,
        category: StorageCategory,
        dataType: DataType = DataType.STRING,
        encrypt: Boolean = false,
        expiresAt: Long? = null,
        metadata: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                val checksum = dataEncryption.generateHash(value)
                val encryptedValue = if (encrypt) {
                    val encryptionResult = dataEncryption.encrypt(
                        data = value,
                        algorithm = EncryptionAlgorithm.AES_GCM,
                        sensitivity = getSensitivityForCategory(category)
                    )
                    if (encryptionResult.isSuccess) {
                        encryptionResult.encryptedData
                    } else {
                        Log.e(TAG, "Failed to encrypt data for key: $key")
                        null
                    }
                } else {
                    null
                }
                
                val secureItem = SecureDataItem(
                    key = key,
                    value = if (encrypt) "" else value,
                    dataType = dataType.name,
                    category = category.name,
                    isEncrypted = encrypt,
                    encryptedValue = encryptedValue,
                    checksum = checksum,
                    expiresAt = expiresAt,
                    metadata = metadata
                )
                
                // Store in cache
                secureCache[key] = secureItem
                
                // Store in SharedPreferences
                val itemJson = json.encodeToString(secureItem)
                sharedPreferences.edit()
                    .putString(key, itemJson)
                    .apply()
                
                // Record event
                recordStorageEvent(
                    eventType = "stored",
                    key = key,
                    category = category,
                    dataSize = value.length
                )
                
                // Notify listeners
                notifyDataStored(key, category, true)
                
                updateFlows()
                
                Log.d(TAG, "Secure data stored: $key")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store secure data: $key", e)
            notifyStorageError(key, category, e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * Retrieve secure data
     */
    suspend fun retrieveSecureData(
        key: String,
        category: StorageCategory
    ): String? = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                // Check cache first
                val cachedItem = secureCache[key]
                if (cachedItem != null) {
                    cacheHits.incrementAndGet()
                    
                    // Check expiration
                    if (cachedItem.expiresAt != null && System.currentTimeMillis() > cachedItem.expiresAt) {
                        deleteSecureData(key, category)
                        notifyDataExpired(key, category)
                        return@withContext null
                    }
                    
                    // Update access tracking
                    val updatedItem = cachedItem.copy(
                        accessCount = cachedItem.accessCount + 1,
                        lastAccessed = System.currentTimeMillis()
                    )
                    secureCache[key] = updatedItem
                    
                    val decryptedValue = if (cachedItem.isEncrypted && cachedItem.encryptedValue != null) {
                        val decryptionResult = dataEncryption.decrypt(
                            encryptedData = cachedItem.encryptedValue,
                            algorithm = EncryptionAlgorithm.AES_GCM
                        )
                        if (decryptionResult.isSuccess) {
                            decryptionResult.decryptedData
                        } else {
                            Log.e(TAG, "Failed to decrypt data for key: $key")
                            null
                        }
                    } else {
                        cachedItem.value
                    }
                    
                    // Verify checksum
                    if (decryptedValue != null && cachedItem.checksum != null) {
                        val currentChecksum = dataEncryption.generateHash(decryptedValue)
                        if (currentChecksum != cachedItem.checksum) {
                            Log.w(TAG, "Checksum verification failed for key: $key")
                            return@withContext null
                        }
                    }
                    
                    recordStorageEvent("retrieved", key, category, dataSize = decryptedValue?.length ?: 0)
                    notifyDataRetrieved(key, category, true)
                    
                    return@withContext decryptedValue
                }
                
                // Cache miss - load from SharedPreferences
                cacheMisses.incrementAndGet()
                val itemJson = sharedPreferences.getString(key, null)
                if (itemJson != null) {
                    val secureItem = json.decodeFromString<SecureDataItem>(itemJson)
                    secureCache[key] = secureItem
                    
                    // Check expiration
                    if (secureItem.expiresAt != null && System.currentTimeMillis() > secureItem.expiresAt) {
                        deleteSecureData(key, category)
                        notifyDataExpired(key, category)
                        return@withContext null
                    }
                    
                    val decryptedValue = if (secureItem.isEncrypted && secureItem.encryptedValue != null) {
                        val decryptionResult = dataEncryption.decrypt(
                            encryptedData = secureItem.encryptedValue,
                            algorithm = EncryptionAlgorithm.AES_GCM
                        )
                        if (decryptionResult.isSuccess) {
                            decryptionResult.decryptedData
                        } else {
                            Log.e(TAG, "Failed to decrypt data for key: $key")
                            null
                        }
                    } else {
                        secureItem.value
                    }
                    
                    recordStorageEvent("retrieved", key, category, dataSize = decryptedValue?.length ?: 0)
                    notifyDataRetrieved(key, category, true)
                    
                    return@withContext decryptedValue
                }
                
                recordStorageEvent("retrieved", key, category, success = false)
                notifyDataRetrieved(key, category, false)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve secure data: $key", e)
            notifyStorageError(key, category, e.message ?: "Unknown error")
            null
        }
    }
    
    /**
     * Delete secure data
     */
    suspend fun deleteSecureData(
        key: String,
        category: StorageCategory
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                // Remove from cache
                secureCache.remove(key)
                
                // Remove from SharedPreferences
                sharedPreferences.edit()
                    .remove(key)
                    .apply()
                
                recordStorageEvent("deleted", key, category)
                notifyDataDeleted(key, category, true)
                updateFlows()
                
                Log.d(TAG, "Secure data deleted: $key")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete secure data: $key", e)
            notifyStorageError(key, category, e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * Check if secure data exists
     */
    suspend fun containsSecureData(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                secureCache.containsKey(key) || sharedPreferences.contains(key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check secure data existence: $key", e)
            false
        }
    }
    
    /**
     * Get all keys for a category
     */
    suspend fun getKeysForCategory(category: StorageCategory): List<String> = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                secureCache.values
                    .filter { it.category == category.name }
                    .map { it.key }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get keys for category: $category", e)
            emptyList()
        }
    }
    
    /**
     * Clear all data for a category
     */
    suspend fun clearCategory(category: StorageCategory): Boolean = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                val keysToRemove = getKeysForCategory(category)
                keysToRemove.forEach { key ->
                    deleteSecureData(key, category)
                }
                
                Log.d(TAG, "Cleared category: $category")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear category: $category", e)
            false
        }
    }
    
    /**
     * Get storage statistics
     */
    suspend fun getStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                val totalItems = secureCache.size
                val encryptedItems = secureCache.values.count { it.isEncrypted }
                val expiredItems = secureCache.values.count { 
                    it.expiresAt != null && System.currentTimeMillis() > it.expiresAt 
                }
                
                val categoryCounts = secureCache.values
                    .groupingBy { it.category }
                    .eachCount()
                
                StorageStats(
                    totalItems = totalItems,
                    encryptedItems = encryptedItems,
                    expiredItems = expiredItems,
                    cacheHits = cacheHits.get(),
                    cacheMisses = cacheMisses.get(),
                    categoryCounts = categoryCounts,
                    cacheSize = secureCache.size
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage stats", e)
            StorageStats()
        }
    }
    
    /**
     * Backup secure data
     */
    suspend fun backupSecureData(): BackupData? = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                val backupItems = secureCache.values.toList()
                val backupJson = json.encodeToString(backupItems)
                
                BackupData(
                    timestamp = System.currentTimeMillis(),
                    version = CURRENT_VERSION,
                    itemCount = backupItems.size,
                    data = backupJson
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup secure data", e)
            null
        }
    }
    
    /**
     * Restore secure data from backup
     */
    suspend fun restoreSecureData(backupData: BackupData): Boolean = withContext(Dispatchers.IO) {
        try {
            storageMutex.withLock {
                val backupItems = json.decodeFromString<List<SecureDataItem>>(backupData.data)
                
                // Clear existing data
                secureCache.clear()
                sharedPreferences.edit().clear().apply()
                
                // Restore data
                backupItems.forEach { item ->
                    secureCache[item.key] = item
                    val itemJson = json.encodeToString(item)
                    sharedPreferences.edit()
                        .putString(item.key, itemJson)
                        .apply()
                }
                
                updateFlows()
                
                Log.d(TAG, "Secure data restored from backup")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore secure data", e)
            false
        }
    }
    
    // Private helper methods
    
    private fun loadSecureData() {
        try {
            val allKeys = sharedPreferences.all.keys
            allKeys.forEach { key ->
                if (key != VERSION_KEY) {
                    val itemJson = sharedPreferences.getString(key, null)
                    if (itemJson != null) {
                        try {
                            val secureItem = json.decodeFromString<SecureDataItem>(itemJson)
                            secureCache[key] = secureItem
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load secure item: $key", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load secure data", e)
        }
    }
    
    private fun cleanExpiredData() {
        try {
            val expiredKeys = secureCache.values
                .filter { it.expiresAt != null && System.currentTimeMillis() > it.expiresAt }
                .map { it.key }
            
            // Note: This would typically be handled by launching a coroutine
            // For now, we'll just remove from cache
            expiredKeys.forEach { key ->
                secureCache.remove(key)
                Log.d(TAG, "Removed expired data for key: $key")
            }
            
            if (expiredKeys.isNotEmpty()) {
                Log.d(TAG, "Cleaned ${expiredKeys.size} expired items")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean expired data", e)
        }
    }
    
    private fun getSensitivityForCategory(category: StorageCategory): DataSensitivity {
        return when (category) {
            StorageCategory.USER_PREFERENCES -> DataSensitivity.LOW
            StorageCategory.AUTHENTICATION -> DataSensitivity.CRITICAL
            StorageCategory.PERSONAL_DATA -> DataSensitivity.HIGH
            StorageCategory.TRANSLATION_DATA -> DataSensitivity.MEDIUM
            StorageCategory.ANALYTICS -> DataSensitivity.LOW
            StorageCategory.SYSTEM_DATA -> DataSensitivity.LOW
        }
    }
    
    private fun migrateSecureStorage(fromVersion: Int, toVersion: Int) {
        Log.d(TAG, "Migrating secure storage from version $fromVersion to $toVersion")
        // Add migration logic here when needed
        sharedPreferences.edit()
            .putInt(VERSION_KEY, toVersion)
            .apply()
    }
    
    private fun updateFlows() {
        _storageFlow.value = secureCache.toMap()
    }
    
    private fun recordStorageEvent(
        eventType: String,
        key: String,
        category: StorageCategory,
        success: Boolean = true,
        errorMessage: String? = null,
        dataSize: Int = 0
    ) {
        val event = StorageEvent(
            eventType = eventType,
            key = key,
            category = category,
            success = success,
            errorMessage = errorMessage,
            dataSize = dataSize
        )
        _storageEventsFlow.value = event
    }
    
    // Listener management
    
    fun addStorageListener(listener: StorageListener) {
        storageListeners.add(listener)
    }
    
    fun removeStorageListener(listener: StorageListener) {
        storageListeners.remove(listener)
    }
    
    private fun notifyDataStored(key: String, category: StorageCategory, success: Boolean) {
        storageListeners.forEach { listener ->
            try {
                listener.onDataStored(key, category, success)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying data stored", e)
            }
        }
    }
    
    private fun notifyDataRetrieved(key: String, category: StorageCategory, success: Boolean) {
        storageListeners.forEach { listener ->
            try {
                listener.onDataRetrieved(key, category, success)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying data retrieved", e)
            }
        }
    }
    
    private fun notifyDataDeleted(key: String, category: StorageCategory, success: Boolean) {
        storageListeners.forEach { listener ->
            try {
                listener.onDataDeleted(key, category, success)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying data deleted", e)
            }
        }
    }
    
    private fun notifyDataExpired(key: String, category: StorageCategory) {
        storageListeners.forEach { listener ->
            try {
                listener.onDataExpired(key, category)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying data expired", e)
            }
        }
    }
    
    private fun notifyStorageError(key: String, category: StorageCategory, error: String) {
        storageListeners.forEach { listener ->
            try {
                listener.onStorageError(key, category, error)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying storage error", e)
            }
        }
    }
    
    /**
     * Storage statistics data class
     */
    data class StorageStats(
        val totalItems: Int = 0,
        val encryptedItems: Int = 0,
        val expiredItems: Int = 0,
        val cacheHits: Long = 0,
        val cacheMisses: Long = 0,
        val categoryCounts: Map<String, Int> = emptyMap(),
        val cacheSize: Int = 0
    )
    
    /**
     * Backup data structure
     */
    @Serializable
    data class BackupData(
        val timestamp: Long,
        val version: Int,
        val itemCount: Int,
        val data: String
    )
}

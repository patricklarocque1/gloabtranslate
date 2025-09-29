package com.example.gloabtranslate.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive data encryption system for sensitive data protection.
 * Provides AES encryption with Android Keystore integration, secure key management,
 * and multiple encryption algorithms for different security requirements.
 */
class DataEncryption private constructor(private val context: Context) {

    // Encryption algorithms
    enum class EncryptionAlgorithm {
        AES_GCM,    // Authenticated encryption (recommended)
        AES_CBC,    // Standard AES with PKCS5 padding
        AES_ECB     // Electronic Codebook (less secure, for compatibility)
    }
    
    // Data sensitivity levels
    enum class DataSensitivity {
        LOW,        // Non-sensitive data (logs, preferences)
        MEDIUM,     // User data (settings, preferences)
        HIGH,       // Personal data (translation history)
        CRITICAL    // Highly sensitive data (authentication tokens)
    }
    
    companion object {
        private const val TAG = "DataEncryption"
        private const val KEYSTORE_ALIAS = "GloabTranslateEncryptionKey"
        private const val TRANSFORMATION_AES_GCM = "AES/GCM/NoPadding"
        private const val TRANSFORMATION_AES_CBC = "AES/CBC/PKCS5Padding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        private const val CBC_IV_LENGTH = 16
        private const val KEY_SIZE = 256
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        
        @Volatile
        private var INSTANCE: DataEncryption? = null
        
        fun getInstance(context: Context): DataEncryption {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DataEncryption(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val isInitialized = AtomicBoolean(false)
    private val encryptionMutex = Mutex()
    private val keyCache = ConcurrentHashMap<String, SecretKey>()
    private val encryptionCount = AtomicLong(0)
    private val decryptionCount = AtomicLong(0)
    private val secureRandom = SecureRandom()
    
    // Keystore for secure key storage
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    }
    
    // Encryption statistics
    data class EncryptionStats(
        val totalEncryptions: Long,
        val totalDecryptions: Long,
        val keyCacheSize: Int,
        val isKeystoreAvailable: Boolean,
        val supportedAlgorithms: List<EncryptionAlgorithm>
    )
    
    /**
     * Initialize the encryption system
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            encryptionMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "DataEncryption already initialized")
                    return@withContext true
                }
                
                // Initialize Android Keystore key
                initializeKeystoreKey()
                
                // Verify encryption capabilities
                verifyEncryptionCapabilities()
                
                Log.d(TAG, "DataEncryption initialized successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DataEncryption", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Encrypt data with specified algorithm and sensitivity level
     */
    suspend fun encrypt(
        data: String,
        algorithm: EncryptionAlgorithm = EncryptionAlgorithm.AES_GCM,
        sensitivity: DataSensitivity = DataSensitivity.MEDIUM,
        keyAlias: String? = null
    ): EncryptionResult = withContext(Dispatchers.IO) {
        try {
            encryptionMutex.withLock {
                val key = getOrCreateKey(keyAlias ?: getKeyAliasForSensitivity(sensitivity), algorithm)
                val cipher = Cipher.getInstance(getTransformation(algorithm))
                
                when (algorithm) {
                    EncryptionAlgorithm.AES_GCM -> encryptWithGCM(data, cipher, key)
                    EncryptionAlgorithm.AES_CBC -> encryptWithCBC(data, cipher, key)
                    EncryptionAlgorithm.AES_ECB -> encryptWithECB(data, cipher, key)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt data", e)
            EncryptionResult.error("Encryption failed: ${e.message}")
        }
    }
    
    /**
     * Decrypt data with specified algorithm
     */
    suspend fun decrypt(
        encryptedData: String,
        algorithm: EncryptionAlgorithm = EncryptionAlgorithm.AES_GCM,
        keyAlias: String? = null
    ): DecryptionResult = withContext(Dispatchers.IO) {
        try {
            encryptionMutex.withLock {
                val key = getOrCreateKey(keyAlias ?: KEYSTORE_ALIAS, algorithm)
                val cipher = Cipher.getInstance(getTransformation(algorithm))
                
                when (algorithm) {
                    EncryptionAlgorithm.AES_GCM -> decryptWithGCM(encryptedData, cipher, key)
                    EncryptionAlgorithm.AES_CBC -> decryptWithCBC(encryptedData, cipher, key)
                    EncryptionAlgorithm.AES_ECB -> decryptWithECB(encryptedData, cipher, key)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt data", e)
            DecryptionResult.error("Decryption failed: ${e.message}")
        }
    }
    
    /**
     * Encrypt data with GCM (Galois/Counter Mode) - Authenticated encryption
     */
    private fun encryptWithGCM(data: String, cipher: Cipher, key: SecretKey): EncryptionResult {
        try {
            // Generate random IV
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)
            
            // Initialize cipher with IV
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)
            
            // Encrypt data
            val encryptedBytes = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            
            // Combine IV and encrypted data
            val combined = iv + encryptedBytes
            
            encryptionCount.incrementAndGet()
            
            return EncryptionResult.success(
                encryptedData = Base64.encodeToString(combined, Base64.DEFAULT),
                algorithm = EncryptionAlgorithm.AES_GCM,
                iv = Base64.encodeToString(iv, Base64.DEFAULT),
                keyAlias = KEYSTORE_ALIAS
            )
        } catch (e: Exception) {
            Log.e(TAG, "GCM encryption failed", e)
            return EncryptionResult.error("GCM encryption failed: ${e.message}")
        }
    }
    
    /**
     * Decrypt data with GCM
     */
    private fun decryptWithGCM(encryptedData: String, cipher: Cipher, key: SecretKey): DecryptionResult {
        try {
            // Decode base64
            val combined = Base64.decode(encryptedData, Base64.DEFAULT)
            
            // Extract IV and encrypted data
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            
            // Initialize cipher with IV
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)
            
            // Decrypt data
            val decryptedBytes = cipher.doFinal(encrypted)
            val decryptedString = String(decryptedBytes, StandardCharsets.UTF_8)
            
            decryptionCount.incrementAndGet()
            
            return DecryptionResult.success(decryptedString)
        } catch (e: Exception) {
            Log.e(TAG, "GCM decryption failed", e)
            return DecryptionResult.error("GCM decryption failed: ${e.message}")
        }
    }
    
    /**
     * Encrypt data with CBC (Cipher Block Chaining)
     */
    private fun encryptWithCBC(data: String, cipher: Cipher, key: SecretKey): EncryptionResult {
        try {
            // Generate random IV
            val iv = ByteArray(CBC_IV_LENGTH)
            secureRandom.nextBytes(iv)
            
            // Initialize cipher with IV
            val parameterSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)
            
            // Encrypt data
            val encryptedBytes = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            
            // Combine IV and encrypted data
            val combined = iv + encryptedBytes
            
            encryptionCount.incrementAndGet()
            
            return EncryptionResult.success(
                encryptedData = Base64.encodeToString(combined, Base64.DEFAULT),
                algorithm = EncryptionAlgorithm.AES_CBC,
                iv = Base64.encodeToString(iv, Base64.DEFAULT),
                keyAlias = KEYSTORE_ALIAS
            )
        } catch (e: Exception) {
            Log.e(TAG, "CBC encryption failed", e)
            return EncryptionResult.error("CBC encryption failed: ${e.message}")
        }
    }
    
    /**
     * Decrypt data with CBC
     */
    private fun decryptWithCBC(encryptedData: String, cipher: Cipher, key: SecretKey): DecryptionResult {
        try {
            // Decode base64
            val combined = Base64.decode(encryptedData, Base64.DEFAULT)
            
            // Extract IV and encrypted data
            val iv = combined.copyOfRange(0, CBC_IV_LENGTH)
            val encrypted = combined.copyOfRange(CBC_IV_LENGTH, combined.size)
            
            // Initialize cipher with IV
            val parameterSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)
            
            // Decrypt data
            val decryptedBytes = cipher.doFinal(encrypted)
            val decryptedString = String(decryptedBytes, StandardCharsets.UTF_8)
            
            decryptionCount.incrementAndGet()
            
            return DecryptionResult.success(decryptedString)
        } catch (e: Exception) {
            Log.e(TAG, "CBC decryption failed", e)
            return DecryptionResult.error("CBC decryption failed: ${e.message}")
        }
    }
    
    /**
     * Encrypt data with ECB (Electronic Codebook) - Less secure, for compatibility
     */
    private fun encryptWithECB(data: String, cipher: Cipher, key: SecretKey): EncryptionResult {
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedBytes = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            
            encryptionCount.incrementAndGet()
            
            return EncryptionResult.success(
                encryptedData = Base64.encodeToString(encryptedBytes, Base64.DEFAULT),
                algorithm = EncryptionAlgorithm.AES_ECB,
                iv = null,
                keyAlias = KEYSTORE_ALIAS
            )
        } catch (e: Exception) {
            Log.e(TAG, "ECB encryption failed", e)
            return EncryptionResult.error("ECB encryption failed: ${e.message}")
        }
    }
    
    /**
     * Decrypt data with ECB
     */
    private fun decryptWithECB(encryptedData: String, cipher: Cipher, key: SecretKey): DecryptionResult {
        try {
            cipher.init(Cipher.DECRYPT_MODE, key)
            val encryptedBytes = Base64.decode(encryptedData, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val decryptedString = String(decryptedBytes, StandardCharsets.UTF_8)
            
            decryptionCount.incrementAndGet()
            
            return DecryptionResult.success(decryptedString)
        } catch (e: Exception) {
            Log.e(TAG, "ECB decryption failed", e)
            return DecryptionResult.error("ECB decryption failed: ${e.message}")
        }
    }
    
    /**
     * Generate a secure hash for data integrity
     */
    fun generateHash(data: String, algorithm: String = "SHA-256"): String {
        return try {
            val digest = MessageDigest.getInstance(algorithm)
            val hashBytes = digest.digest(data.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(hashBytes, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate hash", e)
            ""
        }
    }
    
    /**
     * Generate a secure random string
     */
    fun generateSecureRandomString(length: Int = 32): String {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }
    
    /**
     * Get or create encryption key
     */
    private fun getOrCreateKey(keyAlias: String, algorithm: EncryptionAlgorithm): SecretKey {
        return keyCache[keyAlias] ?: run {
            val key = if (keyStore.containsAlias(keyAlias)) {
                keyStore.getKey(keyAlias, null) as SecretKey
            } else {
                generateNewKey(keyAlias, algorithm)
            }
            keyCache[keyAlias] = key
            key
        }
    }
    
    /**
     * Generate new encryption key
     */
    private fun generateNewKey(keyAlias: String, algorithm: EncryptionAlgorithm): SecretKey {
        return try {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM, KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setKeySize(KEY_SIZE)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build()
            
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key, using fallback", e)
            // Fallback to in-memory key
            val keyBytes = ByteArray(32) // 256 bits
            secureRandom.nextBytes(keyBytes)
            SecretKeySpec(keyBytes, "AES")
        }
    }
    
    /**
     * Initialize Android Keystore key
     */
    private fun initializeKeystoreKey() {
        try {
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM, KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setKeySize(KEY_SIZE)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
                
                Log.d(TAG, "Android Keystore key created successfully")
            } else {
                Log.d(TAG, "Android Keystore key already exists")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Android Keystore key", e)
        }
    }
    
    /**
     * Verify encryption capabilities
     */
    private fun verifyEncryptionCapabilities() {
        try {
            // Test GCM encryption
            val testData = "test_data"
            val encrypted = encryptWithGCM(testData, Cipher.getInstance(TRANSFORMATION_AES_GCM), getOrCreateKey(KEYSTORE_ALIAS, EncryptionAlgorithm.AES_GCM))
            if (encrypted.isSuccess) {
                Log.d(TAG, "GCM encryption verified")
            }
            
            // Test CBC encryption
            val encryptedCBC = encryptWithCBC(testData, Cipher.getInstance(TRANSFORMATION_AES_CBC), getOrCreateKey(KEYSTORE_ALIAS, EncryptionAlgorithm.AES_CBC))
            if (encryptedCBC.isSuccess) {
                Log.d(TAG, "CBC encryption verified")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Encryption capability verification failed", e)
        }
    }
    
    /**
     * Get transformation string for algorithm
     */
    private fun getTransformation(algorithm: EncryptionAlgorithm): String {
        return when (algorithm) {
            EncryptionAlgorithm.AES_GCM -> TRANSFORMATION_AES_GCM
            EncryptionAlgorithm.AES_CBC -> TRANSFORMATION_AES_CBC
            EncryptionAlgorithm.AES_ECB -> "AES/ECB/PKCS5Padding"
        }
    }
    
    /**
     * Get key alias for sensitivity level
     */
    private fun getKeyAliasForSensitivity(sensitivity: DataSensitivity): String {
        return when (sensitivity) {
            DataSensitivity.LOW -> "${KEYSTORE_ALIAS}_LOW"
            DataSensitivity.MEDIUM -> KEYSTORE_ALIAS
            DataSensitivity.HIGH -> "${KEYSTORE_ALIAS}_HIGH"
            DataSensitivity.CRITICAL -> "${KEYSTORE_ALIAS}_CRITICAL"
        }
    }
    
    /**
     * Get encryption statistics
     */
    fun getEncryptionStats(): EncryptionStats {
        return EncryptionStats(
            totalEncryptions = encryptionCount.get(),
            totalDecryptions = decryptionCount.get(),
            keyCacheSize = keyCache.size,
            isKeystoreAvailable = keyStore.containsAlias(KEYSTORE_ALIAS),
            supportedAlgorithms = listOf(EncryptionAlgorithm.AES_GCM, EncryptionAlgorithm.AES_CBC, EncryptionAlgorithm.AES_ECB)
        )
    }
    
    /**
     * Clear key cache (for security)
     */
    fun clearKeyCache() {
        keyCache.clear()
        Log.d(TAG, "Key cache cleared")
    }
    
    /**
     * Encryption result data class
     */
    data class EncryptionResult(
        val isSuccess: Boolean,
        val encryptedData: String? = null,
        val algorithm: EncryptionAlgorithm? = null,
        val iv: String? = null,
        val keyAlias: String? = null,
        val errorMessage: String? = null
    ) {
        companion object {
            fun success(
                encryptedData: String,
                algorithm: EncryptionAlgorithm,
                iv: String? = null,
                keyAlias: String? = null
            ) = EncryptionResult(
                isSuccess = true,
                encryptedData = encryptedData,
                algorithm = algorithm,
                iv = iv,
                keyAlias = keyAlias
            )
            
            fun error(errorMessage: String) = EncryptionResult(
                isSuccess = false,
                errorMessage = errorMessage
            )
        }
    }
    
    /**
     * Decryption result data class
     */
    data class DecryptionResult(
        val isSuccess: Boolean,
        val decryptedData: String? = null,
        val errorMessage: String? = null
    ) {
        companion object {
            fun success(decryptedData: String) = DecryptionResult(
                isSuccess = true,
                decryptedData = decryptedData
            )
            
            fun error(errorMessage: String) = DecryptionResult(
                isSuccess = false,
                errorMessage = errorMessage
            )
        }
    }
}
